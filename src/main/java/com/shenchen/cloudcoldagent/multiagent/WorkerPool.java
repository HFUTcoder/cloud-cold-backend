package com.shenchen.cloudcoldagent.multiagent;

import com.shenchen.cloudcoldagent.agent.SimpleReactAgent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker Agent 池，管理 SimpleReactAgent 的创建、复用和回收。
 */
@Slf4j
public class WorkerPool {

    private final ChatModel chatModel;
    private final List<ToolCallback> allTools;
    private final int maxWorkers;
    private final int maxRoundsPerWorker;
    private final Executor toolExecutor;
    private final Executor virtualThreadExecutor;

    // 所有 Worker
    private final Map<String, WorkerAgent> allWorkers = new ConcurrentHashMap<>();

    // 空闲 Worker 队列
    private final BlockingQueue<WorkerAgent> idleWorkers = new LinkedBlockingQueue<>();

    // Worker 计数器
    private final AtomicInteger workerCounter = new AtomicInteger(0);

    @Getter
    private volatile boolean shutdown = false;

    public WorkerPool(ChatModel chatModel, List<ToolCallback> allTools, int maxWorkers,
                      int maxRoundsPerWorker, Executor toolExecutor, Executor virtualThreadExecutor) {
        this.chatModel = chatModel;
        this.allTools = allTools;
        this.maxWorkers = maxWorkers;
        this.maxRoundsPerWorker = maxRoundsPerWorker;
        this.toolExecutor = toolExecutor;
        this.virtualThreadExecutor = virtualThreadExecutor;

        // 预创建 Worker
        int initialWorkers = Math.min(2, maxWorkers);
        for (int i = 0; i < initialWorkers; i++) {
            createWorker();
        }
        log.info("WorkerPool 初始化完成，预创建 {} 个 Worker，最大容量 {}", initialWorkers, maxWorkers);
    }

    /**
     * 获取一个空闲 Worker（阻塞等待）
     */
    public WorkerAgent acquire() throws InterruptedException {
        return acquire(30, TimeUnit.SECONDS);
    }

    /**
     * 获取一个空闲 Worker（带超时）
     */
    public WorkerAgent acquire(long timeout, TimeUnit unit) throws InterruptedException {
        if (shutdown) {
            throw new IllegalStateException("WorkerPool 已关闭");
        }

        // 1. 从空闲队列获取
        WorkerAgent worker = idleWorkers.poll(timeout, unit);
        if (worker != null) {
            log.debug("从空闲队列获取 Worker: {}", worker.getWorkerId());
            return worker;
        }

        // 2. 尝试创建新 Worker
        worker = createWorkerIfAllowed();
        if (worker != null) {
            return worker;
        }

        // 3. 继续等待
        return idleWorkers.poll(timeout, unit);
    }

    /**
     * 非阻塞获取
     */
    public WorkerAgent tryAcquire() {
        if (shutdown) return null;

        WorkerAgent worker = idleWorkers.poll();
        if (worker != null) {
            return worker;
        }
        return createWorkerIfAllowed();
    }

    /**
     * 释放 Worker 回池
     */
    public void release(WorkerAgent worker) {
        if (worker == null || shutdown) return;

        worker.release();
        boolean offered = idleWorkers.offer(worker);
        if (!offered) {
            log.warn("Worker {} 归还失败，队列已满", worker.getWorkerId());
        } else {
            log.debug("Worker {} 已归还到池", worker.getWorkerId());
        }
    }

    /**
     * 批量获取 Worker
     */
    public List<WorkerAgent> acquireBatch(int count) throws InterruptedException {
        List<WorkerAgent> workers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WorkerAgent worker = acquire(10, TimeUnit.SECONDS);
            if (worker != null) {
                workers.add(worker);
            } else {
                break;
            }
        }
        return workers;
    }

    /**
     * 批量释放 Worker
     */
    public void releaseBatch(List<WorkerAgent> workers) {
        if (workers != null) {
            workers.forEach(this::release);
        }
    }

    /**
     * 创建新 Worker（如果允许）
     */
    private synchronized WorkerAgent createWorkerIfAllowed() {
        if (allWorkers.size() >= maxWorkers || shutdown) {
            return null;
        }
        return createWorker();
    }

    /**
     * 创建 Worker
     */
    private WorkerAgent createWorker() {
        String workerId = "worker-" + workerCounter.incrementAndGet();

        // 工具隔离：过滤掉协调者专属工具
        List<ToolCallback> workerTools = filterToolsForWorker(allTools);

        // 日志：输出 Worker 可用的工具详情
        log.info("========== 创建 Worker {} ==========", workerId);
        log.info("输入工具总数: {}", allTools.size());
        log.info("过滤后工具数: {}", workerTools.size());
        for (ToolCallback tool : workerTools) {
            log.info("  工具: {} - {}", tool.getToolDefinition().name(),
                    tool.getToolDefinition().description() != null ?
                    tool.getToolDefinition().description().substring(0, Math.min(50, tool.getToolDefinition().description().length())) : "无描述");
        }

        SimpleReactAgent agent = SimpleReactAgent.builder()
                .name(workerId)
                .chatModel(chatModel)
                .tools(workerTools)
                .systemPrompt(getWorkerSystemPrompt())
                .maxRounds(maxRoundsPerWorker)
                .toolConcurrency(2)
                .toolExecutor(toolExecutor)
                .virtualThreadExecutor(virtualThreadExecutor)
                .build();

        WorkerAgent worker = new WorkerAgent(workerId, agent);
        allWorkers.put(workerId, worker);
        idleWorkers.offer(worker);

        log.info("Worker: {} 创建完成，当前池大小: {}", workerId, allWorkers.size());
        return worker;
    }

    /**
     * 工具过滤（Worker 不能调用协调者工具）
     */
    private List<ToolCallback> filterToolsForWorker(List<ToolCallback> tools) {
        Set<String> workerBlocked = Set.of(
                "dispatch_to_worker",
                "ask_user"
        );

        return tools.stream()
                .filter(tool -> {
                    String name = tool.getToolDefinition().name();
                    return !workerBlocked.contains(name);
                })
                .toList();
    }

    private String getWorkerSystemPrompt() {
        return CoordinatorPrompts.WORKER_SYSTEM_PROMPT;
    }

    /**
     * 获取池状态
     */
    public PoolStatus getStatus() {
        return new PoolStatus(allWorkers.size(), idleWorkers.size(), allWorkers.size() - idleWorkers.size());
    }

    /**
     * 关闭池
     */
    public void shutdown() {
        this.shutdown = true;
        allWorkers.clear();
        idleWorkers.clear();
        log.info("WorkerPool 已关闭");
    }

    public record PoolStatus(int total, int idle, int busy) {}
}
