package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.agent.SimpleReactAgent;
import com.shenchen.cloudcoldagent.constant.AgentModeConstant;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.dto.agent.AgentCallRequest;
import com.shenchen.cloudcoldagent.service.AgentService;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import com.shenchen.cloudcoldagent.tool.SearchService;
import com.shenchen.cloudcoldagent.tool.WeatherService;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 代理服务层实现
 *
 */
@Service
public class AgentServiceImpl implements AgentService {

    @Autowired
    private ChatModel openAiChatModel;

    @Autowired
    private ChatMemoryRepository chatMemoryRepository;

    @Autowired
    private ChatConversationService chatConversationService;

    private ToolCallback[] toolCallbacks;

    private ChatMemory chatMemory;

    private SimpleReactAgent reactAgent;

    private PlanExecuteAgent planExecuteAgent;

    @PostConstruct
    public void init() {
        toolCallbacks = ToolCallbacks.from(
                new WeatherService(),
                new SearchService()
        );
        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
        reactAgent = SimpleReactAgent.builder()
                .name("ReactAgent")
                .chatModel(openAiChatModel)
                .tools(toolCallbacks)
                .chatMemory(chatMemory)
                .maxRounds(5)
                .systemPrompt("你是专业的研究分析助手！")
                .build();
        planExecuteAgent = PlanExecuteAgent.builder()
                .chatModel(openAiChatModel)
                .tools(toolCallbacks)
                .maxRounds(3)
                .maxToolRetries(3)
                .chatMemory(chatMemory)
                .contextCharLimit(5000)
                .build();
    }


    @Override
    public Flux<String> call(AgentCallRequest agentCallRequest, Long userId) {
        String question = agentCallRequest.getQuestion() == null ? "" : agentCallRequest.getQuestion();
        String mode = agentCallRequest.getMode() == null ? AgentModeConstant.FAST : agentCallRequest.getMode();
        String conversationId = resolveConversationId(userId, agentCallRequest.getConversationId());
        chatConversationService.touchConversation(userId, conversationId);
        chatConversationService.generateTitleOnFirstMessage(userId, conversationId, question);

        switch (mode) {
            case AgentModeConstant.FAST:
                return reactAgent.stream(conversationId, question);
            case AgentModeConstant.THINKING:
                return planExecuteAgent.stream(conversationId, question);
            default:
                return reactAgent.stream(conversationId, question);
        }
    }

    private String resolveConversationId(Long userId, String rawConversationId) {
        if (rawConversationId == null || rawConversationId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空，请先创建会话");
        }
        return chatConversationService.normalizeConversationId(userId, rawConversationId);
    }
}
