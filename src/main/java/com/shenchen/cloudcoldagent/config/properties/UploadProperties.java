package com.shenchen.cloudcoldagent.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

@Data
@Component
@ConfigurationProperties(prefix = "cloudcold.upload")
public class UploadProperties {

    /**
     * 单文件上传大小限制。
     */
    private DataSize maxFileSize = DataSize.ofMegabytes(100);

    /**
     * 单次请求总大小限制。
     */
    private DataSize maxRequestSize = DataSize.ofMegabytes(100);
}
