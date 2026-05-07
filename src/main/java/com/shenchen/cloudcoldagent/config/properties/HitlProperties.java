package com.shenchen.cloudcoldagent.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * `HitlProperties` 类型实现。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cloudcold.hitl")
public class HitlProperties {

    /**
     * 是否开启 HITL。
     */
    private boolean enabled = false;

    /**
     * 需要人工确认的工具名列表。
     */
    private Set<String> interceptToolNames = new LinkedHashSet<>();
}
