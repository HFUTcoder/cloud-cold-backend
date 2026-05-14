package com.shenchen.cloudcoldagent.constant;

/**
 * 知识库分块相关常量。
 */
public interface KnowledgeChunkConstant {

    String CHUNK_TYPE_TEXT = "TEXT";
    String CHUNK_TYPE_PARENT = "PARENT";
    String OBJECT_PREFIX_KNOWLEDGE = "knowledge/";

    String META_USER_ID = "userId";
    String META_KNOWLEDGE_ID = "knowledgeId";
    String META_DOCUMENT_ID = "documentId";
    String META_DOCUMENT_NAME = "documentName";
    String META_OBJECT_NAME = "objectName";
    String META_SOURCE = "source";
    String META_FILE_NAME = "fileName";
    String META_FILE_TYPE = "fileType";
    String META_CONTENT_TYPE = "contentType";
    String META_FILE_SIZE = "fileSize";
    String META_SOURCE_DOCUMENT_ID = "source_document_id";
    String META_CHUNK_ID = "chunkId";
    String META_CHUNK_TYPE = "chunkType";
    String META_PARENT_ID = "parentId";
    String META_PARENT_TYPE = "parentType";
    String META_PARENT_CHUNK_ID = "parentChunkId";
    String META_IMAGE_IDS = "imageIds";
    String META_PAGE_NUMBER = "pageNumber";
    String META_CHUNK_INDEX = "chunk_index";
    String META_CHUNK_TOTAL = "chunk_total";
    String META_CHUNK_SIZE = "chunk_size";
    String META_CHUNK_OVERLAP = "chunk_overlap";

    // 图片标签格式常量（PdfMultimodalProcessor 生成端与 KnowledgeServiceImpl 解析端共享）
    String IMAGE_TAG_PREFIX = "<cloudcoldagent-image id=\"";
    String IMAGE_TAG_SUFFIX = "\">";
    String IMAGE_TAG_CLOSE = "</cloudcoldagent-image>";
    String IMAGE_PLACEHOLDER_PREFIX = "[IMAGE_";
    String IMAGE_PLACEHOLDER_SUFFIX = "]";
}
