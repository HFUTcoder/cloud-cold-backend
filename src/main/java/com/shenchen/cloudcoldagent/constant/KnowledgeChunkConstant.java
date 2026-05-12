package com.shenchen.cloudcoldagent.constant;

/**
 * `KnowledgeChunkConstant` 类型实现。
 */
public final class KnowledgeChunkConstant {

    /**
     * 创建 `KnowledgeChunkConstant` 实例。
     */
    private KnowledgeChunkConstant() {
    }

    public static final String CHUNK_TYPE_TEXT = "TEXT";
    public static final String CHUNK_TYPE_PARENT = "PARENT";
    public static final String OBJECT_PREFIX_KNOWLEDGE = "knowledge/";

    public static final String META_USER_ID = "userId";
    public static final String META_KNOWLEDGE_ID = "knowledgeId";
    public static final String META_DOCUMENT_ID = "documentId";
    public static final String META_DOCUMENT_NAME = "documentName";
    public static final String META_OBJECT_NAME = "objectName";
    public static final String META_SOURCE = "source";
    public static final String META_FILE_NAME = "fileName";
    public static final String META_FILE_TYPE = "fileType";
    public static final String META_CONTENT_TYPE = "contentType";
    public static final String META_FILE_SIZE = "fileSize";
    public static final String META_SOURCE_DOCUMENT_ID = "source_document_id";
    public static final String META_CHUNK_ID = "chunkId";
    public static final String META_CHUNK_TYPE = "chunkType";
    public static final String META_PARENT_ID = "parentId";
    public static final String META_PARENT_TYPE = "parentType";
    public static final String META_PARENT_CHUNK_ID = "parentChunkId";
    public static final String META_IMAGE_IDS = "imageIds";
    public static final String META_PAGE_NUMBER = "pageNumber";
    public static final String META_CHUNK_INDEX = "chunk_index";
    public static final String META_CHUNK_TOTAL = "chunk_total";
    public static final String META_CHUNK_SIZE = "chunk_size";
    public static final String META_CHUNK_OVERLAP = "chunk_overlap";

    // 图片标签格式常量（PdfMultimodalProcessor 生成端与 KnowledgeServiceImpl 解析端共享）
    public static final String IMAGE_TAG_PREFIX = "<cloudcoldagent-image id=\"";
    public static final String IMAGE_TAG_SUFFIX = "\">";
    public static final String IMAGE_TAG_CLOSE = "</cloudcoldagent-image>";
    public static final String IMAGE_PLACEHOLDER_PREFIX = "[IMAGE_";
    public static final String IMAGE_PLACEHOLDER_SUFFIX = "]";
}
