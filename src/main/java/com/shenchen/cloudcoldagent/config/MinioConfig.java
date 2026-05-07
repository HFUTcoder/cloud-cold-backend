package com.shenchen.cloudcoldagent.config;

import com.shenchen.cloudcoldagent.config.properties.MinioProperties;
import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * `MinioConfig` 类型实现。
 */
@Configuration
public class MinioConfig {

    private final MinioProperties minioProperties;

    /**
     * 创建 `MinioConfig` 实例。
     *
     * @param minioProperties minioProperties 参数。
     */
    public MinioConfig(MinioProperties minioProperties) {
        this.minioProperties = minioProperties;
    }

    /**
     * 处理 `minio Client` 对应逻辑。
     *
     * @return 返回处理结果。
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
    }
}
