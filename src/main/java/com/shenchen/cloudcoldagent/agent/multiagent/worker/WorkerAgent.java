package com.shenchen.cloudcoldagent.agent.multiagent.worker;

import com.shenchen.cloudcoldagent.agent.SimpleReactAgent;
import com.shenchen.cloudcoldagent.model.entity.record.agent.multiagent.WorkerTask;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 可复用的 Worker Agent，封装 SimpleReactAgent 并管理状态。
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
     * 执行任务
     */
    public WorkerTask execute(WorkerTask task) {
        if (!status.compareAndSet(WorkerStatus.IDLE, WorkerStatus.BUSY)) {
            throw new IllegalStateException("Worker " + workerId + " 不在空闲状态，当前状态: " + status.get());
        }

        this.currentTask = task.start();
        log.info("========== [Worker {} 执行任务] ==========", workerId);
        log.info("输入 - taskId: {}", task.taskId());
        log.info("输入 - description: {}", task.description());

        try {
            String result = delegate.call(task.description());
            this.currentTask = task.withResult(result);
            log.info("---------- [Worker {} 完成任务] ----------", workerId);
            log.info("输出 - taskId: {}", task.taskId());
            log.info("输出 - 结果长度: {}", result.length());
            log.info("输出 - 结果内容:\n{}", abbreviate(result, 500));
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
}
