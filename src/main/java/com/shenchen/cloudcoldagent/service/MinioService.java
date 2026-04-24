package com.shenchen.cloudcoldagent.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface MinioService {

    public String uploadFile(MultipartFile file, String objectName) throws Exception;

    public String uploadFile(String objectName, byte[] content, String contentType) throws Exception;

    public InputStream downloadFile(String objectName) throws Exception;

    public void deleteFile(String objectName) throws Exception;

    public String getPresignedUrl(String objectName) throws Exception;
}
