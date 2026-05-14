package com.shenchen.cloudcoldagent.prompts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * `UserLongTermMemoryPrompts` 类型实现。
 */
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
                memoryType 只能是 USER_PROFILE、FACT、PREFERENCE。
                title 必须是抽象后的中文短标题，不要直接照抄用户原话。
                content 必须是完整陈述句，简洁、稳定、可复用，默认使用“用户...”开头，不能写成原始对话口吻。
                summary 是 30 字以内摘要，必须概括记忆，不要直接复制 content。
                confidence 和 importance 使用 0 到 1 的数字。
                sourceHistoryIds 必须是支持这条记忆的历史消息 id 列表，只能从输入中挑选，不要编造。
                最多输出 8 条。

                记忆类型规则：
                1. USER_PROFILE：用户的稳定身份、背景、长期设定、固定属性。
                2. FACT：可被后续复用的客观事实、已确认结果、稳定条件。
                3. PREFERENCE：用户相对稳定的偏好、倾向、长期需求方向、习惯性关注点。

                强制约束：
                1. PREFERENCE 禁止直接复述用户单次请求原话，尤其不能写成“帮我...”“请你...”“我要...”“给我...”这类祈使句。
                2. 如果只是一次性任务请求，通常不要提炼成 PREFERENCE；只有当它体现出可复用的长期倾向时，才能归入 PREFERENCE。
                3. FACT 必须尽量自包含；如果一条事实离开关键前提就无法复用，不要只保留结果本身。
                4. 对计算结果类 FACT，优先保留“关键输入条件 + 结果 + 重要假设”的完整表述。
                5. 禁止只保留下游派生结果而丢失上游关键事实。
                6. 优先保留更基础、更稳定、更能支持后续推理的事实，而不是零散中间变量。
                7. 如果多条候选记忆描述的是同一件事，优先合并成一条更完整、更可复用的记忆，避免碎片化和重复。
                8. content 不要包含“这次对话中”“用户刚刚说”“当前问题是”这类会话临时上下文。
                9. 如果某条信息缺少关键条件，导致无法稳定复用，则宁可不输出，也不要输出残缺记忆。
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
