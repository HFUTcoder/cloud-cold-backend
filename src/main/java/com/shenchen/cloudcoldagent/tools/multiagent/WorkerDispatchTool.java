package com.shenchen.cloudcoldagent.tools.multiagent;

import com.shenchen.cloudcoldagent.agent.multiagent.worker.WorkerAgent;
import com.shenchen.cloudcoldagent.agent.multiagent.worker.WorkerPool;
import com.shenchen.cloudcoldagent.enums.WorkerTaskStatusEnum;
import com.shenchen.cloudcoldagent.model.entity.record.agent.multiagent.WorkerTask;
import com.shenchen.cloudcoldagent.tools.BaseTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Worker 派发工具，供协调者将子任务派发给 Worker 池执行。
 */
@Component
public class WorkerDispatchTool extends BaseTool {

    public static final String TOOL_NAME = "dispatch_to_worker";

    private final WorkerPool workerPool;

    /**
     * 注入 Worker 池。
     *
     * @param workerPool Worker 池实例。
     */
    public WorkerDispatchTool(WorkerPool workerPool) {
        super(false);
        this.workerPool = workerPool;
    }

    /**
     * 派发任务给 Worker 执行。
     *
     * @param taskDescription 任务的详细描述。
     * @return Worker 执行结果。
     */
    @Tool(name = TOOL_NAME, description = """
            将子任务派发给 Worker 执行。Worker 会自主调用其持有的工具完成任务并返回结果。
            taskDescription 需写清楚任务的目标和要求，便于 Worker 独立完成。
            有多个独立子任务时，分别调用本工具即可并行执行。
            """)
    public String dispatchToWorker(
            @ToolParam(description = "任务的详细描述，包含具体要调研的内容") String taskDescription) {
        logToolStart(TOOL_NAME, "taskDescription", taskDescription);

        WorkerAgent worker = null;
        try {
            worker = workerPool.acquire(10, TimeUnit.SECONDS);
            if (worker == null) {
                return "错误：当前无可用 Worker，请稍后重试";
            }

            WorkerTask task = WorkerTask.create("task-" + System.currentTimeMillis(), taskDescription);
            WorkerTask result = worker.execute(task);

            if (result.status() == WorkerTaskStatusEnum.COMPLETED) {
                logToolSuccess(TOOL_NAME, taskDescription, result.result());
                return result.result();
            } else {
                return "任务执行失败：" + result.error();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "任务执行被中断";
        } catch (Exception e) {
            return handleToolException(TOOL_NAME, taskDescription, e, "任务执行异常：");
        } finally {
            if (worker != null) {
                workerPool.release(worker);
            }
        }
    }
}
