package com.shenchen.cloudcoldagent.model.entity.usermemory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLongTermMemoryExtractionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<UserLongTermMemoryExtractionItem> items;
}
