package com.shenchen.cloudcoldagent.prompts;

import cn.hutool.json.JSONUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

/**
 * `SkillWorkflowPrompts` 类型实现。
 */
public final class SkillWorkflowPrompts {

    /**
     * 创建 `SkillWorkflowPrompts` 实例。
     */
    private SkillWorkflowPrompts() {
    }

    /**
     * 构建 `build Selected Skill Runtime Header Prompt` 对应结果。
     *
     * @return 返回处理结果。
     */
    public static String buildSelectedSkillRuntimeHeaderPrompt() {
        return """
                [Available Skill Context]
                以下 skills 已经为本轮选定，请在需要时完整阅读并严格遵循其约束。
                规则：
                1. 参数只能来自用户本轮问题原文，或 skill 中明确声明的默认值。
                2. 缺少必填参数时，应先向用户补问，不要自行编造。
                3. 不要猜测脚本路径、参数名或默认值。

                """;
    }

    /**
     * 构建 `build Bound Skill Recognition Prompt` 对应结果。
     *
     * @return 返回处理结果。
     */
    public static String buildBoundSkillRecognitionPrompt() {
        return """
                你是 skill 绑定相关性识别节点。
                你的任务是判断“当前会话已绑定的 skills”里，哪些与用户本轮问题真正相关。

                规则：
                1. 已绑定不等于必用，必须按当前问题重新判断相关性。
                2. 如果某个 skill 与本轮问题无关，relevant 必须为 false。
                3. skillName 必须使用输入里已有的原始名称，禁止改写。
                4. 输出必须严格符合结构化 schema，不要附加解释。
                5. 每个结果项只保留 skillName 和 relevant。
                6. 返回结果使用 items 字段承载数组。
                """;
    }

    /**
     * 构建 `build Bound Skill Recognition Input` 对应结果。
     *
     * @param question question 参数。
     * @param historyText historyText 参数。
     * @param metadataJson metadataJson 参数。
     * @return 返回处理结果。
     */
    public static String buildBoundSkillRecognitionInput(String question, String historyText, String metadataJson) {
        return """
                最近对话历史（按时间顺序，若为空则忽略）：
                %s

                用户问题：
                %s

                已绑定 skill metadata 列表：
                %s
                """.formatted(
                StringUtils.defaultIfBlank(historyText, "（无）"),
                StringUtils.defaultString(question),
                StringUtils.defaultString(metadataJson)
        );
    }

    /**
     * 构建 `build Unbound Skill Discovery Prompt` 对应结果。
     *
     * @return 返回处理结果。
     */
    public static String buildUnboundSkillDiscoveryPrompt() {
        return """
                你是 skill 发现节点。
                你的任务是从“未绑定的 skill metadata 列表”中判断，当前问题可能还需要哪些额外 skill。

                规则：
                1. 只有真正相关的 skill 才能返回 relevant=true。
                2. skillName 必须使用输入里已有的原始名称，禁止自造或改写。
                3. 如果没有相关 skill，items 返回空数组。
                4. 输出必须严格符合结构化 schema，不要附加解释。
                5. 每个结果项只保留 skillName 和 relevant。
                6. 返回结果使用 items 字段承载数组。
                """;
    }

    /**
     * 构建 `build Unbound Skill Discovery Input` 对应结果。
     *
     * @param question question 参数。
     * @param historyText historyText 参数。
     * @param metadataJson metadataJson 参数。
     * @return 返回处理结果。
     */
    public static String buildUnboundSkillDiscoveryInput(String question, String historyText, String metadataJson) {
        return """
                最近对话历史（按时间顺序，若为空则忽略）：
                %s

                用户问题：
                %s

                可供发现的未绑定 skill metadata 列表：
                %s
                """.formatted(
                StringUtils.defaultIfBlank(historyText, "（无）"),
                StringUtils.defaultString(question),
                StringUtils.defaultString(metadataJson)
        );
    }

    /**
     * 处理 `render Conversation History` 对应逻辑。
     *
     * @param history history 参数。
     * @return 返回处理结果。
     */
    public static String renderConversationHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "（无）";
        }

        StringBuilder sb = new StringBuilder();
        for (Message message : history) {
            if (message == null) {
                continue;
            }
            MessageType messageType = message.getMessageType();
            String role = switch (messageType) {
                case USER -> "用户";
                case ASSISTANT -> "助手";
                case SYSTEM -> "系统";
                case TOOL -> "工具";
            };
            sb.append(role)
                    .append("：")
                    .append(StringUtils.defaultString(message.getText()))
                    .append("\n");
        }
        String rendered = sb.toString().trim();
        return rendered.isEmpty() ? "（无）" : rendered;
    }
}
