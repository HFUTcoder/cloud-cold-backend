package com.shenchen.cloudcoldagent.prompts.multiagent;

import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.prompts.PlanExecutePrompts;

import java.time.LocalDateTime;

/**
 * Coordinator 模式提示词管理。
 * <p>
 * 包含协调者任务规划、批判评估、结果合成等提示词。
 */
public final class CoordinatorPrompts {

    private CoordinatorPrompts() {
    }

    /**
     * 协调者系统提示词。
     * 定义协调者的角色、职责和工作流程。
     */
    public static final String COORDINATOR_SYSTEM_PROMPT = """
            你是一个任务协调者（Coordinator），负责将复杂任务分解并派发给 Worker 执行。

            ## 你的职责
            1. **任务规划**：分析用户问题，将其分解为可并行执行的独立子任务
            2. **结果评估**：评估 Worker 的执行结果是否满足需求
            3. **答案合成**：将多个 Worker 的结果综合成完整、有条理的最终答案

            ## 工作原则
            - 任务分解要清晰、独立，避免依赖关系
            - 每个子任务的描述要足够详细，让 Worker 能独立完成
            - 能并行的任务尽量并行（相同 order）
            - 评估结果时要客观，不满足要求就继续补充

            ## 重要约束
            - 你不能直接执行任务，只能通过 Worker 完成
            - 你不能直接与用户对话，只能通过最终答案与用户沟通
            """;

    // ==================== 任务规划提示词 ====================

    /**
     * 简化版任务规划提示词（无工具描述）。
     */
    public static final String PLAN_PROMPT_SIMPLE = """
            你是一个任务规划专家。请将用户的问题分解为可并行执行的子任务。

            ## 可用工具与上下文
            %s

            ## 已执行任务历史
            %s

            ## 规划要求
            1. 每个子任务要独立、清晰、可执行
            2. 每个子任务必须指定 toolName（使用哪个工具）和 arguments（工具参数）
            3. 任务描述要足够详细，让执行者能独立完成
            4. **重要：能并行的任务必须使用相同的 order**，相同 order 的任务会被同时并行执行
            5. 只有存在强依赖关系的任务才使用不同的 order
            6. 尽量让任务并行执行，提高效率
            7. 如果问题简单无需分解，返回空数组 []

            %s
            """;

    // ==================== 批判评估提示词 ====================

    /**
     * 批判评估提示词（简化版，无历史上下文）。
     */
    public static final String CRITIQUE_PROMPT_SIMPLE = """
            你是协调者的评估专家。请评估当前任务执行结果是否满足用户需求。

            ## 用户问题
            {question}

            ## 执行结果
            {results}

            ## 评估标准
            1. 结果是否完整回答了用户问题
            2. 信息是否准确、有实质内容
            3. 是否需要补充更多信息

            ## 输出格式
            请以 JSON 格式输出评估结果：
            - passed: true（已满足）或 false（未满足）
            - action: "SUMMARIZE"（可总结）、"CONTINUE"（需继续）、"ASK_USER"（需用户补充）
            - feedback: 具体的反馈说明

            只输出 JSON，不要输出其他内容。
            """;

    // ==================== 结果合成提示词 ====================

    /**
     * 结果合成提示词模板。
     * 用于将多个 Worker 的结果合成为最终答案。
     * <p>
     * 占位符：
     * - {question}: 用户问题
     * - {results}: 执行结果
     */
    public static final String SYNTHESIZE_PROMPT_TEMPLATE = """
            你是协调者，需要根据各任务的执行结果，合成最终答案。

            ## 用户问题
            {question}

            ## 执行结果
            {results}

            ## 合成要求
            1. 综合所有结果，不要遗漏重要信息
            2. 按逻辑组织，使答案有条理
            3. 如果有冲突信息，说明原因
            4. 语言要流畅、专业

            请给出完整、准确、有条理的最终答案。
            """;

    /**
     * 流式合成提示词（要求简洁输出）。
     */
    public static final String SYNTHESIZE_PROMPT_STREAMING = """
            你是协调者，需要根据各任务的执行结果，合成最终答案。

            ## 用户问题
            {question}

            ## 执行结果
            {results}

            ## 合成要求
            1. 综合所有结果，不要遗漏重要信息
            2. 按逻辑组织，使答案有条理
            3. 语言要流畅、专业
            4. 直接输出答案，不要加"最终答案"等前缀

            请给出完整、准确、有条理的最终答案。
            """;

    // ==================== PromptProvider 工厂 ====================

    /**
     * 创建 Coordinator 专属的 PromptProvider 实例。
     * <p>
     * 用于注入 PlanExecuteAgent，使其 plan/critique/summarize/compress 阶段
     * 使用 Coordinator 专属的中文 prompt 模板，而非通用的 PlanExecutePrompts。
     */
    public static PlanExecuteAgent.PromptProvider createPromptProvider() {
        return new PlanExecuteAgent.PromptProvider() {
            @Override
            public String buildPlanPrompt(LocalDateTime now, int round, String skillContext,
                                          String executedTaskHistory, String outputFormat) {
                return String.format(PLAN_PROMPT_SIMPLE,
                        skillContext != null && !skillContext.isBlank() ? skillContext : "无",
                        executedTaskHistory != null && !executedTaskHistory.isBlank() ? executedTaskHistory : "无",
                        outputFormat != null ? outputFormat : "");
            }

            @Override
            public String buildCritiquePrompt() {
                return CRITIQUE_PROMPT_SIMPLE;
            }

            @Override
            public String buildSummarySystemPrompt() {
                return SYNTHESIZE_PROMPT_STREAMING;
            }

            @Override
            public String buildSummaryUserPrompt(String question, String confirmedArguments,
                                                 String renderedMessages) {
                return SYNTHESIZE_PROMPT_TEMPLATE
                        .replace("{question}", question)
                        .replace("{results}", renderedMessages);
            }

            @Override
            public String buildCompressPrompt(int contextCharLimit) {
                return PlanExecutePrompts.formatCompressPrompt(contextCharLimit);
            }
        };
    }

}
