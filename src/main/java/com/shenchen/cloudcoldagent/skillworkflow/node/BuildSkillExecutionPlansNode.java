package com.shenchen.cloudcoldagent.skillworkflow.node;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.model.vo.SkillResourceListVO;
import com.shenchen.cloudcoldagent.prompts.SkillWorkflowPrompts;
import com.shenchen.cloudcoldagent.skillworkflow.state.SkillCandidate;
import com.shenchen.cloudcoldagent.skillworkflow.state.SkillArgumentSpec;
import com.shenchen.cloudcoldagent.skillworkflow.state.SkillExecutionPlan;
import com.shenchen.cloudcoldagent.skillworkflow.state.SkillScriptExecutionRequest;
import com.shenchen.cloudcoldagent.skillworkflow.state.SkillToolCallPlan;
import com.shenchen.cloudcoldagent.skillworkflow.state.SkillWorkflowStateKeys;
import com.shenchen.cloudcoldagent.skillworkflow.support.StructuredOutputAgentExecutor;
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
        List<SkillCandidate> candidates =
                (List<SkillCandidate>) state.value(SkillWorkflowStateKeys.CANDIDATE_SKILLS).orElse(List.of());
        Map<String, String> skillContents =
                (Map<String, String>) state.value(SkillWorkflowStateKeys.SKILL_CONTENTS).orElse(Map.of());
        Map<String, SkillResourceListVO> skillResources =
                (Map<String, SkillResourceListVO>) state.value(SkillWorkflowStateKeys.SKILL_RESOURCES).orElse(Map.of());

        Map<String, SkillCandidate> candidateMap = candidates.stream()
                .collect(Collectors.toMap(SkillCandidate::getSkillName, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        List<SkillExecutionPlan> plans = new ArrayList<>();
        for (Map.Entry<String, String> entry : skillContents.entrySet()) {
            String skillName = entry.getKey();
            String content = entry.getValue();
            SkillResourceListVO resourceList = skillResources.get(skillName);
            SkillCandidate candidate = candidateMap.get(skillName);
            SkillExecutionPlan plan = structuredOutputAgentExecutor.execute(List.of(
                    new SystemMessage(SkillWorkflowPrompts.buildExecutionPlanPrompt()),
                    new UserMessage(SkillWorkflowPrompts.buildExecutionPlanInput(
                            question,
                            candidate,
                            content,
                            resourceList == null ? "{}" : JSONUtil.toJsonStr(resourceList)
                    ))
            ), SkillExecutionPlan.class);
            if (plan == null) {
                continue;
            }
            plan.setSkillName(skillName);
            if (plan.getSource() == null && candidate != null) {
                plan.setSource(candidate.getSource());
            }
            if (plan.getReason() == null && candidate != null) {
                plan.setReason(candidate.getReason());
            }
            normalizeToolRequest(plan, skillName);
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
        if (request.getSkillName() == null || request.getSkillName().isBlank()) {
            request.setSkillName(skillName);
        }
        normalizeArgumentSpecs(request.getArgumentSpecs());
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
