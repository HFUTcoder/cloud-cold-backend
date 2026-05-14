package com.shenchen.cloudcoldagent.service.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * `MinioService` 接口定义。
 */
public interface MinioService {

    String uploadFile(MultipartFile file, String objectName) throws Exception;

    String uploadFile(String objectName, byte[] content, String contentType) throws Exception;

    InputStream downloadFile(String objectName) throws Exception;

    void deleteFile(String objectName) throws Exception;

    String getPresignedUrl(String objectName) throws Exception;
}
