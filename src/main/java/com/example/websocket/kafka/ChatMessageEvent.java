package com.example.websocket.kafka;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Kafka event payload for a chat message.
 * Produced by the server that receives the WebSocket message,
 * consumed by all server instances to broadcast to their local sessions.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageEvent implements Serializable {

    /** The authenticated sender username */
    private String sender;

    /** Target chat room */
    private String roomName;

    /** Text content (may be null for file-only messages) */
    private String content;

    /** Cloudinary / remote file URL */
    private String fileUrl;

    /** MIME type of the file (e.g. "image/png") */
    private String fileType;

    /** Original filename */
    private String fileName;

    /** Server-side timestamp at the time of message receipt */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    /**
     * ID of the originating server instance (hostname / INSTANCE_ID env var).
     * Useful for debugging multi-node fan-out.
     */
    private String originServerId;
}
