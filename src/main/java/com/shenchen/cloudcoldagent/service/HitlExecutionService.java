package com.shenchen.cloudcoldagent.service;

import java.util.Set;

public interface HitlExecutionService {

    boolean isEnabled(String conversationId, Set<String> interceptToolNames);
}
