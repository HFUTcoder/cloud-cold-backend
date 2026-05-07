package com.shenchen.cloudcoldagent.document.load.store;

import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.service.ElasticSearchService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * `StoreService` 类型实现。
 */
@Service
public class StoreService {

    private final ElasticSearchService elasticSearchService;

    /**
     * 创建 `StoreService` 实例。
     *
     * @param elasticSearchService elasticSearchService 参数。
     */
    public StoreService(ElasticSearchService elasticSearchService) {
        this.elasticSearchService = elasticSearchService;
    }

    /**
     * 将文档分片写入向量索引。
     */
    public void storeVectorChunks(List<EsDocumentChunk> chunks) throws Exception {
        elasticSearchService.vectorAddChunks(chunks);
    }

    /**
     * 将原始文档直接写入向量索引。
     */
    public void storeVectorDocuments(List<Document> documents) throws Exception {
        elasticSearchService.vectorAddDocuments(documents);
    }

}
