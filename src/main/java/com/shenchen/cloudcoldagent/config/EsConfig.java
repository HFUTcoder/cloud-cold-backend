package com.shenchen.cloudcoldagent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.shenchen.cloudcoldagent.config.properties.EsProperties;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.net.ssl.SSLContext;

@Configuration
public class EsConfig {

    private static final Logger logger = LoggerFactory.getLogger(EsConfig.class);

    private final EsProperties elasticsearchClientProperties;

    public EsConfig(EsProperties elasticsearchClientProperties) {
        this.elasticsearchClientProperties = elasticsearchClientProperties;
    }

    @Bean
    @Lazy
    public ElasticsearchClient elasticsearchClient() {
        try {
            String uris = elasticsearchClientProperties.getUris();
            String username = elasticsearchClientProperties.getUsername();
            String password = elasticsearchClientProperties.getPassword();
            boolean insecure = elasticsearchClientProperties.isInsecure();
            RestClientBuilder builder = RestClient.builder(HttpHost.create(uris));

            // 如果需要 Basic Auth，配置 CredentialsProvider
            if (username != null && !username.isEmpty()) {
                final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(username, password));
                builder.setHttpClientConfigCallback(httpClientBuilder -> {
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    // 如果是 https 且 insecure=true，继续设置 SSLContext 和 HostnameVerifier
                    if (uris.startsWith("https") && insecure) {
                        try {
                            SSLContext sslContext = SSLContexts.custom()
                                    .loadTrustMaterial(null, (chain, authType) -> true) // trust all
                                    .build();
                            httpClientBuilder
                                    .setSSLContext(sslContext)
                                    .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to create SSLContext for ES client", e);
                        }
                    }
                    return httpClientBuilder;
                });
            } else {
                // 没有用户名，仅设置 insecure SSL（如果需要）
                if (uris.startsWith("https") && insecure) {
                    builder.setHttpClientConfigCallback(httpClientBuilder -> {
                        try {
                            SSLContext sslContext = SSLContexts.custom()
                                    .loadTrustMaterial(null, (chain, authType) -> true)
                                    .build();
                            httpClientBuilder
                                    .setSSLContext(sslContext)
                                    .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                            return httpClientBuilder;
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to create SSLContext for ES client", e);
                        }
                    });
                }
            }

            RestClient restClient = builder.build();
            ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            return new ElasticsearchClient(transport);
        } catch (Exception e) {
            logger.warn("Failed to create Elasticsearch client: {}. ES functionality will be unavailable.", e.getMessage());
            return null;
        }
    }
}
