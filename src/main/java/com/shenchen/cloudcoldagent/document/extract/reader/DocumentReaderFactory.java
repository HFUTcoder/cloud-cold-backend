package com.shenchen.cloudcoldagent.document.extract.reader;

import com.shenchen.cloudcoldagent.model.entity.record.knowledge.DocumentReadResult;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * `DocumentReaderFactory` 类型实现。
 */
@Component
public class DocumentReaderFactory {

    private final List<DocumentReaderStrategy> strategies;

    /**
     * 创建 `DocumentReaderFactory` 实例。
     *
     * @param strategies strategies 参数。
     */
    public DocumentReaderFactory(List<DocumentReaderStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * 处理 `read Result` 对应逻辑。
     *
     * @param file file 参数。
     * @return 返回处理结果。
     * @throws IOException 异常信息。
     */
    public DocumentReadResult readResult(File file) throws IOException {
        for (DocumentReaderStrategy strategy : strategies) {
            if (strategy.supports(file)) {
                return strategy.read(file);
            }
        }
        throw new IllegalArgumentException("不支持的文件类型: " + file.getName());
    }

    /**
     * 处理 `read` 对应逻辑。
     *
     * @param file file 参数。
     * @return 返回处理结果。
     * @throws IOException 异常信息。
     */
    public List<Document> read(File file) throws IOException {
        return readResult(file).documents();
    }
}
