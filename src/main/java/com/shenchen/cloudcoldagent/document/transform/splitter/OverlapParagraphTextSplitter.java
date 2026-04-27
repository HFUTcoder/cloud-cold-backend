package com.shenchen.cloudcoldagent.document.transform.splitter;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OverlapParagraphTextSplitter extends TextSplitter {

    // 每块最大字符数
    protected final int chunkSize;

    // 相邻块之间重叠字符数
    protected final int overlap;

    public OverlapParagraphTextSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap 不能为负数");
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 不能大于等于 chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    protected List<String> splitText(String text) {
        if (StringUtils.isBlank(text)) {
            return Collections.emptyList();
        }

        String[] paragraphs = text.split("\\n+");
        List<String> allChunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (StringUtils.isBlank(paragraph)) {
                continue;
            }

            int start = 0;
            boolean separatorPending = currentChunk.length() > 0;
            while (start < paragraph.length()) {
                int separatorLength = separatorPending ? 1 : 0;
                int remainingSpace = chunkSize - currentChunk.length() - separatorLength;
                if (remainingSpace <= 0) {
                    currentChunk = flushChunk(allChunks, currentChunk);
                    separatorPending = currentChunk.length() > 0;
                    continue;
                }

                if (separatorPending) {
                    currentChunk.append('\n');
                    separatorPending = false;
                }

                int end = Math.min(start + remainingSpace, paragraph.length());

                currentChunk.append(paragraph, start, end);

                // 如果当前块已满，保存并生成新块
                if (currentChunk.length() >= chunkSize) {
                    currentChunk = flushChunk(allChunks, currentChunk);
                }

                start = end;
            }
        }

        if (currentChunk.length() > 0){
            allChunks.add(currentChunk.toString());
        }

        return allChunks;
    }

    /**
     * 批量拆分
     */
    @Override
    public List<Document> apply(List<Document> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return Collections.emptyList();
        }

        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            List<String> chunks = splitText(doc.getText());
            for (int i = 0; i < chunks.size(); i++) {
                result.add(createChunkDocument(doc, chunks.get(i), i, chunks.size()));
            }
        }
        return result;
    }

    private StringBuilder flushChunk(List<String> allChunks, StringBuilder currentChunk) {
        allChunks.add(currentChunk.toString());

        String overlapText = "";
        if (overlap > 0) {
            int overlapStart = Math.max(0, currentChunk.length() - overlap);
            overlapText = currentChunk.substring(overlapStart);
        }

        StringBuilder nextChunk = new StringBuilder();
        if (!overlapText.isEmpty()) {
            nextChunk.append(overlapText);
        }
        return nextChunk;
    }

    private Document createChunkDocument(Document sourceDocument, String chunkText, int chunkIndex, int chunkTotal) {
        Map<String, Object> metadata = sourceDocument.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(sourceDocument.getMetadata());
        String sourceDocumentId = sourceDocument.getId();

        metadata.put("source_document_id", sourceDocumentId);
        metadata.put("chunk_index", chunkIndex);
        metadata.put("chunk_total", chunkTotal);
        metadata.put("chunk_size", chunkSize);
        metadata.put("chunk_overlap", overlap);

        return Document.builder()
                .id(buildChunkId(sourceDocumentId, chunkIndex))
                .text(chunkText)
                .metadata(metadata)
                .score(sourceDocument.getScore())
                .build();
    }

    private String buildChunkId(String sourceDocumentId, int chunkIndex) {
        String parentId = StringUtils.defaultIfBlank(sourceDocumentId, "document");
        return parentId + "#chunk-" + chunkIndex;
    }
}
