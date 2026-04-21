package com.shenchen.cloudcoldagent.advisors;

import com.alibaba.cloud.ai.graph.advisors.SkillPromptAugmentAdvisor;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

@Slf4j
@Component
@ConditionalOnBean(SkillRegistry.class)
public class SkillAdvisor implements BaseAdvisor {

    private static final String CONSERVATIVE_SKILL_POLICY = String.join("\n",
            "默认情况下，请对 skill 工具保持保守：",
            "1. 如果当前问题没有明确提到某个 skill、read_skill、read_skill_resource、SKILL.md、reference、script 等词，不要为了“试一试”而主动调用 skill 工具。",
            "2. 只有在以下情况之一满足时，才主动使用 skill：",
            "   - 用户明确点名某个 skill 或明确要求读取 skill / skill 资源",
            "   - 当前会话的系统提示词已经明确绑定了某些 skill",
            "   - 任务明显需要某个 skill 提供的专门流程，而不是普通问答、普通搜索或通用推理就能完成",
            "3. 如果没有足够证据证明 skill 相关，就优先按普通问答流程处理，不要猜测 skill 名称，不要随意尝试 read_skill。"
    );

    private final SkillPromptAugmentAdvisor delegate;

    public SkillAdvisor(SkillRegistry skillRegistry) {
        this.delegate = SkillPromptAugmentAdvisor.builder()
                .skillRegistry(skillRegistry)
                .lazyLoad(true)
                .build();
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        ChatClientRequest conservativeRequest = request.mutate()
                .prompt(request.prompt().augmentSystemMessage(CONSERVATIVE_SKILL_POLICY))
                .build();
        return delegate.before(conservativeRequest, chain);
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return delegate.after(response, chain);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return delegate.adviseCall(request, chain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return delegate.adviseStream(request, chain);
    }

    @Override
    public String getName() {
        return "SkillAdvisor";
    }

    @Override
    public int getOrder() {
        return delegate.getOrder();
    }

    @Override
    public Scheduler getScheduler() {
        return delegate.getScheduler();
    }
}
