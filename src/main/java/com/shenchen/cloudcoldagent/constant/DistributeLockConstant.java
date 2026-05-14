package com.shenchen.cloudcoldagent.constant;

/**
 * 分布式锁常量。
 */
public interface DistributeLockConstant {

    String NONE_KEY = "NONE";

    String DEFAULT_OWNER = "DEFAULT";

    int DEFAULT_EXPIRE_TIME = -1;

    int DEFAULT_WAIT_TIME = Integer.MAX_VALUE;
}
