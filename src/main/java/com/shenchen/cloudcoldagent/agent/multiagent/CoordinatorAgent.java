package com.shenchen.cloudcoldagent.agent.multiagent;

import com.shenchen.cloudcoldagent.agent.BaseAgent;
import com.shenchen.cloudcoldagent.agent.PlanExecuteAgent;
import com.shenchen.cloudcoldagent.model.entity.record.agent.planexecute.PlanExecuteCallResult;
import com.shenchen.cloudcoldagent.model.vo.agent.AgentStreamEvent;
import com.shenchen.cloudcoldagent.prompts.multiagent.CoordinatorPrompts;
import com.shenchen.cloudcoldagent.service.hitl.HitlCheckpointService;
import com.shenchen.cloudcoldagent.service.hitl.HitlExecutionService;
import com.shenchen.cloudcoldagent.service.hitl.HitlResumeService;
import com.shenchen.cloudcoldagent.service.skill.SkillService;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillRuntimeContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 专家模式 Agent，内部持有 PlanExecuteAgent 作为 delegate。
 * <p>
 * 使用 {@link CoordinatorPrompts} 作为 PromptProvider，实现任务分解、Worker 派发、评估和合成。
 */
@Slf4j
public class CoordinatorAgent extends BaseAgent {

    private final PlanExecuteAgent delegate;

    public CoordinatorAgent(String name,
                            ChatModel chatModel,
                            List<ToolCallback> tools,
                            List<Advisor> advisors,
                            String systemPrompt,
                            int maxRounds,
                            int contextCharLimit,
                            int toolConcurrency,
                            ChatMemory chatMemory,
                            HitlExecutionService hitlExecutionService,
                            HitlCheckpointService hitlCheckpointService,
                            HitlResumeService hitlResumeService,
                            SkillService skillService,
                            Executor toolExecutor,
                            Executor virtualThreadExecutor) {
        super(name, chatModel, tools,
                advisors != null ? advisors : List.of(),
                buildCombinedSystemPrompt(systemPrompt),
                maxRounds, chatMemory, toolConcurrency, toolExecutor, virtualThreadExecutor);
        this.delegate = PlanExecuteAgent.builder(
                        chatMemory, hitlExecutionService, hitlCheckpointService, hitlResumeService,
                        skillService, toolExecutor, virtualThreadExecutor)
                .name(name)
                .chatModel(chatModel)
                .tools(tools)
                .advisors(advisors)
                .systemPrompt(buildCombinedSystemPrompt(systemPrompt))
                .promptProvider(CoordinatorPrompts.createPromptProvider())
                .extensionTools(tools)
                .maxRounds(maxRounds)
                .contextCharLimit(contextCharLimit)
                .toolConcurrency(toolConcurrency)
                .agentType("CoordinatorAgent")
                .build();
    }

    /**
     * 将固定的协调者角色提示词与业务补充的 system prompt 合并。
     */
    private static String buildCombinedSystemPrompt(String businessSystemPrompt) {
        StringBuilder sb = new StringBuilder();
        sb.append(CoordinatorPrompts.COORDINATOR_SYSTEM_PROMPT);
        if (StringUtils.isNotBlank(businessSystemPrompt)) {
            sb.append("\n\n").append(businessSystemPrompt.trim());
        }
        return sb.toString();
    }

    public static Builder builder(HitlExecutionService hitlExecutionService,
                                  HitlCheckpointService hitlCheckpointService,
                                  HitlResumeService hitlResumeService,
                                  SkillService skillService,
                                  Executor toolExecutor,
                                  Executor virtualThreadExecutor) {
        return new Builder(hitlExecutionService, hitlCheckpointService, hitlResumeService,
                skillService, toolExecutor, virtualThreadExecutor);
    }

    /**
     * 流式执行协调任务（便捷方法，无 userId/conversationId）。
     */
    public Flux<AgentStreamEvent> stream(String question) {
        return delegate.stream(question);
    }

    /**
     * 流式执行协调任务。
     */
    public Flux<AgentStreamEvent> stream(Long userId, String conversationId, String question,
                                         String runtimeSystemPrompt, String memoryQuestion,
                                         List<SkillRuntimeContext> skillRuntimeContexts) {
        return delegate.stream(userId, conversationId, question, runtimeSystemPrompt,
                memoryQuestion, skillRuntimeContexts);
    }

    /**
     * 恢复被 HITL 中断的协调任务。
     */
    public Flux<AgentStreamEvent> resume(String interruptId, Long userId) {
        return delegate.resume(interruptId, userId);
    }

    /**
     * 同步执行协调任务。
     */
    public PlanExecuteCallResult call(String question) {
        return delegate.call(question);
    }

    public static class Builder {
        private final HitlExecutionService hitlExecutionService;
        private final HitlCheckpointService hitlCheckpointService;
        private final HitlResumeService hitlResumeService;
        private final SkillService skillService;
        private final Executor toolExecutor;
        private final Executor virtualThreadExecutor;
        private String name;
        private ChatModel chatModel;
        private List<ToolCallback> tools = new ArrayList<>();
        private List<Advisor> advisors = new ArrayList<>();
        private String systemPrompt;
        private int maxRounds = 5;
        private int contextCharLimit = 10000;
        private int toolConcurrency = 3;
        private ChatMemory chatMemory;

        private Builder(HitlExecutionService hitlExecutionService,
                        HitlCheckpointService hitlCheckpointService,
                        HitlResumeService hitlResumeService,
                        SkillService skillService,
                        Executor toolExecutor,
                        Executor virtualThreadExecutor) {
            this.hitlExecutionService = hitlExecutionService;
            this.hitlCheckpointService = hitlCheckpointService;
            this.hitlResumeService = hitlResumeService;
            this.skillService = skillService;
            this.toolExecutor = toolExecutor;
            this.virtualThreadExecutor = virtualThreadExecutor;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools;
            return this;
        }

        public Builder tools(ToolCallback... tools) {
            this.tools = tools == null ? new ArrayList<>() : new ArrayList<>(List.of(tools));
            return this;
        }

        public Builder advisors(List<Advisor> advisors) {
            this.advisors = advisors == null ? new ArrayList<>() : new ArrayList<>(advisors);
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public Builder contextCharLimit(int contextCharLimit) {
            this.contextCharLimit = contextCharLimit;
            return this;
        }

        public Builder toolConcurrency(int toolConcurrency) {
            this.toolConcurrency = toolConcurrency;
            return this;
        }

        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;
        }

        public CoordinatorAgent build() {
            Objects.requireNonNull(chatModel, "chatModel must not be null");
            Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
            Objects.requireNonNull(virtualThreadExecutor, "virtualThreadExecutor must not be null");
            return new CoordinatorAgent(name, chatModel, tools, advisors, systemPrompt,
                    maxRounds, contextCharLimit, toolConcurrency, chatMemory,
                    hitlExecutionService, hitlCheckpointService,
                    hitlResumeService, skillService, toolExecutor, virtualThreadExecutor);
        }
    }
}
