package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.service.HitlExecutionService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class HitlExecutionServiceImpl implements HitlExecutionService {

    @Override
    public boolean isEnabled(String conversationId, Set<String> interceptToolNames) {
        return conversationId != null
                && !conversationId.isBlank()
                && interceptToolNames != null
                && !interceptToolNames.isEmpty();
    }
}
