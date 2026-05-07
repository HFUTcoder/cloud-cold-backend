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

/**
 * 用户-会话归属关系服务实现，负责维护会话归属和相关查询。
 */
@Service
public class UserConversationRelationServiceImpl extends ServiceImpl<UserConversationRelationMapper, UserConversationRelation>
        implements UserConversationRelationService {

    /**
     * 查询 `list Conversation Ids By User Id` 对应集合。
     *
     * @param userId userId 参数。
     * @return 返回处理结果。
     */
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

    /**
     * 根据会话 id 查询其归属用户。
     *
     * @param conversationId 会话 id。
     * @return 用户 id；不存在时返回 null。
     */
    @Override
    public Long getUserIdByConversationId(String conversationId) {
        validateConversationId(conversationId);
        UserConversationRelation relation = this.mapper.selectOneByQuery(QueryWrapper.create()
                .eq("conversationId", conversationId.trim())
                .eq("isDelete", 0)
                .orderBy("id", false));
        return relation == null ? null : relation.getUserId();
    }

    /**
     * 判断 `is Conversation Owned By User` 条件是否成立。
     *
     * @param userId userId 参数。
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    @Override
    public boolean isConversationOwnedByUser(Long userId, String conversationId) {
        validateUserId(userId);
        validateConversationId(conversationId);
        return this.mapper.selectCountByQuery(QueryWrapper.create()
                .eq("userId", userId)
                .eq("conversationId", conversationId.trim())
                .eq("isDelete", 0)) > 0;
    }

    /**
     * 建立用户与会话之间的归属关系。
     *
     * @param userId 用户 id。
     * @param conversationId 会话 id。
     * @param now 绑定时间；为空时使用当前时间。
     */
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

    /**
     * 删除 `delete By Conversation Id` 对应内容。
     *
     * @param conversationId conversationId 参数。
     * @return 返回处理结果。
     */
    @Override
    public boolean deleteByConversationId(String conversationId) {
        validateConversationId(conversationId);
        UserConversationRelation updating = UserConversationRelation.builder()
                .isDelete(1)
                .build();
        return this.mapper.updateByQuery(
                updating,
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
}
