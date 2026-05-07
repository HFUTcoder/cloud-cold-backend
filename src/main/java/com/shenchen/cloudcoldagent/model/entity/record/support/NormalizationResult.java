package com.shenchen.cloudcoldagent.model.entity.record.support;

/**
 * 创建 `NormalizationResult` 实例。
 *
 * @param normalizedJson normalizedJson 参数。
 * @param valid valid 参数。
 * @param errorMessage errorMessage 参数。
 */
/**
 * `NormalizationResult` 记录对象。
 */
public record NormalizationResult(String normalizedJson, boolean valid, String errorMessage) {
}
