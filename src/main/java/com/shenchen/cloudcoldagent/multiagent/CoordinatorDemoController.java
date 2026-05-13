package com.shenchen.cloudcoldagent.multiagent;

import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.common.BaseResponse;
import com.shenchen.cloudcoldagent.common.ResultUtils;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.PlanExecuteCallResult;
import com.shenchen.cloudcoldagent.model.vo.AgentStreamEvent;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coordinator 模式测试接口。
 *
 * 协调者复用 PlanExecuteAgent，子 Agent 复用 SimpleReactAgent。
 */
@Slf4j
@RestController
@RequestMapping("/multiagent")
@ConditionalOnProperty(prefix = "cloudcold.multiagent.coordinator", name = "enabled", havingValue = "true")
public class CoordinatorDemoController {

    @Autowired(required = false)
    private PlanExecuteAgent coordinatorAgent;

    @Autowired(required = false)
    private WorkerPool workerPool;

    @Autowired(required = false)
    private CoordinatorConfig coordinatorConfig;

    // ==================== 核心接口 ====================

    /**
     * 同步调用协调者（复用 PlanExecuteAgent）。
     */
    @PostMapping("/call")
    public BaseResponse<?> call(@RequestBody CoordinatorRequest request) {
        if (coordinatorAgent == null) {
            return ResultUtils.error(-1, "Coordinator 未启用");
        }

        String question = request.getQuestion();
        if (question == null || question.isBlank()) {
            return ResultUtils.error(-1, "question 不能为空");
        }

        log.info("[Coordinator] 收到同步请求: {}", abbreviate(question, 100));
        long startTime = System.currentTimeMillis();

        try {
            PlanExecuteCallResult result = coordinatorAgent.call(question);
            long costTime = System.currentTimeMillis() - startTime;
            log.info("[Coordinator] 同步请求完成，耗时: {}ms", costTime);

            Map<String, Object> data = new HashMap<>();
            data.put("answer", result.answer());
            data.put("costTimeMs", costTime);
            return ResultUtils.success(data);
        } catch (Exception e) {
            log.error("[Coordinator] 同步请求异常", e);
            return ResultUtils.error(-1, "执行异常: " + e.getMessage());
        }
    }

    /**
     * 流式调用协调者（复用 PlanExecuteAgent）。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentStreamEvent> stream(@RequestBody CoordinatorRequest request) {
        if (coordinatorAgent == null) {
            return Flux.error(new IllegalStateException("Coordinator 未启用"));
        }

        String question = request.getQuestion();
        if (question == null || question.isBlank()) {
            return Flux.error(new IllegalArgumentException("question 不能为空"));
        }

        log.info("[Coordinator] 收到流式请求: {}", abbreviate(question, 100));
        return coordinatorAgent.stream(question);
    }

    // ==================== 状态查询接口 ====================

    /**
     * 获取 Worker 池状态。
     */
    @GetMapping("/pool/status")
    public BaseResponse<?> getPoolStatus() {
        if (workerPool == null) {
            return ResultUtils.error(-1, "WorkerPool 未启用");
        }
        return ResultUtils.success(workerPool.getStatus());
    }

    /**
     * 获取配置信息。
     */
    @GetMapping("/config")
    public BaseResponse<?> getConfig() {
        if (coordinatorConfig == null) {
            return ResultUtils.error(-1, "CoordinatorConfig 未启用");
        }

        Map<String, Object> config = new HashMap<>();
        config.put("enabled", coordinatorConfig.isEnabled());
        config.put("maxWorkers", coordinatorConfig.getMaxWorkers());
        config.put("maxRoundsPerWorker", coordinatorConfig.getMaxRoundsPerWorker());
        config.put("maxCoordinatorRounds", coordinatorConfig.getMaxCoordinatorRounds());
        return ResultUtils.success(config);
    }

    /**
     * 健康检查。
     */
    @GetMapping("/health")
    public BaseResponse<?> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("coordinatorAgent", coordinatorAgent != null ? "ready" : "not_available");
        health.put("workerPool", workerPool != null ? "ready" : "not_available");

        if (workerPool != null) {
            WorkerPool.PoolStatus status = workerPool.getStatus();
            health.put("poolStatus", Map.of(
                    "total", status.total(),
                    "idle", status.idle(),
                    "busy", status.busy()
            ));
        }

        return ResultUtils.success(health);
    }

    // ==================== 测试辅助接口 ====================

    /**
     * 预热 Worker 池。
     */
    @PostMapping("/pool/warmup")
    public BaseResponse<?> warmupPool(@RequestParam(defaultValue = "2") int count) {
        if (workerPool == null) {
            return ResultUtils.error(-1, "WorkerPool 未启用");
        }

        int created = 0;
        for (int i = 0; i < count; i++) {
            try {
                WorkerAgent worker = workerPool.tryAcquire();
                if (worker != null) {
                    workerPool.release(worker);
                    created++;
                }
            } catch (Exception e) {
                break;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("requested", count);
        result.put("created", created);
        result.put("poolStatus", workerPool.getStatus());
        return ResultUtils.success(result);
    }

    /**
     * 测试单个 Worker 执行。
     */
    @PostMapping("/test/worker")
    public BaseResponse<?> testWorker(@RequestBody CoordinatorRequest request) {
        if (workerPool == null) {
            return ResultUtils.error(-1, "WorkerPool 未启用");
        }

        String question = request.getQuestion();
        if (question == null || question.isBlank()) {
            return ResultUtils.error(-1, "question 不能为空");
        }

        WorkerAgent worker = null;
        try {
            worker = workerPool.acquire(5, TimeUnit.SECONDS);
            if (worker == null) {
                return ResultUtils.error(-1, "无可用 Worker");
            }

            log.info("[Test] 测试 Worker {} 执行任务", worker.getWorkerId());
            long startTime = System.currentTimeMillis();

            WorkerTask task = WorkerTask.create("test-1", question);
            WorkerTask result = worker.execute(task);

            long costTime = System.currentTimeMillis() - startTime;

            Map<String, Object> data = new HashMap<>();
            data.put("workerId", worker.getWorkerId());
            data.put("taskId", result.taskId());
            data.put("status", result.status());
            data.put("result", result.result());
            data.put("error", result.error());
            data.put("costTimeMs", costTime);

            return ResultUtils.success(data);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResultUtils.error(-1, "执行被中断");
        } catch (Exception e) {
            log.error("[Test] Worker 测试异常", e);
            return ResultUtils.error(-1, "测试异常: " + e.getMessage());
        } finally {
            if (worker != null) {
                workerPool.release(worker);
            }
        }
    }

    /**
     * 批量测试。
     */
    @PostMapping("/test/batch")
    public BaseResponse<?> testBatch(@RequestBody BatchTestRequest request) {
        if (workerPool == null) {
            return ResultUtils.error(-1, "WorkerPool 未启用");
        }

        int count = request.getCount() > 0 ? request.getCount() : 3;
        String baseQuestion = request.getQuestion() != null ? request.getQuestion() : "简单介绍一下自己";

        log.info("[Test] 批量测试，任务数: {}", count);
        long startTime = System.currentTimeMillis();

        try {
            List<WorkerAgent> workers = workerPool.acquireBatch(count);
            if (workers.isEmpty()) {
                return ResultUtils.error(-1, "无可用 Worker");
            }

            List<WorkerTask> tasks = new ArrayList<>();
            for (int i = 0; i < workers.size(); i++) {
                tasks.add(WorkerTask.create("batch-" + (i + 1), baseQuestion));
            }

            // 并行执行
            List<CompletableFuture<WorkerTask>> futures = new ArrayList<>();
            for (int i = 0; i < Math.min(workers.size(), tasks.size()); i++) {
                WorkerAgent worker = workers.get(i);
                WorkerTask task = tasks.get(i);
                futures.add(CompletableFuture.supplyAsync(() -> worker.execute(task)));
            }

            // 等待完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(60, TimeUnit.SECONDS);

            // 收集结果
            List<Map<String, Object>> results = new ArrayList<>();
            for (CompletableFuture<WorkerTask> future : futures) {
                try {
                    WorkerTask result = future.getNow(null);
                    if (result != null) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("taskId", result.taskId());
                        item.put("status", result.status());
                        item.put("result", abbreviate(result.result(), 200));
                        results.add(item);
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

            // 归还 Worker
            workerPool.releaseBatch(workers);

            long costTime = System.currentTimeMillis() - startTime;

            Map<String, Object> data = new HashMap<>();
            data.put("taskCount", results.size());
            data.put("results", results);
            data.put("costTimeMs", costTime);
            data.put("poolStatus", workerPool.getStatus());

            return ResultUtils.success(data);
        } catch (Exception e) {
            log.error("[Test] 批量测试异常", e);
            return ResultUtils.error(-1, "测试异常: " + e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    // ==================== 请求体定义 ====================

    @Data
    public static class CoordinatorRequest {
        /** 用户问题 */
        private String question;
    }

    @Data
    public static class BatchTestRequest {
        /** 测试问题 */
        private String question;
        /** 并行任务数 */
        private int count;
    }
}
