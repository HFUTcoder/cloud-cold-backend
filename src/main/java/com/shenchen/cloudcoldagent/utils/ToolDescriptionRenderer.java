package com.shenchen.cloudcoldagent.utils;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 将 ToolCallback 列表转化为结构化的能力描述文本，供协调者 Plan 阶段了解子 Agent 能力。
 */
public final class ToolDescriptionRenderer {

    private ToolDescriptionRenderer() {
    }

    /**
     * 生成 Worker 能力参考文本，供注入协调者 plan prompt。
     * <p>
     * 注意：这里只描述 Worker 能做什么，<b>不包含 tool name</b>，
     * 避免协调者模型误将 Worker 的工具当作自己的工具来规划。
     *
     * @param workerTools Worker 持有的工具列表
     * @return Worker 能力描述文本
     */
    public static String renderWorkerCapabilities(List<ToolCallback> workerTools) {
        if (workerTools == null || workerTools.isEmpty()) {
            return "（Worker 当前无可用工具）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Worker 可以自主完成以下类型的工作，你在 taskDescription 中指派即可：\n");
        for (ToolCallback tool : workerTools) {
            sb.append("- ").append(tool.getToolDefinition().description()).append("\n");
        }
        return sb.toString();
    }
}
