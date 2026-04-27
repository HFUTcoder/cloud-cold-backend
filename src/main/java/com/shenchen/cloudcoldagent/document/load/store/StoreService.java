package com.shenchen.cloudcoldagent.document.load.store;

import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.service.ElasticSearchService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StoreService {

    @Autowired
    private ElasticSearchService elasticSearchService;

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
