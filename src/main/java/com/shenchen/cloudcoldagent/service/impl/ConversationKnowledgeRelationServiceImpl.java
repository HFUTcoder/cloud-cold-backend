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

@Service
public class ConversationKnowledgeRelationServiceImpl
        extends ServiceImpl<ConversationKnowledgeRelationMapper, ConversationKnowledgeRelation>
        implements ConversationKnowledgeRelationService {

    @Override
    public ConversationKnowledgeRelation getByUserIdAndConversationId(Long userId, String conversationId) {
        validateUserId(userId);
        validateConversationId(conversationId);
        return this.mapper.selectOneByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("conversationId", conversationId.trim())
                .eq("isDelete", 0));
    }

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

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
    }

    private void validateConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空");
        }
    }

    private void validateKnowledgeId(Long knowledgeId) {
        if (knowledgeId == null || knowledgeId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "knowledgeId 不合法");
        }
    }
}
