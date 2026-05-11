package com.shenchen.cloudcoldagent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchen.cloudcoldagent.config.properties.EsProperties;
import com.shenchen.cloudcoldagent.config.properties.LongTermMemoryProperties;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;

import javax.net.ssl.SSLContext;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * `EsConfig` 类型实现。
 */
@Configuration
@Slf4j
public class EsConfig {

    private static final String RAG_DOCS_INDEX = "rag_docs";
    private static final String RAG_DOCS_MAPPING = "com/shenchen/cloudcoldagent/database/es_rag_docs_mapping.json";
    private static final String LONG_TERM_MEMORY_MAPPING = "com/shenchen/cloudcoldagent/database/user_long_term_memory_mapping.json";

    private final EsProperties elasticsearchClientProperties;
    private final LongTermMemoryProperties longTermMemoryProperties;
    private final ObjectMapper esObjectMapper;

    public EsConfig(EsProperties elasticsearchClientProperties,
                    LongTermMemoryProperties longTermMemoryProperties,
                    @Qualifier("esObjectMapper") ObjectMapper esObjectMapper) {
        this.elasticsearchClientProperties = elasticsearchClientProperties;
        this.longTermMemoryProperties = longTermMemoryProperties;
        this.esObjectMapper = esObjectMapper;
    }

    /**
     * 处理 `elasticsearch Rest Client` 对应逻辑。
     *
     * @return 返回处理结果。
     */
    @Bean
    @Lazy
    public RestClient elasticsearchRestClient() {
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

            return builder.build();
        } catch (Exception e) {
            log.warn("Failed to create Elasticsearch client: {}. ES functionality will be unavailable.", e.getMessage());
            return null;
        }
    }

    /**
     * 处理 `elasticsearch Client` 对应逻辑。
     *
     * @param elasticsearchRestClient elasticsearchRestClient 参数。
     * @return 返回处理结果。
     */
    @Bean
    @Lazy
    public ElasticsearchClient elasticsearchClient(RestClient elasticsearchRestClient) {
        if (elasticsearchRestClient == null) {
            return null;
        }
        ElasticsearchTransport transport = new RestClientTransport(
                elasticsearchRestClient,
                new JacksonJsonpMapper(esObjectMapper)
        );
        return new ElasticsearchClient(transport);
    }

    /**
     * 应用启动后检查并创建所有 ES 关键词索引。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initEsIndices() {
        try {
            RestClient restClient = elasticsearchRestClient();
            if (restClient == null) {
                log.warn("Elasticsearch RestClient is null, skip index creation.");
                return;
            }
            ElasticsearchClient client = elasticsearchClient(restClient);
            if (client == null) {
                log.warn("Elasticsearch client is null, skip index creation.");
                return;
            }

            createIndexIfNotExists(client, RAG_DOCS_INDEX, RAG_DOCS_MAPPING);
            createIndexIfNotExists(client, longTermMemoryProperties.getKeywordIndexName(), LONG_TERM_MEMORY_MAPPING);
        } catch (Exception e) {
            log.error("Failed to create ES indices: {}", e.getMessage(), e);
        }
    }

    private void createIndexIfNotExists(ElasticsearchClient client, String indexName, String resourcePath) {
        try {
            boolean exists = client.indices().exists(
                    ExistsRequest.of(e -> e.index(indexName))
            ).value();

            if (!exists) {
                var resource = new ClassPathResource(resourcePath);
                String json;
                try (var in = resource.getInputStream()) {
                    json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                client.indices().create(
                        CreateIndexRequest.of(b -> b
                                .index(indexName)
                                .withJson(new StringReader(json))
                        )
                );
                log.info("ES index [{}] created.", indexName);
            } else {
                log.info("ES index [{}] already exists, skip creation.", indexName);
            }
        } catch (Exception e) {
            log.error("Failed to create ES index [{}]: {}", indexName, e.getMessage(), e);
        }
    }
}
