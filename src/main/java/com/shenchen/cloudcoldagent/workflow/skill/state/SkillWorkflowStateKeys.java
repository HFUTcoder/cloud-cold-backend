package com.shenchen.cloudcoldagent.workflow.skill.state;

/**
 * `SkillWorkflowStateKeys` 类型实现。
 */
public final class SkillWorkflowStateKeys {

    private SkillWorkflowStateKeys() {
    }

    public static final String USER_ID = "userId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String USER_QUESTION = "userQuestion";
    public static final String CONVERSATION_HISTORY = "conversationHistory";
    public static final String BOUND_SKILLS = "boundSkills";
    public static final String CANDIDATE_SKILLS = "candidateSkills";
    public static final String SELECTED_SKILLS = "selectedSkills";
    public static final String SKILL_CONTENTS = "skillContents";
    public static final String SKILL_RESOURCES = "skillResources";
    public static final String SKILL_RUNTIME_CONTEXTS = "skillRuntimeContexts";
}
