package com.shenchen.cloudcoldagent.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * `EsProperties` 类型实现。
 */
@Data
@Component
@ConfigurationProperties(prefix = "spring.elasticsearch")
public class EsProperties {

    private String uris;

    private String username;

    private String password;

    private boolean insecure = false;
}
