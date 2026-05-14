package com.shenchen.cloudcoldagent.service.usermemory;

import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryPreprocessResult;

/**
 * `UserLongTermMemoryPreprocessService` 接口定义。
 */
public interface UserLongTermMemoryPreprocessService {

    UserLongTermMemoryPreprocessResult preprocess(Long userId, String question);
}
