package com.shenchen.cloudcoldagent.service.usermemory;

import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryPreprocessResult;

public interface UserLongTermMemoryPreprocessService {

    UserLongTermMemoryPreprocessResult preprocess(Long userId, String question);
}
