package com.shenchen.cloudcoldagent.service;

import com.mybatisflex.core.service.IService;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.AgentInterrupted;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.PendingToolCall;
import com.shenchen.cloudcoldagent.model.entity.HitlCheckpoint;
import com.shenchen.cloudcoldagent.model.vo.HitlCheckpointVO;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

/**
 * `HitlCheckpointService` 接口定义。
 */
public interface HitlCheckpointService extends IService<HitlCheckpoint> {

    /**
     * 创建 `create Checkpoint` 对应内容。
     *
     * @param conversationId conversationId 参数。
     * @param agentType agentType 参数。
     * @param pendingToolCalls pendingToolCalls 参数。
     * @param checkpointMessages checkpointMessages 参数。
     * @param context context 参数。
     * @return 返回处理结果。
     */
    HitlCheckpointVO createCheckpoint(String conversationId, String agentType,
                                      List<PendingToolCall> pendingToolCalls,
                                      List<Message> checkpointMessages,
                                      Map<String, Object> context);

    /**
     * 获取 `get By Interrupt Id` 对应结果。
     *
     * @param interruptId interruptId 参数。
     * @return 返回处理结果。
     */
    HitlCheckpointVO getByInterruptId(String interruptId);

    /**
     * 获取 `get By Interrupt Id` 对应结果。
     *
     * @param userId userId 参数。
     * @param interruptId interruptId 参数。
     * @return 返回处理结果。
     */
    HitlCheckpointVO getByInterruptId(Long userId, String interruptId);

    /**
     * 获取 `get Latest Pending By Conversation Id` 对应结果。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    HitlCheckpointVO getLatestPendingByConversationId(String conversationId);

    /**
     * 获取 `get Latest Pending By Conversation Id` 对应结果。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    HitlCheckpointVO getLatestPendingByConversationId(Long userId, String conversationId);

    /**
     * 解析 `resolve Checkpoint` 对应结果。
     *
     * @param interruptId interruptId 参数。
     * @param feedbacks feedbacks 参数。
     * @return 返回处理结果。
     */
    HitlCheckpointVO resolveCheckpoint(String interruptId, List<PendingToolCall> feedbacks);

    /**
     * 解析 `resolve Checkpoint` 对应结果。
     *
     * @param userId userId 参数。
     * @param interruptId interruptId 参数。
     * @param feedbacks feedbacks 参数。
     * @return 返回处理结果。
     */
    HitlCheckpointVO resolveCheckpoint(Long userId, String interruptId, List<PendingToolCall> feedbacks);

    /**
     * 处理 `consume Resolved Checkpoint` 对应逻辑。
     *
     * @param interruptId interruptId 参数。
     * @return 返回处理结果。
     */
    HitlCheckpointVO consumeResolvedCheckpoint(String interruptId);

    /**
     * 处理 `consume Resolved Checkpoint` 对应逻辑。
     *
     * @param userId userId 参数。
     * @param interruptId interruptId 参数。
     * @return 返回处理结果。
     */
    HitlCheckpointVO consumeResolvedCheckpoint(Long userId, String interruptId);

    /**
     * 加载 `load Interrupted` 相关内容。
     *
     * @param interruptId interruptId 参数。
     * @return 返回处理结果。
     */
    AgentInterrupted loadInterrupted(String interruptId);

    /**
     * 处理 `append Context` 对应逻辑。
     *
     * @param interruptId interruptId 参数。
     * @param additionalContext additionalContext 参数。
     * @return 返回处理结果。
     */
    HitlCheckpointVO appendContext(String interruptId, Map<String, Object> additionalContext);

    /**
     * 删除 `delete By Conversation Id` 对应内容。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    boolean deleteByConversationId(String conversationId);
}
