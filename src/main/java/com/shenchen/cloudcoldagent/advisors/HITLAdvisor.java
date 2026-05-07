package com.shenchen.cloudcoldagent.advisors;

import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * `HITLAdvisor` 类型实现。
 */
public class HITLAdvisor implements CallAdvisor {

    public static final String HITL_REQUIRED = "hitl.required";
    public static final String HITL_PENDING_TOOLS = "hitl.pending.tools";
    public static final String HITL_STATE_KEY = "hitl.state";
    public static final String HITL_NON_INTERCEPT_TOOLS = "hitl.non.intercept.tools";

    private final Set<String> interceptToolNames;
    private final Set<String> approvedToolCallIds;

    /**
     * 创建 `HITLAdvisor` 实例。
     *
     * @param interceptToolNames interceptToolNames 参数。
     */
    public HITLAdvisor(Set<String> interceptToolNames) {
        this(interceptToolNames, Set.of());
    }

    /**
     * 创建 `HITLAdvisor` 实例。
     *
     * @param interceptToolNames interceptToolNames 参数。
     * @param approvedToolCallIds approvedToolCallIds 参数。
     */
    public HITLAdvisor(Set<String> interceptToolNames, Set<String> approvedToolCallIds) {
        this.interceptToolNames = interceptToolNames;
        this.approvedToolCallIds = approvedToolCallIds == null ? Set.of() : approvedToolCallIds;
    }

    /**
     * 处理 `advise Call` 对应逻辑。
     *
     * @param chatClientRequest chatClientRequest 参数。
     * @param callAdvisorChain callAdvisorChain 参数。
     * @return 返回处理结果。
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);
        if (!response.chatResponse().hasToolCalls()) {
            return response;
        }

        List<PendingToolCall> pending = new ArrayList<>();
        List<AssistantMessage.ToolCall> nonInterceptTools = new ArrayList<>();

        for (AssistantMessage.ToolCall tc : response.chatResponse().getResult().getOutput().getToolCalls()) {

            if (!interceptToolNames.contains(tc.name())) {
                nonInterceptTools.add(tc);
                continue;
            }
            if (approvedToolCallIds.contains(tc.id())) {
                nonInterceptTools.add(tc);
                continue;
            }

            pending.add(new PendingToolCall(tc.id(), tc.name(), tc.arguments(), null, "该工具需要用户手动确认"));
        }

        if (pending.isEmpty()) {
            return response;
        }

        response.context().put(HITL_REQUIRED, true);
        response.context().put(HITL_PENDING_TOOLS, pending);
        // 传递非拦截工具，立即执行
        if (!nonInterceptTools.isEmpty()) {
            response.context().put(HITL_NON_INTERCEPT_TOOLS, nonInterceptTools);
        }

        return response;
    }

    /**
     * 获取 `get Name` 对应结果。
     *
     * @return 返回处理结果。
     */
    @Override
    public String getName() {
        return "HITLAdvisor";
    }

    /**
     * 获取 `get Order` 对应结果。
     *
     * @return 返回处理结果。
     */
    @Override
    public int getOrder() {
        return 0;
    }
}
