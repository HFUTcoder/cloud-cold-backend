package com.shenchen.cloudcoldagent.model.entity.record.support;

/**
 * `NormalizationResult` 记录对象。
 */
public record NormalizationResult(String normalizedJson, boolean valid, String errorMessage) {
}
