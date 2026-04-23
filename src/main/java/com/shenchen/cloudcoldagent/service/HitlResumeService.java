package com.shenchen.cloudcoldagent.service;

import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

public interface HitlResumeService {

    HitlResumeResult resume(HitlResumeRequest request);

    record HitlResumeRequest(
            String interruptId,
            ChatModel chatModel,
            List<ToolCallback> tools,
            List<Advisor> advisors,
            int maxRounds,
            Set<String> interceptToolNames,
            Set<String> approvedToolNames
    ) {
    }

    record HitlResumeResult(
            boolean interrupted,
            String content,
            String error,
            HitlCheckpointVO checkpoint
    ) {
    }
}
