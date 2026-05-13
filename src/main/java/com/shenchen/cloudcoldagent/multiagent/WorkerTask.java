package com.shenchen.cloudcoldagent.multiagent;

import java.util.Map;

/**
 * Worker 任务定义，用于协调者向 Worker 派发任务。
 */
public record WorkerTask(
        String taskId,
        String description,
        Map<String, Object> context,
        TaskStatus status,
        String result,
        String error,
        long startTime,
        long endTime
) {

    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }

    public static WorkerTask create(String taskId, String description) {
        return new WorkerTask(taskId, description, Map.of(), TaskStatus.PENDING, null, null, 0, 0);
    }

    public static WorkerTask create(String taskId, String description, Map<String, Object> context) {
        return new WorkerTask(taskId, description, context, TaskStatus.PENDING, null, null, 0, 0);
    }

    public WorkerTask withStatus(TaskStatus newStatus) {
        return new WorkerTask(taskId, description, context, newStatus, result, error, startTime, endTime);
    }

    public WorkerTask withResult(String result) {
        return new WorkerTask(taskId, description, context, TaskStatus.COMPLETED, result, null, startTime, System.currentTimeMillis());
    }

    public WorkerTask withError(String error) {
        return new WorkerTask(taskId, description, context, TaskStatus.FAILED, null, error, startTime, System.currentTimeMillis());
    }

    public WorkerTask start() {
        return new WorkerTask(taskId, description, context, TaskStatus.RUNNING, result, error, System.currentTimeMillis(), endTime);
    }

    public boolean isCompleted() {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED;
    }

    public long getDurationMs() {
        if (startTime == 0) return 0;
        long end = endTime > 0 ? endTime : System.currentTimeMillis();
        return end - startTime;
    }
}
