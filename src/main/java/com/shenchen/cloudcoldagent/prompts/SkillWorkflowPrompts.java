package com.shenchen.cloudcoldagent.prompts;

import cn.hutool.json.JSONUtil;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillExecutionPlan;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SkillWorkflowPrompts {

    private SkillWorkflowPrompts() {
    }

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

    public static String buildExecutionPlanPrompt() {
        return """
                你是 skill 执行计划生成节点。
                你需要基于用户问题、多个 skill 的说明正文和资源清单，批量产出后续执行阶段所需的结构化 skill execution plans。

                规则：
                1. 不要猜测输入中不存在的 scriptPath 或 reference 路径，优先使用资源清单和正文中已有信息。
                2. arguments 只能来自用户问题原文，或者 skill 正文里明确声明的默认值。
                3. argumentSpecs 必须只描述当前 skill 固定脚本真实支持的参数，不要臆造参数名。
                4. 如果参数不足或当前 skill 不可执行，executable 必须为 false，toolCallPlan 置为 null。
                5. 如果是因为缺少必填参数导致不可执行，blockingReason 必须为 MISSING_REQUIRED_ARGUMENTS。
                6. 当 blockingReason=MISSING_REQUIRED_ARGUMENTS 时，blockingUserMessage 必须直接面向用户，清楚说明当前缺少哪些信息才能继续，不要输出 JSON，不要输出 schema 描述，不要输出内部字段名解释。
                7. blockingUserMessage 必须结合 argumentSpecs 里的 displayName、skill 正文和用户当前问题生成，语气自然、简洁、可直接展示给前端。
                8. 如果不是缺少必填参数导致不可执行，blockingReason 和 blockingUserMessage 置为 null。
                9. 如果 skill 与当前问题无关，selected 必须为 false。
                10. 如果可直接执行固定脚本，toolCallPlan.toolName 必须为 execute_skill_script。
                11. request 中必须包含 skillName、scriptPath、argumentSpecs、arguments。
                12. argumentSpecs 每个参数对象只允许使用 name、displayName、type、required、defaultValue 这 5 个字段，不要输出 description，不要输出 optional。
                13. displayName 由模型直接输出，要求使用清晰的中文参数名称，便于前端在 HITL 弹窗中展示。
                14. 每个 execution plan 只保留 skillName、selected、executable、toolCallPlan、blockingReason、blockingUserMessage。
                15. toolCallPlan 只保留 toolName 和 request，不要输出 summary。
                16. 必须返回 items 数组，数组中的每一项对应输入中的一个 skill。
                17. skillName 必须使用输入里已有的原始名称，禁止改写。
                18. 输出必须严格符合结构化 schema，不要附加解释。
                """;
    }

    public static String buildExecutionPlanInput(String question,
                                                 String historyText,
                                                 String batchSkillInputJson) {
        return """
                最近对话历史（按时间顺序，若为空则忽略）：
                %s

                用户问题：
                %s

                待批量生成 execution plan 的 skills：
                %s
                """.formatted(
                StringUtils.defaultIfBlank(historyText, "（无）"),
                StringUtils.defaultString(question),
                StringUtils.defaultString(batchSkillInputJson)
        );
    }

    public static String buildExecutionPlanBatchInput(List<Map<String, Object>> skillInputs) {
        return JSONUtil.toJsonStr(skillInputs == null ? List.of() : skillInputs);
    }

    public static String buildEnhancedQuestion(List<String> selectedSkills,
                                               Map<String, String> skillContents,
                                               List<SkillExecutionPlan> executionPlans,
                                               String question) {
        if ((selectedSkills == null || selectedSkills.isEmpty())
                || skillContents == null
                || skillContents.isEmpty()) {
            return question;
        }
        String executionPlanJson = JSONUtil.toJsonStr(executionPlans == null ? List.of() : executionPlans);
        String skillBundleJson = JSONUtil.toJsonStr(buildSkillBundle(selectedSkills, skillContents));

        return """
                [Skill Workflow Context]
                系统已在进入执行阶段前完成完整的 skill 前置工作流。
                下面已经是本轮真正需要使用的 skills 的完整 SKILL.md 正文与执行 hints。
                这些 skills 已经完成了：
                1. 是否相关的判断
                2. 是否应纳入本轮执行的筛选
                3. 渐进式披露与完整 SKILL.md 读取
                4. 初步执行意图识别

                已选定 skills（结构化）：
                %s

                已选定 skills 的完整 SKILL.md 内容（结构化）：
                %s

                已生成的执行 plans（结构化）：
                %s

                规则：
                1. 执行阶段不要再调用 read_skill、read_skill_resource、list_skill_resources，也不要再次判断 skill 是否相关。
                2. 直接把上面的完整 SKILL.md 当作本轮可用规范来源来规划和执行。
                3. 如果某个 skill execution plan 已经给出固定 toolName、skillName、scriptPath、arguments，则优先直接执行，不要重新拼参数。
                4. 不要猜测脚本名、参数名、默认值；优先遵循上面的完整 SKILL.md 和 execution plans。
                5. 只有当 execution plan 明确表示参数不足时，才向用户补问缺失字段。
                6. 最终回答必须基于工具结果，不要凭模型常识改写数值。

                [用户问题]
                %s
                """.formatted(
                JSONUtil.toJsonStr(selectedSkills),
                skillBundleJson,
                executionPlanJson,
                StringUtils.defaultString(question)
        );
    }

    private static List<Map<String, String>> buildSkillBundle(List<String> selectedSkills, Map<String, String> skillContents) {
        return selectedSkills.stream()
                .map(skillName -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("skillName", skillName);
                    item.put("content", StringUtils.defaultString(skillContents.get(skillName)));
                    return item;
                })
                .toList();
    }

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
