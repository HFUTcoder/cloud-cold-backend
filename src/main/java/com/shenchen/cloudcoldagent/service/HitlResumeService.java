package com.shenchen.cloudcoldagent.service;

import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeRequest;
import com.shenchen.cloudcoldagent.model.entity.record.hitl.HitlResumeResult;

public interface HitlResumeService {

    HitlResumeResult resume(HitlResumeRequest request);
}
