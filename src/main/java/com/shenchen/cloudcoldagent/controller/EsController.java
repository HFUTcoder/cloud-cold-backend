package com.shenchen.cloudcoldagent.controller;

import com.shenchen.cloudcoldagent.document.extract.cleaner.DocumentCleaner;
import com.shenchen.cloudcoldagent.document.extract.reader.DocumentReaderFactory;
import com.shenchen.cloudcoldagent.document.load.store.StoreService;
import com.shenchen.cloudcoldagent.document.transform.splitter.OverlapParagraphTextSplitter;
import com.shenchen.cloudcoldagent.model.entity.EsDocumentChunk;
import com.shenchen.cloudcoldagent.service.ElasticSearchService;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.List;

/**
 * `EsController` 类型实现。
 */
@RestController
@RequestMapping("/es")
public class EsController {

    private final DocumentReaderFactory selector;

    private final ElasticSearchService elasticSearchService;

    private final StoreService storeService;

    /**
     * 创建 `EsController` 实例。
     *
     * @param selector selector 参数。
     * @param elasticSearchService elasticSearchService 参数。
     * @param storeService storeService 参数。
     */
    public EsController(DocumentReaderFactory selector,
                        ElasticSearchService elasticSearchService,
                        StoreService storeService) {
        this.selector = selector;
        this.elasticSearchService = elasticSearchService;
        this.storeService = storeService;
    }

    /**
     * 处理 `write` 对应逻辑。
     *
     * @param filePath filePath 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    @RequestMapping("write")
    public String write(String filePath) throws Exception {
        // 1. 加载文档
        List<Document> documents = selector.read(new File(filePath));

        // 2. 文本清洗
        documents = DocumentCleaner.cleanDocuments(documents);

        // 3. 文档分片
        OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(
                // 每块最大字符数
                200,
                // 块之间重叠 100 字符
                50
        );
        List<Document> apply = splitter.apply(documents);

        // 4. 存储到ES
        List<EsDocumentChunk> esDocs = apply.stream().map(doc -> {
            EsDocumentChunk es = new EsDocumentChunk();
            es.setId(doc.getId());
            es.setContent(doc.getText());
            es.setMetadata(doc.getMetadata());
            return es;
        }).toList();

        elasticSearchService.bulkIndex(esDocs);
        storeService.storeVectorChunks(esDocs);
        return "success";
    }

    /**
     * 处理 `search` 对应逻辑。
     *
     * @param keyword keyword 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    @RequestMapping("search")
    public List<EsDocumentChunk> search(String keyword) throws Exception {
        return elasticSearchService.searchByKeyword(keyword);
    }
}
