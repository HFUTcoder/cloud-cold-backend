package com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute;

/**
 * 批判评估结果。
 *
 * @param passed 目标是否已满足
 * @param feedback 评估说明
 * @param action 下一步行动: SUMMARIZE(生成最终回答) / CONTINUE(继续执行工具) / ASK_USER(需要用户提供信息)
 */
public record CritiqueResult(boolean passed, String feedback, String action) {

    /** 旧版 critique prompt 可能不输出 action 字段，默认 CONTINUE 保持兼容。 */
    public static final String ACTION_SUMMARIZE = "SUMMARIZE";
    public static final String ACTION_CONTINUE = "CONTINUE";
    public static final String ACTION_ASK_USER = "ASK_USER";

    public CritiqueResult {
        if (action == null || action.isBlank()) {
            action = ACTION_CONTINUE;
        }
    }
}
