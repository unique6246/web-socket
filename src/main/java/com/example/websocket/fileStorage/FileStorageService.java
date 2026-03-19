package com.example.websocket.fileStorage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class FileStorageService {

    private final Cloudinary cloudinary;

    public FileStorageService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    /**
     * Uploads a file to Cloudinary and returns a UploadResult
     * containing the secure CDN URL and the public_id (for deletion).
     */
    public UploadResult storeFile(MultipartFile file) {
        String originalFileName = StringUtils.cleanPath(
                Objects.requireNonNull(file.getOriginalFilename()));

        if (originalFileName.contains("..")) {
            throw new RuntimeException("Invalid file name: " + originalFileName);
        }

        // Determine folder and resource type
        String contentType = file.getContentType() != null ? file.getContentType() : "";
        String resourceType = contentType.startsWith("image/") ? "image"
                            : contentType.startsWith("video/") ? "video"
                            : "raw"; // raw = any other file type (pdf, doc, zip …)

        // Use a unique public_id so filenames never collide
        String publicId = "chat-files/" + UUID.randomUUID() + "_"
                + originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "public_id",     publicId,
                            "resource_type", resourceType,
                            "overwrite",     false
                    )
            );

            String secureUrl = (String) result.get("secure_url");
            String returnedPublicId = (String) result.get("public_id");
            return new UploadResult(secureUrl, returnedPublicId, resourceType);

        } catch (Exception ex) {
            throw new RuntimeException("Cloudinary upload failed for: " + originalFileName, ex);
        }
    }

    /**
     * Deletes a previously uploaded file from Cloudinary by its public_id.
     */
    public void deleteFile(String publicId, String resourceType) {
        try {
            cloudinary.uploader().destroy(publicId,
                    ObjectUtils.asMap("resource_type", resourceType));
        } catch (Exception ex) {
            // Log but don't fail — deletion is best-effort
            System.err.println("Cloudinary delete failed for " + publicId + ": " + ex.getMessage());
        }
    }

    /** Simple value object returned after a successful upload. */
    public record UploadResult(String fileUrl, String publicId, String resourceType) {}
}
