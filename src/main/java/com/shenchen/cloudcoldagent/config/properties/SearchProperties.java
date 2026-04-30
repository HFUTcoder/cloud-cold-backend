package com.shenchen.cloudcoldagent.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "cloudcold.search")
public class SearchProperties {

    private final Mock mock = new Mock();

    @Data
    public static class Mock {
        private boolean enabled = false;
    }
}
