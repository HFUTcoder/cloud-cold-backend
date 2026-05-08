package com.shenchen.cloudcoldagent.utils;

import com.alibaba.cloud.ai.graph.OverAllState;

/**
 * OverAllState 值读取工具。
 * <p>
 * OverAllState.value(key).orElse(defaultValue) 返回擦除后的类型，强制转换到泛型参数 T
 * 会产生 unchecked cast 警告。Java 泛型擦除决定了这个 cast 无法在运行时验证元素类型，
 * 因此在此集中处理，避免每个调用点都标注 {@code @SuppressWarnings("unchecked")}。
 * </p>
 */
public final class StateValueUtils {

    private StateValueUtils() {
    }

    /**
     * 从 {@link OverAllState} 中读取指定 key 的值，不存在时返回默认值。
     *
     * @param state        workflow 全局状态
     * @param key          状态键
     * @param defaultValue 默认值
     * @param <T>          目标类型
     * @return 状态值或默认值
     */
    @SuppressWarnings("unchecked")
    public static <T> T getValue(OverAllState state, String key, T defaultValue) {
        return (T) state.value(key).orElse(defaultValue);
    }
}
