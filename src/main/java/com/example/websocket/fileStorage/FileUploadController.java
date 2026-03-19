package com.example.websocket.fileStorage;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private final FileStorageService fileStorageService;

    public FileUploadController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file selected."));
        }
        try {
            FileStorageService.UploadResult result = fileStorageService.storeFile(file);
            return ResponseEntity.ok(Map.of(
                    "fileUrl",      result.fileUrl(),        // Cloudinary CDN HTTPS URL
                    "fileName",     file.getOriginalFilename() != null ? file.getOriginalFilename() : "file",
                    "publicId",     result.publicId(),       // needed if you want to delete later
                    "resourceType", result.resourceType(),
                    "type",         file.getContentType() != null ? file.getContentType() : ""
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "File upload failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/delete")
    public ResponseEntity<Map<String, String>> deleteFile(
            @RequestParam("publicId") String publicId,
            @RequestParam(value = "resourceType", defaultValue = "raw") String resourceType) {
        try {
            fileStorageService.deleteFile(publicId, resourceType);
            return ResponseEntity.ok(Map.of("message", "File deleted successfully."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "File deletion failed: " + e.getMessage()));
        }
    }
}
