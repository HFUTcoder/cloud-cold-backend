package com.shenchen.cloudcoldagent.document.extract.reader;

import com.shenchen.cloudcoldagent.model.entity.record.knowledge.DocumentReadResult;
import org.springframework.ai.document.Document;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * `DocumentReaderStrategy` 接口定义。
 */
public interface DocumentReaderStrategy {

    /**
     * 判断是否支持该文件
     */
    boolean supports(File file);

    /**
     * 读取文件并返回 Document 列表
     */
    DocumentReadResult read(File file) throws IOException;

    /**
     * 处理 `read Documents` 对应逻辑。
     *
     * @param file file 参数。
     * @return 返回处理结果。
     * @throws IOException 异常信息。
     */
    default List<Document> readDocuments(File file) throws IOException {
        return read(file).documents();
    }
}
