package com.shenchen.cloudcoldagent.workflow.skill.service;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowResult;

public interface SkillWorkflowService {

    SkillWorkflowResult preprocess(Long userId, String conversationId, String question);

    <T> T executeStructuredOutput(List<Message> messages, Class<T> outputType);
}
