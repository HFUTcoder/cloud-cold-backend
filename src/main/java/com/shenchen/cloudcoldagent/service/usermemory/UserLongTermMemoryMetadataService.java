package com.shenchen.cloudcoldagent.service.usermemory;

import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemory;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryDoc;
import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemorySourceRelation;

import java.util.List;
import java.util.Map;

public interface UserLongTermMemoryMetadataService {

    void replaceAll(Long userId, List<UserLongTermMemoryDoc> memories);

    List<UserLongTermMemory> listActiveByUserId(Long userId, int size);

    Map<String, UserLongTermMemory> mapActiveByMemoryIds(Long userId, List<String> memoryIds);

    Map<String, List<UserLongTermMemorySourceRelation>> mapSourcesByMemoryIds(List<String> memoryIds);

    void markRetrieved(Long userId, List<String> memoryIds);

    boolean deleteMemory(Long userId, String memoryId);

    void softDeleteByUserId(Long userId);
}
