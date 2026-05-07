package com.shenchen.cloudcoldagent.workflow.skill.service;

import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowResult;

/**
 * Skill 工作流服务，负责在进入 Agent 主链路前完成 skill 预处理。
 */
public interface SkillWorkflowService {

    /**
     * 执行 skill 工作流预处理，输出本轮对话命中的 skill 与运行时上下文。
     *
     * @param userId 当前用户 id。
     * @param conversationId 当前会话 id。
     * @param question 用户原始问题。
     * @return skill 工作流预处理结果。
     */
    SkillWorkflowResult preprocess(Long userId, String conversationId, String question);
}
