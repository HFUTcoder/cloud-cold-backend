package com.shenchen.cloudcoldagent.service;

import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeRequest;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeResult;

/**
 * `HitlResumeService` 接口定义。
 */
public interface HitlResumeService {

    /**
     * 恢复 `resume` 相关流程。
     *
     * @param request request 参数。
     * @return 返回处理结果。
     */
    HitlResumeResult resume(HitlResumeRequest request);
}
