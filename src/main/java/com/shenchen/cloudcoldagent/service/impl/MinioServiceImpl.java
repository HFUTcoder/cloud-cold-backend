package com.shenchen.cloudcoldagent.service.impl;

import com.shenchen.cloudcoldagent.config.properties.MinioProperties;
import com.shenchen.cloudcoldagent.service.MinioService;
import io.minio.*;
import io.minio.http.Method;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 文件服务实现，负责 bucket 兜底创建、文件上传下载删除和预签名地址生成。
 */
@Service
public class MinioServiceImpl implements MinioService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    /**
     * 注入 MinIO 客户端和对象存储配置。
     *
     * @param minioClient MinIO 客户端。
     * @param minioProperties MinIO 配置。
     */
    public MinioServiceImpl(MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
    }

    // 确保 bucket 存在
    /**
     * 确保存储 bucket 存在，必要时自动创建并按需设置公共读策略。
     *
     * @param publicRead 是否设置为公共读。
     * @throws Exception 创建 bucket 或设置策略失败时抛出。
     */
    private void createBucketIfNotExists(boolean publicRead) throws Exception {
        String bucketName = minioProperties.getBucketName();
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());

            // 设置 bucket 策略为公共读
            if (publicRead) {
                String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + bucketName + "/*\"]}]}";
                minioClient.setBucketPolicy(
                        SetBucketPolicyArgs.builder()
                                .bucket(bucketName)
                                .config(policy)
                                .build()
                );
            }
        }
    }

    // 上传文件
    /**
     * 上传 MultipartFile 到 MinIO，并返回公开访问地址。
     *
     * @param file 上传文件。
     * @param objectName 对象名。
     * @return 文件访问地址。
     * @throws Exception 上传失败时抛出。
     */
    public String uploadFile(MultipartFile file, String objectName) throws Exception {
        createBucketIfNotExists(true);// 这里可根据你自己的情况改成false，如果改成false，需要在这个方法最后调一次getPresignedUrl
        String bucketName = minioProperties.getBucketName();
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build());
        return String.format("%s/%s/%s", minioProperties.getEndpoint(), bucketName, objectName);

    }

    /**
     * 以上传字节数组的方式写入 MinIO，并返回公开访问地址。
     */
    public String uploadFile(String objectName, byte[] content, String contentType) throws Exception {
        createBucketIfNotExists(true);
        String bucketName = minioProperties.getBucketName();
        try (InputStream stream = new ByteArrayInputStream(content)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(stream, content.length, -1)
                            .contentType(contentType)
                            .build()
            );

            return String.format("%s/%s/%s", minioProperties.getEndpoint(), bucketName, objectName);
        }
    }

    // 下载文件（返回 InputStream）
    /**
     * 下载指定对象并返回输入流。
     *
     * @param objectName 对象名。
     * @return 对象输入流。
     * @throws Exception 下载失败时抛出。
     */
    public InputStream downloadFile(String objectName) throws Exception {
        String bucketName = minioProperties.getBucketName();
        GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build());
        return response;
    }

    // 删除文件
    /**
     * 删除指定对象。
     *
     * @param objectName 对象名。
     * @throws Exception 删除失败时抛出。
     */
    public void deleteFile(String objectName) throws Exception {
        String bucketName = minioProperties.getBucketName();
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucketName)
                .object(objectName)
                .build());
    }

    // 生成临时下载链接（带签名，有效期 7 天）
    /**
     * 生成带签名的临时访问链接。
     *
     * @param objectName 对象名。
     * @return 预签名 URL。
     * @throws Exception 生成失败时抛出。
     */
    public String getPresignedUrl(String objectName) throws Exception {
        String bucketName = minioProperties.getBucketName();
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucketName)
                        .object(objectName)
                        .expiry(7, TimeUnit.DAYS)
                        .build());
    }
}
