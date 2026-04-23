package com.shenchen.cloudcoldagent.workflow.skill.node;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.model.vo.SkillMetadataVO;
import com.shenchen.cloudcoldagent.workflow.skill.service.SkillWorkflowService;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillCandidate;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillCandidateListResult;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import com.shenchen.cloudcoldagent.prompts.SkillWorkflowPrompts;
import com.shenchen.cloudcoldagent.service.SkillService;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class RecognizeBoundSkillsNode {

    private final SkillService skillService;
    private final SkillWorkflowService skillWorkflowService;

    public RecognizeBoundSkillsNode(SkillService skillService,
                                    SkillWorkflowService skillWorkflowService) {
        this.skillService = skillService;
        this.skillWorkflowService = skillWorkflowService;
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        String question = state.value(SkillWorkflowStateKeys.USER_QUESTION, String.class).orElse("");
        List<String> boundSkills = (List<String>) state.value(SkillWorkflowStateKeys.BOUND_SKILLS).orElse(List.of());
        if (boundSkills.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of(SkillWorkflowStateKeys.CANDIDATE_SKILLS, List.of()));
        }

        List<SkillMetadataVO> metadataList = boundSkills.stream()
                .map(skillService::getSkillMetadata)
                .toList();

        SkillCandidateListResult result = skillWorkflowService.executeStructuredOutput(List.of(
                new SystemMessage(SkillWorkflowPrompts.buildBoundSkillRecognitionPrompt()),
                new UserMessage(SkillWorkflowPrompts.buildBoundSkillRecognitionInput(question, JSONUtil.toJsonStr(metadataList)))
        ), SkillCandidateListResult.class);
        List<SkillCandidate> candidates = result == null ? List.of() : result.getItems();
        List<SkillCandidate> normalizedCandidates = candidates == null ? List.of() : candidates.stream()
                .filter(candidate -> candidate != null && candidate.getSkillName() != null)
                .map(candidate -> SkillCandidate.builder()
                        .skillName(candidate.getSkillName())
                        .relevant(Boolean.TRUE.equals(candidate.getRelevant()))
                        .build())
                .toList();

        return CompletableFuture.completedFuture(new LinkedHashMap<>(Map.of(
                SkillWorkflowStateKeys.CANDIDATE_SKILLS, normalizedCandidates
        )));
    }
}
