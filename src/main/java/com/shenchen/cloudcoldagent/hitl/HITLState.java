package com.shenchen.cloudcoldagent.hitl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class HITLState {

    private final Set<String> consumedToolCallIds = ConcurrentHashMap.newKeySet();

    public HITLState() {
    }

    public boolean isConsumed(String toolCallId) {
        return consumedToolCallIds.contains(toolCallId);
    }

    public void markConsumed(String toolCallId) {
        consumedToolCallIds.add(toolCallId);
    }

    public Collection<String> snapshotConsumedToolCallIds() {
        return new ArrayList<>(consumedToolCallIds);
    }

    public static HITLState fromConsumedToolCallIds(Collection<String> consumedToolCallIds) {
        HITLState state = new HITLState();
        if (consumedToolCallIds != null) {
            consumedToolCallIds.forEach(state::markConsumed);
        }
        return state;
    }
}
