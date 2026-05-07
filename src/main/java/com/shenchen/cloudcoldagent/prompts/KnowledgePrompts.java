package com.shenchen.cloudcoldagent.prompts;

/**
 * `KnowledgePrompts` 类型实现。
 */
public final class KnowledgePrompts {

    /**
     * 创建 `KnowledgePrompts` 实例。
     */
    private KnowledgePrompts() {
    }

    /**
     * 构建 `build Bound Knowledge Runtime Prompt` 对应结果。
     *
     * @return 返回处理结果。
     */
    public static String buildBoundKnowledgeRuntimePrompt() {
        return """
                当前会话已绑定知识库。
                如果上下文里已经提供知识库检索内容，请优先基于这些知识内容回答。
                如果仍需要继续检索，请默认使用当前会话已绑定知识库，不要臆造其它知识库。
                """.trim();
    }

    /**
     * 构建 `build No Hit Augmented Question` 对应结果。
     *
     * @param question question 参数。
     * @return 返回处理结果。
     */
    public static String buildNoHitAugmentedQuestion(String question) {
        return """
                请优先基于知识库内容回答用户问题。
                当前会话已绑定知识库，但本次预检索没有命中可直接使用的知识内容。
                如果无法仅根据知识库确定答案，请明确说明，不要编造。

                【用户问题】
                %s
                """.formatted(question);
    }

    /**
     * 构建 `build Hit Augmented Question` 对应结果。
     *
     * @param knowledgeContent knowledgeContent 参数。
     * @param question question 参数。
     * @return 返回处理结果。
     */
    public static String buildHitAugmentedQuestion(String knowledgeContent, String question) {
        return """
                请优先基于以下知识库内容回答用户问题。
                如果知识库内容不足以支持结论，请明确说明，不要编造。

                【知识库内容】
                %s

                【用户问题】
                %s
                """.formatted(knowledgeContent, question);
    }

    /**
     * 构建 `build Image Description Prompt` 对应结果。
     *
     * @return 返回处理结果。
     */
    public static String buildImageDescriptionPrompt() {
        return "请描述这张图片的内容，包括场景、对象、布局、颜色、文字信息，直接输出纯文本描述，不要多余说明。";
    }
}
