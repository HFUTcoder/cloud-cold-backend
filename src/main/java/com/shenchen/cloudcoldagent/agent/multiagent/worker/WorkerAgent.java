package com.shenchen.cloudcoldagent.agent.multiagent.worker;

import com.shenchen.cloudcoldagent.agent.SimpleReactAgent;
import com.shenchen.cloudcoldagent.common.AgentStreamEventFactory;
import com.shenchen.cloudcoldagent.context.AgentRuntimeContext;
import com.shenchen.cloudcoldagent.model.entity.record.agent.multiagent.WorkerTask;
import com.shenchen.cloudcoldagent.model.vo.agent.AgentStreamEventData;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可复用的 Worker Agent，封装 SimpleReactAgent 并管理状态。
 * <p>
 * 执行任务时使用 {@link SimpleReactAgent#stream(String)} 进行流式调用，
 * 通过 {@link AgentRuntimeContext.MultiAgentEventEmitter} 向前端推送实时事件。
 */
@Slf4j
@Getter
public class WorkerAgent {

    private final String workerId;
    private final SimpleReactAgent delegate;
    private final AtomicReference<WorkerStatus> status = new AtomicReference<>(WorkerStatus.IDLE);
    private volatile WorkerTask currentTask;

    public enum WorkerStatus {
        IDLE,
        BUSY,
        DISABLED
    }

    public WorkerAgent(String workerId, SimpleReactAgent delegate) {
        this.workerId = workerId;
        this.delegate = delegate;
    }

    /**
     * 流式执行任务，边执行边通过 emitter 推送 Worker 事件，同时收集完整结果返回给协调者。
     */
    public WorkerTask execute(WorkerTask task) {
        if (!status.compareAndSet(WorkerStatus.IDLE, WorkerStatus.BUSY)) {
            throw new IllegalStateException("Worker " + workerId + " 不在空闲状态，当前状态: " + status.get());
        }

        this.currentTask = task.start();
        log.info("========== [Worker {} 执行任务] ==========", workerId);
        log.info("输入 - taskId: {}", task.taskId());
        log.info("输入 - description: {}", task.description());

        // 在流开始前捕获 emitter 和 conversationId（流在 virtual thread 上执行，ThreadLocal 不可靠）
        AgentRuntimeContext.MultiAgentEventEmitter emitter = AgentRuntimeContext.getCurrentEmitter();
        String conversationId = AgentRuntimeContext.getCurrentConversationId();

        try {
            // 发送 worker_start 事件
            emitWorkerStart(emitter, conversationId, task);

            // 流式调用 SimpleReactAgent，收集结果
            WorkerStreamResult streamResult = executeStreaming(task.description(), emitter, conversationId);

            if (streamResult.success) {
                String result = streamResult.content;
                this.currentTask = task.withResult(result);
                log.info("---------- [Worker {} 完成任务] ----------", workerId);
                log.info("输出 - taskId: {}", task.taskId());
                log.info("输出 - 结果长度: {}", result.length());
                log.info("输出 - 结果内容:\n{}", abbreviate(result, 500));
            } else {
                this.currentTask = task.withError(streamResult.error);
                log.warn("Worker {} 执行任务 {} 失败: {}", workerId, task.taskId(), streamResult.error);
            }
            return this.currentTask;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Worker {} 执行任务 {} 被中断", workerId, task.taskId());
            this.currentTask = task.withError("任务执行被中断");
            return this.currentTask;
        } catch (Exception e) {
            log.warn("Worker {} 执行任务 {} 失败: {}", workerId, task.taskId(), e.getMessage(), e);
            this.currentTask = task.withError(e.getMessage());
            return this.currentTask;
        } finally {
            this.currentTask = null;
            this.status.set(WorkerStatus.IDLE);
        }
    }

    /**
     * 流式执行并收集结果。
     */
    private WorkerStreamResult executeStreaming(String question,
                                                 AgentRuntimeContext.MultiAgentEventEmitter emitter,
                                                 String conversationId) throws InterruptedException {
        StringBuilder contentBuffer = new StringBuilder();
        AtomicReference<String> errorRef = new AtomicReference<>();
        AtomicBoolean hasCompleted = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        delegate.stream(question)
                .doOnNext(event -> {
                    if (event == null || event.getData() == null) {
                        return;
                    }
                    switch (event.getType()) {
                        case "assistant_delta" -> {
                            if (event.getData() instanceof AgentStreamEventData.AssistantDelta delta
                                    && delta.content() != null) {
                                contentBuffer.append(delta.content());
                                emitWorkerDelta(emitter, conversationId, delta.content());
                            }
                        }
                        case "final_answer" -> {
                            if (event.getData() instanceof AgentStreamEventData.FinalAnswer fa
                                    && fa.content() != null) {
                                // final_answer 替代之前增量中可能遗漏的内容
                                contentBuffer.setLength(0);
                                contentBuffer.append(fa.content());
                            }
                        }
                        case "error" -> {
                            if (event.getData() instanceof AgentStreamEventData.Error err) {
                                errorRef.set(err.message());
                            }
                        }
                        // thinking_step / hitl_interrupt / knowledge_retrieval / multi_agent 忽略
                    }
                })
                .doOnError(err -> {
                    errorRef.set(err.getMessage());
                    latch.countDown();
                })
                .doOnComplete(() -> {
                    hasCompleted.set(true);
                    latch.countDown();
                })
                .doFinally(signalType -> latch.countDown())
                .subscribe();

        // 阻塞等待流完成（Worker 在 toolExecutor 线程上执行，阻塞是安全的）
        boolean finished = latch.await(120, TimeUnit.SECONDS);
        if (!finished) {
            log.warn("Worker {} 流式执行超时", workerId);
            return new WorkerStreamResult(false, "", "Worker 执行超时（120s）");
        }

        String content = contentBuffer.toString();
        String error = errorRef.get();

        if (error != null && content.isEmpty()) {
            emitWorkerResult(emitter, conversationId, error, false);
            return new WorkerStreamResult(false, "", error);
        }

        emitWorkerResult(emitter, conversationId, content, true);
        return new WorkerStreamResult(true, content, null);
    }

    private void emitWorkerStart(AgentRuntimeContext.MultiAgentEventEmitter emitter,
                                  String conversationId, WorkerTask task) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.emit(AgentStreamEventFactory.multiAgentWorkerStart(
                    conversationId, workerId, task.taskId(), task.description()));
        } catch (Exception e) {
            log.warn("发送 worker_start 事件失败: {}", e.getMessage());
        }
    }

    private void emitWorkerDelta(AgentRuntimeContext.MultiAgentEventEmitter emitter,
                                  String conversationId, String content) {
        if (emitter == null || content == null || content.isEmpty()) {
            return;
        }
        try {
            emitter.emit(AgentStreamEventFactory.multiAgentWorkerDelta(
                    conversationId, workerId,
                    currentTask != null ? currentTask.taskId() : null,
                    content));
        } catch (Exception e) {
            log.warn("发送 worker_delta 事件失败: {}", e.getMessage());
        }
    }

    private void emitWorkerResult(AgentRuntimeContext.MultiAgentEventEmitter emitter,
                                   String conversationId, String summary, boolean success) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.emit(AgentStreamEventFactory.multiAgentWorkerResult(
                    conversationId, workerId,
                    currentTask != null ? currentTask.taskId() : null,
                    summary, success));
        } catch (Exception e) {
            log.warn("发送 worker_result 事件失败: {}", e.getMessage());
        }
    }

    /**
     * 尝试获取 Worker（CAS 设置为 BUSY）
     */
    public boolean tryAcquire() {
        return status.compareAndSet(WorkerStatus.IDLE, WorkerStatus.BUSY);
    }

    /**
     * 释放 Worker
     */
    public void release() {
        this.currentTask = null;
        this.status.set(WorkerStatus.IDLE);
    }

    public boolean isIdle() {
        return status.get() == WorkerStatus.IDLE;
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private record WorkerStreamResult(boolean success, String content, String error) {
    }
}
