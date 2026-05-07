package com.shenchen.cloudcoldagent.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.mapper.ConversationKnowledgeRelationMapper;
import com.shenchen.cloudcoldagent.model.entity.ConversationKnowledgeRelation;
import com.shenchen.cloudcoldagent.service.ConversationKnowledgeRelationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 会话-知识库绑定服务实现，负责维护会话当前绑定的知识库关系。
 */
@Service
public class ConversationKnowledgeRelationServiceImpl
        extends ServiceImpl<ConversationKnowledgeRelationMapper, ConversationKnowledgeRelation>
        implements ConversationKnowledgeRelationService {

    /**
     * 查询指定用户某个会话当前绑定的知识库关系。
     *
     * @param userId 用户 id。
     * @param conversationId 会话 id。
     * @return 会话知识库绑定关系；不存在时返回 null。
     */
    @Override
    public ConversationKnowledgeRelation getByUserIdAndConversationId(Long userId, String conversationId) {
        validateUserId(userId);
        validateConversationId(conversationId);
        return this.mapper.selectOneByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("conversationId", conversationId.trim())
                .eq("isDelete", 0));
    }

    /**
     * 为会话绑定知识库；已存在绑定时执行覆盖更新。
     *
     * @param userId 用户 id。
     * @param conversationId 会话 id。
     * @param knowledgeId 知识库 id。
     * @param now 更新时间；为空时使用当前时间。
     */
    @Override
    public void bindKnowledge(Long userId, String conversationId, Long knowledgeId, LocalDateTime now) {
        validateUserId(userId);
        validateConversationId(conversationId);
        validateKnowledgeId(knowledgeId);
        LocalDateTime timestamp = now == null ? LocalDateTime.now() : now;
        ConversationKnowledgeRelation existing = this.mapper.selectOneByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("conversationId", conversationId.trim()));
        if (existing != null) {
            existing.setKnowledgeId(knowledgeId);
            existing.setIsDelete(0);
            existing.setUpdateTime(timestamp);
            this.updateById(existing);
            return;
        }
        this.save(ConversationKnowledgeRelation.builder()
                .userId(userId)
                .conversationId(conversationId.trim())
                .knowledgeId(knowledgeId)
                .createTime(timestamp)
                .updateTime(timestamp)
                .isDelete(0)
                .build());
    }

    /**
     * 解除会话与知识库的绑定关系。
     *
     * @param userId 用户 id。
     * @param conversationId 会话 id。
     * @return 是否成功解除绑定。
     */
    @Override
    public boolean unbindKnowledge(Long userId, String conversationId) {
        validateUserId(userId);
        validateConversationId(conversationId);
        return this.mapper.updateByQuery(
                ConversationKnowledgeRelation.builder().isDelete(1).build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("conversationId", conversationId.trim())
                        .eq("isDelete", 0)
        ) > 0;
    }

    /**
     * 按会话 id 逻辑删除知识库绑定关系。
     *
     * @param conversationId 会话 id。
     * @return 是否成功删除绑定关系。
     */
    @Override
    public boolean deleteByConversationId(String conversationId) {
        validateConversationId(conversationId);
        return this.mapper.updateByQuery(
                ConversationKnowledgeRelation.builder().isDelete(1).build(),
                QueryWrapper.create()
                        .eq("conversationId", conversationId.trim())
                        .eq("isDelete", 0)
        ) > 0;
    }

    /**
     * 校验 `validate User Id` 对应内容。
     *
     * @param userId userId 参数。
     */
    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
    }

    /**
     * 校验 `validate Conversation Id` 对应内容。
     *
     * @param conversationId conversationId 参数。
     */
    private void validateConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空");
        }
    }

    /**
     * 校验 `validate Knowledge Id` 对应内容。
     *
     * @param knowledgeId knowledgeId 参数。
     */
    private void validateKnowledgeId(Long knowledgeId) {
        if (knowledgeId == null || knowledgeId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "knowledgeId 不合法");
        }
    }
}
