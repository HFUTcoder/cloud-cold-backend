package com.shenchen.cloudcoldagent.controller;

import com.shenchen.cloudcoldagent.service.MinioService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * `FileController` 类型实现。
 */
@RestController
@RequestMapping("/files")
@Slf4j
public class FileController {

    private final MinioService minioService;

    /**
     * 创建 `FileController` 实例。
     *
     * @param minioService minioService 参数。
     */
    public FileController(MinioService minioService) {
        this.minioService = minioService;
    }

    /**
     * 处理 `upload File` 对应逻辑。
     *
     * @param file file 参数。
     * @return 返回处理结果。
     */
    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            String objectName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            minioService.uploadFile(file, objectName);
            return ResponseEntity.ok("上传成功: " + objectName);
        } catch (Exception e) {
            log.error("文件上传失败，fileName={}, message={}",
                    file == null ? null : file.getOriginalFilename(),
                    e.getMessage(),
                    e);
            return ResponseEntity.status(500).body("上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取 `get Download Url` 对应结果。
     *
     * @param objectName objectName 参数。
     * @return 返回处理结果。
     */
    @GetMapping("/download-url/{objectName}")
    public ResponseEntity<String> getDownloadUrl(@PathVariable String objectName) {
        try {
            String url = minioService.getPresignedUrl(objectName);
            return ResponseEntity.ok(url);
        } catch (Exception e) {
            log.error("生成下载链接失败，objectName={}, message={}", objectName, e.getMessage(), e);
            return ResponseEntity.status(500).body("生成下载链接失败");
        }
    }

}
