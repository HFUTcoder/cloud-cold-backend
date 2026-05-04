package com.shenchen.cloudcoldagent.service.usermemory;

import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.model.vo.usermemory.UserLongTermMemoryVO;
import com.shenchen.cloudcoldagent.model.vo.usermemory.UserPetStateVO;

import java.util.List;

public interface UserLongTermMemoryService {

    UserPetStateVO getPetState(Long userId);

    List<UserLongTermMemoryVO> listMemories(Long userId);

    boolean setEnabled(Long userId, boolean enabled);

    boolean renamePet(Long userId, String petName);

    boolean deleteMemory(Long userId, String memoryId);

    void onAssistantMessagePersisted(Long userId, String conversationId, int assistantMessageCount);

    void onConversationDeleted(Long userId, String conversationId);

    void onHistoryDeleted(Long userId, String conversationId);

    List<UserLongTermMemoryDoc> retrieveRelevantMemories(Long userId, String question, int topK);

    void processPendingConversations(Long userId);
}
