package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.agent.SimpleReactAgent;
import com.shenchen.cloudcoldagent.advisors.SkillAdvisor;
import com.shenchen.cloudcoldagent.constant.AgentModeConstant;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.model.dto.agent.AgentCallRequest;
import com.shenchen.cloudcoldagent.model.entity.ChatConversation;
import com.shenchen.cloudcoldagent.service.AgentService;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 代理服务层实现
 *
 */
@Service
@Slf4j
public class AgentServiceImpl implements AgentService {

    @Autowired
    private ChatModel openAiChatModel;

    @Autowired
    private ChatMemoryRepository chatMemoryRepository;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private ObjectProvider<Advisor> advisorProvider;

    @Autowired
    @Qualifier("allTools")
    private ToolCallback[] allToolCallbacks;

    @Autowired
    @Qualifier("commonTools")
    private ToolCallback[] commonToolCallbacks;

    private List<Advisor> allAdvisors;

    private List<Advisor> commonAdvisors;

    private ChatMemory chatMemory;

    private SimpleReactAgent reactAgent;

    private SimpleReactAgent commonReactAgent;

    private PlanExecuteAgent planExecuteAgent;

    private PlanExecuteAgent commonPlanExecuteAgent;

    @PostConstruct
    public void init() {
        allAdvisors = advisorProvider.orderedStream().toList();
        commonAdvisors = allAdvisors.stream()
                .filter(advisor -> !(advisor instanceof SkillAdvisor))
                .toList();
        chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
        reactAgent = SimpleReactAgent.builder()
                .name("ReactAgent")
                .chatModel(openAiChatModel)
                .tools(allToolCallbacks)
                .advisors(allAdvisors)
                .chatMemory(chatMemory)
                .maxRounds(5)
                .systemPrompt("你是专业的研究分析助手！")
                .build();
        commonReactAgent = SimpleReactAgent.builder()
                .name("CommonReactAgent")
                .chatModel(openAiChatModel)
                .tools(commonToolCallbacks)
                .advisors(commonAdvisors)
                .chatMemory(chatMemory)
                .maxRounds(5)
                .systemPrompt("你是专业的研究分析助手！")
                .build();
        planExecuteAgent = PlanExecuteAgent.builder()
                .chatModel(openAiChatModel)
                .tools(allToolCallbacks)
                .advisors(allAdvisors)
                .maxRounds(3)
                .maxToolRetries(3)
                .chatMemory(chatMemory)
                .contextCharLimit(5000)
                .build();
        commonPlanExecuteAgent = PlanExecuteAgent.builder()
                .chatModel(openAiChatModel)
                .tools(commonToolCallbacks)
                .advisors(commonAdvisors)
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
        String conversationSkillPrompt = chatConversationService.buildConversationSkillPrompt(userId, conversationId);
        String effectiveQuestion = buildEffectiveQuestion(userId, conversationId, question);
        boolean enableSkillTools = shouldEnableSkillTools(question, conversationSkillPrompt);

        switch (mode) {
            case AgentModeConstant.FAST:
                return enableSkillTools
                        ? reactAgent.stream(conversationId, effectiveQuestion, conversationSkillPrompt)
                        : commonReactAgent.stream(conversationId, effectiveQuestion, null);
            case AgentModeConstant.THINKING:
                return enableSkillTools
                        ? planExecuteAgent.stream(conversationId, effectiveQuestion, conversationSkillPrompt)
                        : commonPlanExecuteAgent.stream(conversationId, effectiveQuestion, null);
            default:
                return enableSkillTools
                        ? reactAgent.stream(conversationId, effectiveQuestion, conversationSkillPrompt)
                        : commonReactAgent.stream(conversationId, effectiveQuestion, null);
        }
    }

    private String resolveConversationId(Long userId, String rawConversationId) {
        if (rawConversationId == null || rawConversationId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空，请先创建会话");
        }
        return chatConversationService.normalizeConversationId(userId, rawConversationId);
    }

    private boolean shouldEnableSkillTools(String question, String conversationSkillPrompt) {
        return StringUtils.hasText(conversationSkillPrompt);
    }

    private String buildEffectiveQuestion(Long userId, String conversationId, String question) {
        ChatConversation conversation = chatConversationService.getByConversationId(userId, conversationId);
        List<String> selectedSkillList = conversation.getSelectedSkillList();
        if (selectedSkillList == null || selectedSkillList.isEmpty()) {
            return question;
        }

        String skillLines = selectedSkillList.stream()
                .map(skill -> "- " + skill)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        String effectiveQuestion = """
                [会话绑定技能上下文]
                当前会话已绑定以下 skills：
                %s

                下面是用户本轮真实问题。请在理解和回答时优先结合上述已绑定 skills，但不要把这段上下文当作用户原话复述给用户。
                如果本轮问题需要依赖这些已绑定 skill 的正文或资源内容，而你当前还没有读到对应内容，请先调用相关 skill 工具读取，再回答。
                不要仅仅因为当前上下文尚未展开这些 skill 内容，就直接回答“信息不足”或“缺少上下文”。

                [用户问题]
                %s
                """.formatted(skillLines, question);
        log.info("拼接会话级 skill 用户提示词，userId={}, conversationId={}, selectedSkills={}, rawQuestion={}, effectiveQuestion=\n{}",
                userId,
                conversationId,
                selectedSkillList,
                question,
                effectiveQuestion);
        return effectiveQuestion;
    }
}
