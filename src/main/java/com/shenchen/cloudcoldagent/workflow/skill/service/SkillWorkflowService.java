package com.shenchen.cloudcoldagent.workflow.skill.service;

import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowResult;

public interface SkillWorkflowService {

    SkillWorkflowResult preprocess(Long userId, String conversationId, String question);
}
