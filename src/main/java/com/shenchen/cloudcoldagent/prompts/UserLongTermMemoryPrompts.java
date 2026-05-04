package com.shenchen.cloudcoldagent.prompts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class UserLongTermMemoryPrompts {

    private UserLongTermMemoryPrompts() {
    }

    public static String buildExtractionSystemPrompt() {
        return """
                你是一个用户长期记忆整理器。
                你的任务是从当前单个会话的聊天历史里，提炼出少量、稳定、可复用的长期记忆。
                只保留真正值得长期保存的信息，禁止输出临时任务、一次性上下文、模糊推测或隐私猜测。
                输出必须是一个 JSON 对象，格式为：
                { "items": [ ... ] }
                其中 items 是数组，每个元素包含字段：
                memoryType,title,content,summary,confidence,importance,sourceHistoryIds
                memoryType 只能是 IDENTITY、PREFERENCE、WORKFLOW、FACT、EXPERIENCE、CONSTRAINT。
                content 必须简洁、稳定、可复用。
                summary 是 30 字以内摘要。
                confidence 和 importance 使用 0 到 1 的数字。
                sourceHistoryIds 必须是支持这条记忆的历史消息 id 列表，只能从输入中挑选，不要编造。
                最多输出 8 条。
                """;
    }

    public static String buildExtractionUserPrompt(Long userId, String transcriptJson) {
        return """
                用户ID：%s
                以下是该用户当前会话的历史消息，请提炼长期记忆：
                %s
                """.formatted(userId, StringUtils.defaultString(transcriptJson, "[]"));
    }

    public static String buildRuntimePrompt(List<UserLongTermMemoryDoc> memories, int maxPromptMemories) {
        if (memories == null || memories.isEmpty() || maxPromptMemories <= 0) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        int limit = Math.min(maxPromptMemories, memories.size());
        for (int i = 0; i < limit; i++) {
            UserLongTermMemoryDoc memory = memories.get(i);
            if (memory == null || StringUtils.isBlank(memory.getContent())) {
                continue;
            }
            String type = StringUtils.defaultIfBlank(memory.getMemoryType(), "MEMORY");
            lines.add((i + 1) + ". [" + type + "] " + memory.getContent().trim());
        }
        if (lines.isEmpty()) {
            return null;
        }
        return """
                以下是关于当前用户的长期记忆，仅在相关时使用。
                如果这些记忆与当前问题无关，不要强行引用。

                %s
                """.formatted(String.join("\n", lines));
    }

    public static String renderTranscriptJson(ObjectMapper objectMapper, List<Map<String, Object>> transcript) {
        if (objectMapper == null || transcript == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(transcript);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
