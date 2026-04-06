package com.example.websocket.kafka;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Kafka event payload for a chat message and all real-time events.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageEvent implements Serializable {

    /** Type of event — governs routing in consumer and UI */
    private EventType eventType = EventType.MESSAGE;

    /** The authenticated sender username */
    private String sender;

    /** Target chat room */
    private String roomName;

    /** Text content (may be null for file-only messages or non-MESSAGE events) */
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

    /** ID of the originating server instance */
    private String originServerId;

    // ── Fields used by non-MESSAGE event types ───────────────────────

    /** DB message ID (used for REACTION, MESSAGE_EDIT, MESSAGE_DELETE, PIN) */
    private Long messageId;

    /** Emoji (used for REACTION events) */
    private String reactionEmoji;

    /** Whether user is currently typing (used for TYPING events) */
    private Boolean isTyping;

    /** Presence status string, e.g. "ONLINE", "OFFLINE" (used for PRESENCE events) */
    private String presenceStatus;

    /** Reply-to message ID (used when sending a reply) */
    private Long replyToMessageId;

    /** Notification recipient username (used for NOTIFICATION events) */
    private String recipientUsername;

    /** Notification ID (used for NOTIFICATION events) */
    private Long notificationId;

    /** Carries the group display name for GROUP_CREATED events */
    private String displayName;

    /** Convenience constructor for a basic chat message (backward compat) */
    public ChatMessageEvent(String sender, String roomName, String content,
                             String fileUrl, String fileType, String fileName,
                             LocalDateTime timestamp, String originServerId) {
        this.eventType = EventType.MESSAGE;
        this.sender = sender;
        this.roomName = roomName;
        this.content = content;
        this.fileUrl = fileUrl;
        this.fileType = fileType;
        this.fileName = fileName;
        this.timestamp = timestamp;
        this.originServerId = originServerId;
    }

    public enum EventType {
        MESSAGE,
        TYPING,
        READ_RECEIPT,
        PRESENCE,
        REACTION,
        MESSAGE_EDIT,
        MESSAGE_DELETE,
        PIN,
        NOTIFICATION,
        UNREAD_COUNT,
        GROUP_CREATED
    }
}
