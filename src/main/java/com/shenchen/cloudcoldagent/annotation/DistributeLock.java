package com.shenchen.cloudcoldagent.annotation;

import com.shenchen.cloudcoldagent.constant.DistributeLockConstant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 分布式锁注解
 *
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributeLock {

    /**
     * 锁的场景
     *
     * @return
     */
    public String scene();

    /**
     * 加锁的key，优先取key()，如果没有，则取keyExpression()
     *
     * @return
     */
    public String key() default DistributeLockConstant.NONE_KEY;

    /**
     * SPEL表达式:
     * <pre>
     *     #id
     *     #insertResult.id
     * </pre>
     *
     * @return
     */
    public String keyExpression() default DistributeLockConstant.NONE_KEY;

    /**
     * 超时时间，毫秒
     * 默认情况下不设置超时时间，会自动续期
     *
     * @return
     */
    public int expireTime() default DistributeLockConstant.DEFAULT_EXPIRE_TIME;

    /**
     * 加锁等待时长，毫秒
     * 默认情况下不设置等待时长，会一直等待直到获取到锁
     * @return
     */
    public int waitTime() default DistributeLockConstant.DEFAULT_WAIT_TIME;
}
