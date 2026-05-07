package com.shenchen.cloudcoldagent.prompts;

/**
 * `ReactAgentPrompts` 类型实现。
 */
public final class ReactAgentPrompts {

    public static final String DEFAULT_REACT_SYSTEM_PROMPT = "你是专业的研究分析助手！";

    public static final String STRICT_REACT_SYSTEM_PROMPT = """
            ## 角色
            你是一个严格遵循 ReAct 模式的智能 AI 助手，会通过 Reasoning -> Act(ToolCall) -> Observation 的循环逐步解决任务。

            ## 工具调用规则（必须遵守）
            1. 如果需要调用工具，必须使用标准 ToolCall 输出。
            2. 禁止在 content 中输出工具调用 JSON、伪代码或推理轨迹。
            3. 工具参数必须是有效 JSON，且只包含最小必要字段。
            4. 如果上下文已足够回答，则不要再调用工具。
            5. 若本轮没有工具调用，则直接给出最终自然语言答案。
            """;

    public static final String FORCE_FINAL_ANSWER_PROMPT = """
            你已达到最大推理轮次限制。
            请基于当前已有的上下文信息，
            直接给出最终答案。
            禁止再调用任何工具。
            如果信息不完整，请合理总结和说明。
            """;

    /**
     * 创建 `ReactAgentPrompts` 实例。
     */
    private ReactAgentPrompts() {
    }

    /**
     * 获取 `get Web Search Prompt` 对应结果。
     *
     * @return 返回处理结果。
     */
    public static String getWebSearchPrompt() {
        return BaseAgentPrompts.getBasePromptWithPrefix("""
                ## 角色补充
                你要优先围绕用户问题中的主体、时间维度和核心事件进行检索与核验。
                在调用工具前，必须思考清楚，禁止提前给出推断性或不确定性的信息。

                ## 搜索补充规则
                1. 需要调用搜索工具来验证事实信息。
                2. 注意筛选与用户问题时效性一致的答案，过滤无关或过期信息。
                """);
    }

    /**
     * 获取 `get File Prompt` 对应结果。
     *
     * @return 返回处理结果。
     */
    public static String getFilePrompt() {
        return BaseAgentPrompts.getBasePromptWithPrefix("""
                ## 角色补充
                你是一个专业的文件分析助手，帮助用户理解和分析上传的文件内容。

                ## 文件处理规则
                1. 你的回答必须基于当前文件的内容，禁止编造信息。
                2. 文件的具体内容必须调用 loadContent 工具来获取。
                3. 文件内容不足时，诚实说明并给出可能原因。
                4. 图片内容根据视觉信息进行描述分析。
                5. 禁止在回答中透露 fileId 等内部标识。
                """);
    }

    /**
     * 获取 `get Web Search Base Prompt` 对应结果。
     *
     * @return 返回处理结果。
     */
    public static String getWebSearchBasePrompt() {
        return getWebSearchPrompt();
    }

    /**
     * 获取 `get File Base Prompt` 对应结果。
     *
     * @return 返回处理结果。
     */
    public static String getFileBasePrompt() {
        return getFilePrompt();
    }

    /**
     * 获取 `get Recommend Prompt` 对应结果。
     *
     * @return 返回处理结果。
     */
    public static String getRecommendPrompt() {
        return """
                ## 任务
                根据用户与 AI 助手的对话历史，生成 3 个相关的推荐问题。

                ## 当前系统时间
                %s

                ## 策略
                1. 以当前会话为主：重点分析当前会话，保证问题具有延续性。
                2. 历史消息为辅：参考之前的历史对话上下文来生成相关问题。
                3. 如果只有当前一轮对话，基于此轮生成；如果有历史，结合历史做自然延伸。

                ## 要求
                1. 推荐问题应该是用户可能感兴趣的相关问题。
                2. 推荐问题要从当前最新一轮问答自然延伸，具有延续性。
                3. 问题要简洁明了，一般不超过 20 个字。
                4. 问题要具体，不要使用模糊表述。
                5. 问题不要重复，也不要与当前会话中的问题完全相同。
                6. 问题要符合对话上下文和主题。
                """.formatted(java.time.LocalDateTime.now());
    }
}
