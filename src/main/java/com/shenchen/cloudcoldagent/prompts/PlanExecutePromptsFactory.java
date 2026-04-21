package com.shenchen.cloudcoldagent.prompts;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PlanExecutePromptsFactory {

    /**
     * 生成执行计划
     */
    private String planPrompt;

    /**
     * 工具执行（React 执行器）
     */
    private String executePrompt;

    /**
     * 任务批判
     */
    private String critiquePrompt;

    /**
     * 上下文压缩
     */
    private String compressPrompt;

    /**
     * 最终总结
     */
    private String summarizePrompt;

    /**
     * 默认 Prompt 集
     */
    public static PlanExecutePromptsFactory buildPrompts() {
        return PlanExecutePromptsFactory.builder()
                .planPrompt(DefaultPrompts.PLAN)
                .executePrompt(DefaultPrompts.EXECUTE)
                .critiquePrompt(DefaultPrompts.CRITIQUE)
                .compressPrompt(DefaultPrompts.COMPRESS)
                .summarizePrompt(DefaultPrompts.SUMMARIZE)
                .build();
    }

    public static PlanExecutePromptsFactory buildPrompts(PlanExecutePromptsFactory custom) {
        PlanExecutePromptsFactory defaults = buildPrompts();

        if (custom == null) {
            return defaults;
        }

        return PlanExecutePromptsFactory.builder()
                .planPrompt(custom.planPrompt != null ? custom.planPrompt : defaults.planPrompt)
                .executePrompt(custom.executePrompt != null ? custom.executePrompt : defaults.executePrompt)
                .critiquePrompt(custom.critiquePrompt != null ? custom.critiquePrompt : defaults.critiquePrompt)
                .compressPrompt(custom.compressPrompt != null ? custom.compressPrompt : defaults.compressPrompt)
                .summarizePrompt(custom.summarizePrompt != null ? custom.summarizePrompt : defaults.summarizePrompt)
                .build();
    }
}
