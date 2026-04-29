package com.shenchen.cloudcoldagent.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.shenchen.cloudcoldagent.exception.BusinessException;
import com.shenchen.cloudcoldagent.exception.ErrorCode;
import com.shenchen.cloudcoldagent.mapper.ChatMemoryHistoryMapper;
import com.shenchen.cloudcoldagent.model.entity.ChatMemoryHistory;
import com.shenchen.cloudcoldagent.service.ChatConversationService;
import com.shenchen.cloudcoldagent.service.ChatMemoryHistoryService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 聊天记忆服务层实现
 */
@Service
public class ChatMemoryHistoryServiceImpl extends ServiceImpl<ChatMemoryHistoryMapper, ChatMemoryHistory>
        implements ChatMemoryHistoryService {

    private final ChatConversationService chatConversationService;

    public ChatMemoryHistoryServiceImpl(ChatConversationService chatConversationService) {
        this.chatConversationService = chatConversationService;
    }

    @Override
    public List<ChatMemoryHistory> listByConversationId(Long userId, String conversationId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "conversationId 不能为空");
        }
        String normalizedConversationId = chatConversationService.normalizeConversationId(userId, conversationId);
        boolean owned = chatConversationService.isConversationOwnedByUser(userId, normalizedConversationId);
        if (!owned) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该会话");
        }

        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("conversationId", normalizedConversationId)
                .eq("isDelete", 0)
                .orderBy("createTime", true)
                .orderBy("id", true);
        return this.mapper.selectListByQuery(queryWrapper);
    }

    @Override
    public List<String> listConversationIdsByUserId(Long userId) {
        return chatConversationService.listConversationIdsByUserId(userId);
    }

    @Override
    public boolean deleteByHistoryId(Long userId, Long id) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "userId 不合法");
        }
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "id 不合法");
        }

        ChatMemoryHistory history = this.mapper.selectOneByQuery(QueryWrapper.create().eq("id", id).eq("isDelete", 0));
        if (history == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "记录不存在");
        }

        boolean owned = chatConversationService.isConversationOwnedByUser(userId, history.getConversationId());
        if (!owned) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限删除该消息");
        }
        ChatMemoryHistory updating = ChatMemoryHistory.builder()
                .isDelete(1)
                .build();
        return this.mapper.updateByQuery(
                updating,
                QueryWrapper.create()
                        .eq("id", id)
                        .eq("isDelete", 0)
        ) > 0;
    }
}
