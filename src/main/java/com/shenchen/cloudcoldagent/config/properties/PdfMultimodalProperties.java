package com.shenchen.cloudcoldagent.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "cloudcold.pdf.multimodal")
public class PdfMultimodalProperties {

    private String apiKey;

    private String baseUrl;

    private String model = "qwen3-vl-plus";

    private Double temperature = 0.2d;
}
