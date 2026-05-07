package com.shenchen.cloudcoldagent.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

/**
 * `MinioService` 接口定义。
 */
public interface MinioService {

    /**
     * 处理 `upload File` 对应逻辑。
     *
     * @param file file 参数。
     * @param objectName objectName 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    public String uploadFile(MultipartFile file, String objectName) throws Exception;

    /**
     * 处理 `upload File` 对应逻辑。
     *
     * @param objectName objectName 参数。
     * @param content content 参数。
     * @param contentType contentType 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    public String uploadFile(String objectName, byte[] content, String contentType) throws Exception;

    /**
     * 处理 `download File` 对应逻辑。
     *
     * @param objectName objectName 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    public InputStream downloadFile(String objectName) throws Exception;

    /**
     * 删除 `delete File` 对应内容。
     *
     * @param objectName objectName 参数。
     * @throws Exception 异常信息。
     */
    public void deleteFile(String objectName) throws Exception;

    /**
     * 获取 `get Presigned Url` 对应结果。
     *
     * @param objectName objectName 参数。
     * @return 返回处理结果。
     * @throws Exception 异常信息。
     */
    public String getPresignedUrl(String objectName) throws Exception;
}
