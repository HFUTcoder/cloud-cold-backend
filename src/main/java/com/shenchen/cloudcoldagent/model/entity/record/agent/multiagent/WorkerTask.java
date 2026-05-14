package com.shenchen.cloudcoldagent.model.entity.record.agent.multiagent;

import com.shenchen.cloudcoldagent.enums.WorkerTaskStatusEnum;

import java.util.Map;

/**
 * Worker 任务定义，用于协调者向 Worker 派发任务。
 */
public record WorkerTask(
        String taskId,
        String description,
        Map<String, Object> context,
        WorkerTaskStatusEnum status,
        String result,
        String error,
        long startTime,
        long endTime
) {

    public static WorkerTask create(String taskId, String description) {
        return new WorkerTask(taskId, description, Map.of(), WorkerTaskStatusEnum.PENDING, null, null, 0, 0);
    }

    public static WorkerTask create(String taskId, String description, Map<String, Object> context) {
        return new WorkerTask(taskId, description, context, WorkerTaskStatusEnum.PENDING, null, null, 0, 0);
    }

    public WorkerTask withStatus(WorkerTaskStatusEnum newStatus) {
        return new WorkerTask(taskId, description, context, newStatus, result, error, startTime, endTime);
    }

    public WorkerTask withResult(String result) {
        return new WorkerTask(taskId, description, context, WorkerTaskStatusEnum.COMPLETED, result, null, startTime, System.currentTimeMillis());
    }

    public WorkerTask withError(String error) {
        return new WorkerTask(taskId, description, context, WorkerTaskStatusEnum.FAILED, null, error, startTime, System.currentTimeMillis());
    }

    public WorkerTask start() {
        return new WorkerTask(taskId, description, context, WorkerTaskStatusEnum.RUNNING, result, error, System.currentTimeMillis(), endTime);
    }

    public boolean isCompleted() {
        return status == WorkerTaskStatusEnum.COMPLETED || status == WorkerTaskStatusEnum.FAILED;
    }

    public long getDurationMs() {
        if (startTime == 0) return 0;
        long end = endTime > 0 ? endTime : System.currentTimeMillis();
        return end - startTime;
    }
}
