package com.shenchen.cloudcoldagent.service.usermemory.impl;

import com.shenchen.cloudcoldagent.config.properties.LongTermMemoryProperties;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryPreprocessResult;
import com.shenchen.cloudcoldagent.prompts.UserLongTermMemoryPrompts;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryPreprocessService;
import com.shenchen.cloudcoldagent.service.usermemory.UserLongTermMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆预处理服务实现，负责在进入 Agent 前召回相关记忆并生成运行时提示词。
 */
@Service
@Slf4j
public class UserLongTermMemoryPreprocessServiceImpl implements UserLongTermMemoryPreprocessService {

    private final UserLongTermMemoryService userLongTermMemoryService;
    private final LongTermMemoryProperties properties;

    /**
     * 注入长期记忆预处理所需的依赖服务。
     *
     * @param userLongTermMemoryService 长期记忆业务服务。
     * @param properties 长期记忆配置。
     */
    public UserLongTermMemoryPreprocessServiceImpl(UserLongTermMemoryService userLongTermMemoryService,
                                                   LongTermMemoryProperties properties) {
        this.userLongTermMemoryService = userLongTermMemoryService;
        this.properties = properties;
    }

    /**
     * 在进入 Agent 前召回与当前问题相关的长期记忆，并按配置生成 runtime prompt。
     *
     * @param userId 当前用户 id。
     * @param question 用户问题。
     * @return 长期记忆预处理结果。
     */
    @Override
    public UserLongTermMemoryPreprocessResult preprocess(Long userId, String question) {
        if (!properties.isEnabled() || userId == null || userId <= 0 || question == null || question.isBlank()) {
            return new UserLongTermMemoryPreprocessResult(List.of(), null, false);
        }
        try {
            List<UserLongTermMemoryDoc> memories = userLongTermMemoryService.retrieveRelevantMemories(
                    userId,
                    question,
                    properties.getRetrieveTopK()
            );
            if (memories.isEmpty()) {
                return new UserLongTermMemoryPreprocessResult(List.of(), null, true);
            }
            String runtimePrompt = UserLongTermMemoryPrompts.buildRuntimePrompt(
                    memories,
                    properties.getMaxPromptMemories()
            );
            if (runtimePrompt == null || runtimePrompt.isBlank()) {
                return new UserLongTermMemoryPreprocessResult(memories, null, true);
            }
            return new UserLongTermMemoryPreprocessResult(memories, runtimePrompt, true);
        } catch (Exception e) {
            log.warn("长期记忆预处理失败，userId={}, message={}", userId, e.getMessage(), e);
            return new UserLongTermMemoryPreprocessResult(List.of(), null, true);
        }
    }
}
