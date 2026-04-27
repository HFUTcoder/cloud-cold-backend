package com.shenchen.cloudcoldagent.prompts;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class PlanExecutePrompts {

    private static final String PLAN_TASK_RULES = """
            你是执行计划生成器。
            你的职责是判断是否需要调用工具推进任务。
            如果无需工具，返回一个 id=null 的占位任务。
            如果需要工具，生成仅包含工具调用型任务的 JSON 数组。

            规则：
            1. 每个 task 都必须明确对应一个工具，并且必须输出 toolName 和 arguments。
            2. 不要规划总结、写报告、输出答案等非工具任务。
            3. order 相同表示可并行，order 更大表示依赖前序结果。
            4. arguments 必须是结构化 JSON 对象，不要把参数写进自然语言 summary。
            5. summary 只用于给人看，不能承载执行所需参数。
            6. 输出必须是严格 JSON 数组，不要附加解释。
            """;

    private static final String PLAN_SKILL_BOUNDARY_RULES = """
            ## Skill 执行边界（必须严格遵守）
            1. skill 的筛选、相关性判断、渐进式披露与完整 SKILL.md 读取，已经由前置 skill workflow 完成。
            2. 如果上下文里已经给出完整 SKILL.md 和 execution hints，不要再规划任何读取 skill 资源的动作。
            3. 若当前问题命中某个可执行型 skill，且上下文已给出固定脚本与参数，则第一轮计划必须直接包含 execute_skill_script。
            4. execute_skill_script 等工具任务，必须把 skillName、scriptPath、arguments 放进结构化 arguments 字段，不要放进 summary。
            5. 如果 execution hints 明确指出参数不足，才允许向用户补问缺失字段。
            """;

    private static final String CRITIQUE_PROMPT = """
            你是任务批判评估专家。
            请基于完整上下文判断是否已满足用户目标。
            只输出 JSON：
            {
              "passed": true | false,
              "feedback": "如果未通过，给出简洁明确的改进建议"
            }
            """;

    private static final String COMPRESS_PROMPT = """
            你是上下文压缩器。
            目标是在不丢失决策关键信息的前提下，压缩为下一轮继续执行所需的最小状态。

            必须保留：
            1. 用户最终目标。
            2. 已完成的关键任务及其结论。
            3. 工具名称、关键输入和关键结果。
            4. 最近一次 critique 结论。
            5. 当前未解决的问题。

            输出格式：
            【User Goal】
            <目标>

            【Completed Work】
            - Task: ...
              Conclusion: ...

            【Key Tool Results】
            - Tool: ...
              Input: ...
              Result: ...

            【Last Critique】
            <critique>

            【Open Issues】
            <未解决问题>
            """;

    private static final String SUMMARIZE_PROMPT = """
            你是最终答案总结器。
            必须仅基于执行上下文中的真实工具结果回答。
            禁止输出 Execution Plan、Critique、Task Result 等中间过程。
            最终回答要直接面向用户，保持完整、自然、准确。
            """;

    private PlanExecutePrompts() {
    }

    public static String getCurrentTime() {
        return "当前正确的系统时间：" + LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public static String formatPlanPrompt(LocalDateTime now,
                                          int round,
                                          String toolDesc,
                                          String executedTaskHistory,
                                          String outputFormat) {
        return """
                当前时间是：%s。
                当前是迭代的第 %s 轮次。

                %s

                ## 可用工具说明（仅用于规划参考）
                %s

                ## 已执行任务摘要（避免重复规划）
                %s

                ## 输出格式
                %s

                %s
                """.formatted(
                now,
                round,
                PLAN_SKILL_BOUNDARY_RULES,
                toolDesc,
                executedTaskHistory,
                outputFormat,
                PLAN_TASK_RULES
        );
    }

    public static String getCritiquePrompt() {
        return CRITIQUE_PROMPT;
    }

    public static String formatCompressPrompt(int contextCharLimit) {
        return String.format(
                "## 最大压缩限制（必须遵守）%n" +
                        "- 你输出的最终内容【总字符数（包含所有标签、空格、换行）】%n" +
                        "  不得超过：%s%n" +
                        "- 这是硬性上限，不是建议%n" +
                        "- 如超过该限制，视为压缩失败%n%n%s",
                contextCharLimit,
                COMPRESS_PROMPT
        );
    }

    public static String getSummarizePrompt() {
        return SUMMARIZE_PROMPT;
    }

    public static String buildSummarySystemPrompt() {
        return """
                ## 核心规则（必须100%严格遵守）
                1. 你必须完全、仅基于下方【执行上下文】里的工具返回的真实数据生成最终答案，禁止编造任何不在上下文里的日期、天气、数值、景点等信息。
                2. 若上下文出现“用户原始提问参数”与“实际工具执行参数（尤其 HITL 编辑后参数）”不一致，必须以实际工具执行参数为准，并在答案中明确说明“按确认参数计算”。
                3. 禁止输出【Execution Plan】【Critique Feedback】【Task Result】等任何中间执行过程的标签和内容，只输出给用户看的最终答案。
                4. 输出内容必须连贯、完整，符合用户的原始问题要求，禁止重复内容。
                5. 如果上下文里的工具数据有明确的时间，必须以工具返回的时间为准，禁止使用与当前时间不符的虚假日期。

                """ + SUMMARIZE_PROMPT;
    }
}
