package com.shenchen.cloudcoldagent.workflow.skill.node;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.shenchen.cloudcoldagent.model.vo.SkillMetadataVO;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillCandidate;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillCandidateListResult;
import com.shenchen.cloudcoldagent.workflow.skill.state.SkillWorkflowStateKeys;
import com.shenchen.cloudcoldagent.prompts.SkillWorkflowPrompts;
import com.shenchen.cloudcoldagent.service.SkillService;
import com.shenchen.cloudcoldagent.workflow.skill.service.StructuredOutputAgentExecutor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * `RecognizeBoundSkillsNode` 类型实现。
 */
@Component
public class RecognizeBoundSkillsNode {

    private final SkillService skillService;
    private final StructuredOutputAgentExecutor structuredOutputAgentExecutor;

    /**
     * 创建 `RecognizeBoundSkillsNode` 实例。
     *
     * @param skillService skillService 参数。
     * @param structuredOutputAgentExecutor structuredOutputAgentExecutor 参数。
     */
    public RecognizeBoundSkillsNode(SkillService skillService,
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
        if (boundSkills.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of(SkillWorkflowStateKeys.CANDIDATE_SKILLS, List.of()));
        }

        List<SkillMetadataVO> metadataList = boundSkills.stream()
                .map(skillService::getSkillMetadata)
                .toList();

        SkillCandidateListResult result = structuredOutputAgentExecutor.execute(List.of(
                new SystemMessage(SkillWorkflowPrompts.buildBoundSkillRecognitionPrompt()),
                new UserMessage(SkillWorkflowPrompts.buildBoundSkillRecognitionInput(
                        question,
                        SkillWorkflowPrompts.renderConversationHistory(conversationHistory),
                        JSONUtil.toJsonStr(metadataList)
                ))
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
