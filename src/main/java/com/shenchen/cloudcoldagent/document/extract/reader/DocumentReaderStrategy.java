package com.shenchen.cloudcoldagent.document.extract.reader;

import org.springframework.ai.document.Document;

import java.io.File;
import java.io.IOException;
import java.util.List;

public interface DocumentReaderStrategy {

    /**
     * 判断是否支持该文件
     */
    boolean supports(File file);

    /**
     * 读取文件并返回 Document 列表
     */
    List<Document> read(File file) throws IOException;
}
