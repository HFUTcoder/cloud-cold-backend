package com.shenchen.cloudcoldagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "spring.elasticsearch")
public class ElasticsearchClientProperties {

    private String uris;

    private String username;

    private String password;

    private boolean insecure = false;
}
