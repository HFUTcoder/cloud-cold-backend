package com.shenchen.cloudcoldagent.workflow.skill.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.model.vo.SkillResourceListVO;
import com.shenchen.cloudcoldagent.prompts.SkillWorkflowPrompts;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillCandidate;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillArgumentSpec;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillExecutionPlan;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillExecutionPlanListResult;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillScriptExecutionRequest;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillToolCallPlan;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import com.shenchen.cloudcoldagent.workflow.skill.service.StructuredOutputAgentExecutor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class BuildSkillExecutionPlansNode {

    private final StructuredOutputAgentExecutor structuredOutputAgentExecutor;

    public BuildSkillExecutionPlansNode(StructuredOutputAgentExecutor structuredOutputAgentExecutor) {
        this.structuredOutputAgentExecutor = structuredOutputAgentExecutor;
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        String question = state.value(SkillWorkflowStateKeys.USER_QUESTION, String.class).orElse("");
        List<Message> conversationHistory =
                (List<Message>) state.value(SkillWorkflowStateKeys.CONVERSATION_HISTORY).orElse(List.of());
        List<SkillCandidate> candidates =
                (List<SkillCandidate>) state.value(SkillWorkflowStateKeys.CANDIDATE_SKILLS).orElse(List.of());
        Map<String, String> skillContents =
                (Map<String, String>) state.value(SkillWorkflowStateKeys.SKILL_CONTENTS).orElse(Map.of());
        Map<String, SkillResourceListVO> skillResources =
                (Map<String, SkillResourceListVO>) state.value(SkillWorkflowStateKeys.SKILL_RESOURCES).orElse(Map.of());

        Map<String, SkillCandidate> candidateMap = candidates.stream()
                .collect(Collectors.toMap(SkillCandidate::getSkillName, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        if (skillContents.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of(SkillWorkflowStateKeys.EXECUTION_PLANS, List.of()));
        }

        List<Map<String, Object>> batchSkillInputs = new ArrayList<>();
        for (Map.Entry<String, String> entry : skillContents.entrySet()) {
            String skillName = entry.getKey();
            SkillCandidate candidate = candidateMap.get(skillName);
            SkillResourceListVO resourceList = skillResources.get(skillName);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skillName", skillName);
            item.put("candidate", candidate == null ? Map.of() : candidate);
            item.put("resourceList", resourceList == null ? Map.of() : resourceList);
            item.put("skillContent", entry.getValue());
            batchSkillInputs.add(item);
        }

        SkillExecutionPlanListResult result = structuredOutputAgentExecutor.execute(List.of(
                new SystemMessage(SkillWorkflowPrompts.buildExecutionPlanPrompt()),
                new UserMessage(SkillWorkflowPrompts.buildExecutionPlanInput(
                        question,
                        SkillWorkflowPrompts.renderConversationHistory(conversationHistory),
                        SkillWorkflowPrompts.buildExecutionPlanBatchInput(batchSkillInputs)
                ))
        ), SkillExecutionPlanListResult.class);

        List<SkillExecutionPlan> rawPlans = result == null || result.getItems() == null ? List.of() : result.getItems();
        List<SkillExecutionPlan> plans = new ArrayList<>();
        for (SkillExecutionPlan plan : rawPlans) {
            if (plan == null || plan.getSkillName() == null || plan.getSkillName().isBlank()) {
                continue;
            }
            normalizeToolRequest(plan, plan.getSkillName());
            normalizeBlockingMessage(plan);
            plans.add(plan);
        }

        return CompletableFuture.completedFuture(Map.of(SkillWorkflowStateKeys.EXECUTION_PLANS, plans));
    }

    private void normalizeToolRequest(SkillExecutionPlan plan, String skillName) {
        if (plan == null || plan.getToolCallPlan() == null) {
            return;
        }
        SkillToolCallPlan toolCallPlan = plan.getToolCallPlan();
        SkillScriptExecutionRequest request = toolCallPlan.getRequest();
        if (request == null) {
            return;
        }
        if (skillName != null && !skillName.isBlank()) {
            request.setSkillName(skillName);
        }
        normalizeArgumentSpecs(request.getArgumentSpecs());
    }

    private void normalizeBlockingMessage(SkillExecutionPlan plan) {
        if (plan == null) {
            return;
        }
        if (plan.getBlockingReason() != null) {
            plan.setBlockingReason(plan.getBlockingReason().trim());
        }
        if (plan.getBlockingUserMessage() != null) {
            String normalizedMessage = plan.getBlockingUserMessage().trim();
            plan.setBlockingUserMessage(normalizedMessage.isEmpty() ? null : normalizedMessage);
        }
    }

    private void normalizeArgumentSpecs(Map<String, SkillArgumentSpec> argumentSpecs) {
        if (argumentSpecs == null || argumentSpecs.isEmpty()) {
            return;
        }
        argumentSpecs.forEach((argumentName, spec) -> {
            if (spec == null) {
                return;
            }
            if (spec.getName() == null || spec.getName().isBlank()) {
                spec.setName(argumentName);
            }
            if (spec.getRequired() == null && spec.getOptional() != null) {
                spec.setRequired(!Boolean.TRUE.equals(spec.getOptional()));
            }
        });
    }
}
