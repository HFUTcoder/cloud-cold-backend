package com.shenchen.cloudcoldagent.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.mapper.UserConversationRelationMapper;
import com.shenchen.cloudcoldagent.model.entity.UserConversationRelation;
import com.shenchen.cloudcoldagent.service.UserConversationRelationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class UserConversationRelationServiceImpl extends ServiceImpl<UserConversationRelationMapper, UserConversationRelation>
        implements UserConversationRelationService {

    @Override
    public List<String> listConversationIdsByUserId(Long userId) {
        validateUserId(userId);
        return this.mapper.selectListByQuery(QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("isDelete", 0)
                        .orderBy("id", false))
                .stream()
                .map(UserConversationRelation::getConversationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    @Override
    public boolean isConversationOwnedByUser(Long userId, String conversationId) {
        validateUserId(userId);
        validateConversationId(conversationId);
        return this.mapper.selectCountByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("conversationId", conversationId.trim())
                .eq("isDelete", 0)) > 0;
    }

    @Override
    public void bindUserConversation(Long userId, String conversationId, LocalDateTime now) {
        validateUserId(userId);
        validateConversationId(conversationId);
        LocalDateTime timestamp = now == null ? LocalDateTime.now() : now;
        UserConversationRelation existing = this.mapper.selectOneByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("conversationId", conversationId.trim())
                .eq("isDelete", 0));
        if (existing != null) {
            return;
        }
        this.save(UserConversationRelation.builder()
                .userId(userId)
                .conversationId(conversationId.trim())
                .createTime(timestamp)
                .updateTime(timestamp)
                .isDelete(0)
                .build());
    }

    @Override
    public boolean deleteByConversationId(String conversationId) {
        validateConversationId(conversationId);
        return this.remove(QueryWrapper.create()
                .eq("conversationId", conversationId.trim())
                .eq("isDelete", 0));
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
}
