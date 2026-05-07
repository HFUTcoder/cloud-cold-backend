package com.shenchen.cloudcoldagent.model.entity.record.knowledge;

/**
 * 创建 `ExtractedDocumentImage` 实例。
 *
 * @param imageIndex imageIndex 参数。
 * @param pageNumber pageNumber 参数。
 * @param bytes bytes 参数。
 * @param contentType contentType 参数。
 * @param description description 参数。
 */
/**
 * `ExtractedDocumentImage` 记录对象。
 */
public record ExtractedDocumentImage(
        Integer imageIndex,
        Integer pageNumber,
        byte[] bytes,
        String contentType,
        String description
) {
}
