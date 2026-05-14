package com.shenchen.cloudcoldagent.service.hitl;

import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeRequest;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeResult;

/**
 * `HitlResumeService` 接口定义。
 */
public interface HitlResumeService {

    HitlResumeResult resume(HitlResumeRequest request);
}
