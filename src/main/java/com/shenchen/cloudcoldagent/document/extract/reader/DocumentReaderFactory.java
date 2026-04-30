package com.shenchen.cloudcoldagent.document.extract.reader;

import com.shenchen.cloudcoldagent.model.entity.record.knowledge.DocumentReadResult;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Component
public class DocumentReaderFactory {

    private final List<DocumentReaderStrategy> strategies;

    public DocumentReaderFactory(List<DocumentReaderStrategy> strategies) {
        this.strategies = strategies;
    }

    public DocumentReadResult readResult(File file) throws IOException {
        for (DocumentReaderStrategy strategy : strategies) {
            if (strategy.supports(file)) {
                return strategy.read(file);
            }
        }
        throw new IllegalArgumentException("不支持的文件类型: " + file.getName());
    }

    public List<Document> read(File file) throws IOException {
        return readResult(file).documents();
    }
}
