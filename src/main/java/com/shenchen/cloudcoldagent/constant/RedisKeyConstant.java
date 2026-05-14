package com.shenchen.cloudcoldagent.constant;

/**
 * Redis key 前缀常量，统一管理各业务域的 Redis key 命名。
 */
public interface RedisKeyConstant {

    // 长期记忆 / 宠物记忆
    String USER_MEMORY_PET_NAME = "user_memory:pet_name:";
    String USER_MEMORY_LAST_LEARNED_AT = "user_memory:last_learned_at:";
}
