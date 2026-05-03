package com.shenchen.cloudcoldagent.model.vo.usermemory;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UserPetStateVO {

    private boolean enabled;

    private String petName;

    private String petMood;

    private int memoryCount;

    private int pendingRounds;

    private LocalDateTime lastLearnedAt;

    private List<String> memoryHighlights;

    private List<UserLongTermMemoryVO> recentMemories;
}
