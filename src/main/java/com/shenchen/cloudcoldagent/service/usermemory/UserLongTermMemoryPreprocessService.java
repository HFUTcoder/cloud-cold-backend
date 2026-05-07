package com.shenchen.cloudcoldagent.service.usermemory;

import com.shenchen.cloudcoldagent.model.entity.usermemory.UserLongTermMemoryPreprocessResult;

/**
 * `UserLongTermMemoryPreprocessService` 接口定义。
 */
public interface UserLongTermMemoryPreprocessService {

    /**
     * 预处理 `preprocess` 对应内容。
     *
     * @param userId userId 参数。
     * @param question question 参数。
     * @return 返回处理结果。
     */
    UserLongTermMemoryPreprocessResult preprocess(Long userId, String question);
}
