package com.shenchen.cloudcoldagent.skillworkflow;

import com.shenchen.cloudcoldagent.skillworkflow.state.SkillWorkflowResult;

public interface SkillWorkflowService {

    SkillWorkflowResult preprocess(Long userId, String conversationId, String question);
}
