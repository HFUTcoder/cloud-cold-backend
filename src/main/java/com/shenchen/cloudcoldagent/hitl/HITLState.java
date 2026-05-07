package com.shenchen.cloudcoldagent.hitl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * `HITLState` 类型实现。
 */
public class HITLState {

    private final Set<String> consumedToolCallIds = ConcurrentHashMap.newKeySet();

    /**
     * 创建 `HITLState` 实例。
     */
    public HITLState() {
    }

    /**
     * 判断 `is Consumed` 条件是否成立。
     *
     * @param toolCallId toolCallId 参数。
     * @return 返回处理结果。
     */
    public boolean isConsumed(String toolCallId) {
        return consumedToolCallIds.contains(toolCallId);
    }

    /**
     * 处理 `mark Consumed` 对应逻辑。
     *
     * @param toolCallId toolCallId 参数。
     */
    public void markConsumed(String toolCallId) {
        consumedToolCallIds.add(toolCallId);
    }

    /**
     * 处理 `snapshot Consumed Tool Call Ids` 对应逻辑。
     *
     * @return 返回处理结果。
     */
    public Collection<String> snapshotConsumedToolCallIds() {
        return new ArrayList<>(consumedToolCallIds);
    }

    /**
     * 处理 `from Consumed Tool Call Ids` 对应逻辑。
     *
     * @param consumedToolCallIds consumedToolCallIds 参数。
     * @return 返回处理结果。
     */
    public static HITLState fromConsumedToolCallIds(Collection<String> consumedToolCallIds) {
        HITLState state = new HITLState();
        if (consumedToolCallIds != null) {
            consumedToolCallIds.forEach(state::markConsumed);
        }
        return state;
    }
}
