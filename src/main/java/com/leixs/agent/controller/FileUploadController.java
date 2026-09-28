package com.leixs.agent.controller;

import com.leixs.agent.config.FileUploadConfig;
import com.leixs.agent.dto.FileUploadRes;
import com.leixs.agent.service.VectorIndexService;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api")
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired
    private FileUploadConfig fileUploadConfig;

    @Autowired
    private VectorIndexService vectorIndexService;

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("文件不能为空");
        }

        String originalFilaName = file.getOriginalFilename();
        if (originalFilaName == null || originalFilaName.isEmpty()) {
            return ResponseEntity.badRequest().body("文件名不能为空");
        }

        String fileExtension = getFileExtension(originalFilaName);

        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }

        try {
            String uploadPath = fileUploadConfig.getPath();
            Path uploadDir = Paths.get(uploadPath).normalize();
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            Path filePath = uploadDir.resolve(originalFilaName).normalize();

            if (Files.exists(filePath)) {
                logger.info("文件已存在，将覆盖: {}", filePath);
                Files.delete(filePath);
            }

            Files.copy(file.getInputStream(), filePath);

            logger.info("文件上传成功: {}", filePath);

            boolean indexed = true;

            try {
                logger.info("开始为上传文件创建向量索引: {}", filePath);
                vectorIndexService.indexSingleFile(filePath.toString());
                logger.info("向量索引创建成功: {}", filePath);
            } catch (Exception e) {
                indexed = false;
                logger.error("向量索引创建失败: {}, 错误: {}", filePath, e.getMessage(), e);
            }

            FileUploadRes response = new FileUploadRes(
                    originalFilaName,
                    filePath.toString(),
                    file.getSize()
            );

            ApiResponse<FileUploadRes> apiResponse = new ApiResponse<>();
            apiResponse.setCode(indexed ? HttpStatus.OK.value() : HttpStatus.SERVICE_UNAVAILABLE.value());
            apiResponse.setMessage(indexed ? "success" : "文件已上传，但向量索引未完成，请稍后重试");
            apiResponse.setData(response);

            return indexed
                    ? ResponseEntity.ok(apiResponse)
                    : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(apiResponse);
        } catch (IOException e) {
            ApiResponse<String> errorResponse = new ApiResponse<>();
            logger.error("文件上传失败", e);
            errorResponse.setCode(HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.setMessage("文件上传失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }
    }

    /**
     * 统一 API 响应格式
     */
    @Setter
    @Getter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        List<String> allowedList = Arrays.asList(allowedExtensions.split(","));
        return allowedList.contains(extension.toLowerCase());
    }

    //获取文件名的**文件后缀（扩展名）**，并且全部转小写。
    private String getFileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }
}
