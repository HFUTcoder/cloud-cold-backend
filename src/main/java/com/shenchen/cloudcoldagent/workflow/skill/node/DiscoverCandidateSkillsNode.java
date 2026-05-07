package com.shenchen.cloudcoldagent.workflow.skill.node;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.model.vo.SkillMetadataVO;
import com.shenchen.cloudcoldagent.prompts.SkillWorkflowPrompts;
import com.shenchen.cloudcoldagent.service.SkillService;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillCandidate;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillCandidateListResult;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import com.shenchen.cloudcoldagent.workflow.skill.service.StructuredOutputAgentExecutor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * `DiscoverCandidateSkillsNode` 类型实现。
 */
@Component
public class DiscoverCandidateSkillsNode {

    private final SkillService skillService;
    private final StructuredOutputAgentExecutor structuredOutputAgentExecutor;

    /**
     * 创建 `DiscoverCandidateSkillsNode` 实例。
     *
     * @param skillService skillService 参数。
     * @param structuredOutputAgentExecutor structuredOutputAgentExecutor 参数。
     */
    public DiscoverCandidateSkillsNode(SkillService skillService,
                                       StructuredOutputAgentExecutor structuredOutputAgentExecutor) {
        this.skillService = skillService;
        this.structuredOutputAgentExecutor = structuredOutputAgentExecutor;
    }

    /**
     * 处理 `apply` 对应逻辑。
     *
     * @param state state 参数。
     * @return 返回处理结果。
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        String question = state.value(SkillWorkflowStateKeys.USER_QUESTION, String.class).orElse("");
        List<Message> conversationHistory =
                (List<Message>) state.value(SkillWorkflowStateKeys.CONVERSATION_HISTORY).orElse(List.of());
        List<String> boundSkills = (List<String>) state.value(SkillWorkflowStateKeys.BOUND_SKILLS).orElse(List.of());
        List<SkillCandidate> currentCandidates =
                (List<SkillCandidate>) state.value(SkillWorkflowStateKeys.CANDIDATE_SKILLS).orElse(List.of());

        Set<String> existingSkillNames = new LinkedHashSet<>(boundSkills);
        currentCandidates.stream().map(SkillCandidate::getSkillName).forEach(existingSkillNames::add);

        List<SkillMetadataVO> unboundMetadatas = skillService.listSkillMetadata().stream()
                .filter(metadata -> metadata != null && !existingSkillNames.contains(metadata.getName()))
                .toList();

        List<SkillCandidate> mergedCandidates = new ArrayList<>(currentCandidates);
        if (!unboundMetadatas.isEmpty()) {
            SkillCandidateListResult result = structuredOutputAgentExecutor.execute(List.of(
                    new SystemMessage(SkillWorkflowPrompts.buildUnboundSkillDiscoveryPrompt()),
                    new UserMessage(SkillWorkflowPrompts.buildUnboundSkillDiscoveryInput(
                            question,
                            SkillWorkflowPrompts.renderConversationHistory(conversationHistory),
                            JSONUtil.toJsonStr(unboundMetadatas)
                    ))
            ), SkillCandidateListResult.class);
            List<SkillCandidate> discoveredCandidates = result == null ? List.of() : result.getItems();
            if (discoveredCandidates != null) {
                discoveredCandidates.stream()
                        .filter(candidate -> candidate != null && candidate.getSkillName() != null)
                        .map(candidate -> SkillCandidate.builder()
                                .skillName(candidate.getSkillName())
                                .relevant(Boolean.TRUE.equals(candidate.getRelevant()))
                                .build())
                        .forEach(mergedCandidates::add);
            }
        }

        List<String> selectedSkills = mergedCandidates.stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getRelevant()))
                .map(SkillCandidate::getSkillName)
                .distinct()
                .toList();

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(SkillWorkflowStateKeys.CANDIDATE_SKILLS, mergedCandidates);
        updates.put(SkillWorkflowStateKeys.SELECTED_SKILLS, selectedSkills);
        return CompletableFuture.completedFuture(updates);
    }
}
