package com.shenchen.cloudcoldagent.multiagent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Worker 派发工具。
 * <p>
 * 供协调者（PlanExecuteAgent）调用，将子任务派发给 Worker 池执行。
 */
@Slf4j
@Component
public class WorkerDispatchTool {

    private final WorkerPool workerPool;

    public WorkerDispatchTool(WorkerPool workerPool) {
        this.workerPool = workerPool;
    }

    /**
     * 派发任务给 Worker 执行。
     *
     * @param taskDescription 任务的详细描述
     * @return Worker 执行结果
     */
    @Tool(name = "dispatch_to_worker", description = """
            将子任务派发给 Worker 执行。Worker 会使用搜索工具获取信息并完成任务。
            适用场景：需要调研、搜索、分析信息的任务。
            参数 taskDescription 应该是详细的任务描述，包含具体要调研的内容。
            示例：调研 Spring AI 的核心特性和优势
            """)
    public String dispatchToWorker(
            @ToolParam(description = "任务的详细描述，包含具体要调研的内容") String taskDescription) {

        log.info("========== [dispatch_to_worker] ==========");
        log.info("输入 - taskDescription: {}", taskDescription);

        WorkerAgent worker = null;
        try {
            // 从池中获取 Worker
            worker = workerPool.acquire(10, TimeUnit.SECONDS);
            if (worker == null) {
                log.warn("无可用 Worker");
                return "错误：当前无可用 Worker，请稍后重试";
            }

            log.info("获取到 Worker: {}", worker.getWorkerId());

            // 创建任务
            WorkerTask task = WorkerTask.create("task-" + System.currentTimeMillis(), taskDescription);

            // 执行任务
            WorkerTask result = worker.execute(task);

            log.info("任务完成: status={}, resultLength={}",
                    result.status(),
                    result.result() != null ? result.result().length() : 0);

            if (result.status() == WorkerTask.TaskStatus.COMPLETED) {
                return result.result();
            } else {
                return "任务执行失败：" + result.error();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("任务执行被中断");
            return "任务执行被中断";
        } catch (Exception e) {
            log.error("任务执行异常", e);
            return "任务执行异常：" + e.getMessage();
        } finally {
            // 归还 Worker
            if (worker != null) {
                workerPool.release(worker);
                log.info("归还 Worker: {}", worker.getWorkerId());
            }
        }
    }
}
