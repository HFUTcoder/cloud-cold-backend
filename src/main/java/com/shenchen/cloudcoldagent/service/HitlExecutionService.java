package com.shenchen.cloudcoldagent.service;

import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlExecutionRequest;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlExecutionResult;

import java.util.Set;

public interface HitlExecutionService {

    boolean isEnabled(String conversationId, Set<String> interceptToolNames);

    HitlExecutionResult execute(HitlExecutionRequest request);
}
