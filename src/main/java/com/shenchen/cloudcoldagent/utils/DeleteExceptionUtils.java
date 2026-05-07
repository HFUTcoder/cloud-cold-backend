package com.shenchen.cloudcoldagent.utils;

import io.minio.errors.ErrorResponseException;

import java.util.List;
import java.util.Set;

/**
 * 删除异常判断工具，统一判断 MinIO/S3 删除操作中的可忽略错误。
 */
public final class DeleteExceptionUtils {

    private static final Set<String> IGNORABLE_S3_ERROR_CODES =
            Set.of("NoSuchKey", "NoSuchObject", "NoSuchBucket");

    private static final List<String> IGNORABLE_ERROR_MESSAGE_PATTERNS = List.of(
            "no such key",
            "no such object",
            "no such bucket",
            "object does not exist",
            "index_not_found_exception"
    );

    private DeleteExceptionUtils() {
    }

    /**
     * 判断删除操作异常是否为可忽略的“资源不存在”类错误。
     *
     * @param exception 删除操作抛出的异常。
     * @return 可忽略时返回 true。
     */
    public static boolean isIgnorableDeleteException(Exception exception) {
        if (exception instanceof ErrorResponseException errorResponseException) {
            String code = errorResponseException.errorResponse() == null
                    ? null
                    : errorResponseException.errorResponse().code();
            if (code != null && IGNORABLE_S3_ERROR_CODES.contains(code)) {
                return true;
            }
        }

        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return IGNORABLE_ERROR_MESSAGE_PATTERNS.stream().anyMatch(normalized::contains);
    }
}
