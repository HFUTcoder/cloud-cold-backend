package com.shenchen.cloudcoldagent.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.mapper.ConversationSkillRelationMapper;
import com.shenchen.cloudcoldagent.model.entity.ConversationSkillRelation;
import com.shenchen.cloudcoldagent.service.ConversationSkillRelationService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 会话-skill 绑定服务实现，负责读取和替换某个会话当前绑定的 skill 列表。
 */
@Service
public class ConversationSkillRelationServiceImpl
        extends ServiceImpl<ConversationSkillRelationMapper, ConversationSkillRelation>
        implements ConversationSkillRelationService {

    /**
     * 查询指定用户某个会话当前绑定的 skill 名称列表。
     *
     * @param userId 用户 id。
     * @param conversationId 会话 id。
     * @return 去重后的 skill 名称列表。
     */
    @Override
    public List<String> listSkillNamesByUserIdAndConversationId(Long userId, String conversationId) {
        validateUserId(userId);
        validateConversationId(conversationId);
        return this.mapper.selectListByQuery(QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("conversationId", conversationId.trim())
                        .eq("isDelete", 0)
                        .orderBy("id", true))
                .stream()
                .map(ConversationSkillRelation::getSkillName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(skillName -> !skillName.isBlank())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
    }

    /**
     * 用新的 skill 列表覆盖会话现有绑定关系。
     *
     * @param userId 用户 id。
     * @param conversationId 会话 id。
     * @param skillNames 新的 skill 名称列表。
     * @param now 更新时间；为空时使用当前时间。
     */
    @Override
    public void replaceSkills(Long userId, String conversationId, List<String> skillNames, LocalDateTime now) {
        validateUserId(userId);
        validateConversationId(conversationId);
        LocalDateTime timestamp = now == null ? LocalDateTime.now() : now;
        this.mapper.updateByQuery(
                ConversationSkillRelation.builder().isDelete(1).build(),
                QueryWrapper.create()
                        .eq("userId", userId)
                        .eq("conversationId", conversationId.trim())
                        .eq("isDelete", 0)
        );

        if (skillNames == null || skillNames.isEmpty()) {
            return;
        }

        for (String skillName : skillNames) {
            if (skillName == null || skillName.isBlank()) {
                continue;
            }
            String normalizedSkillName = skillName.trim();
            ConversationSkillRelation existing = this.mapper.selectOneByQuery(QueryWrapper.create()
                    .eq("userId", userId)
                    .eq("conversationId", conversationId.trim())
                    .eq("skillName", normalizedSkillName));
            if (existing != null) {
                existing.setIsDelete(0);
                existing.setUpdateTime(timestamp);
                this.updateById(existing);
                continue;
            }
            this.save(ConversationSkillRelation.builder()
                    .userId(userId)
                    .conversationId(conversationId.trim())
                    .skillName(normalizedSkillName)
                    .createTime(timestamp)
                    .updateTime(timestamp)
                    .isDelete(0)
                    .build());
        }
    }

    /**
     * 按会话 id 逻辑删除 skill 绑定关系。
     *
     * @param conversationId 会话 id。
     * @return 是否成功删除绑定关系。
     */
    @Override
    public boolean deleteByConversationId(String conversationId) {
        validateConversationId(conversationId);
        return this.mapper.updateByQuery(
                ConversationSkillRelation.builder().isDelete(1).build(),
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
