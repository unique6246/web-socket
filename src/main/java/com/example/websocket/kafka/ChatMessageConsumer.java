package com.example.websocket.kafka;

import com.example.websocket.config.SocketConnectionHandler;
import com.example.websocket.model.Message;
import com.example.websocket.model.NotificationType;
import com.example.websocket.model.User;
import com.example.websocket.repo.UserRepository;
import com.example.websocket.service.ChatRoomService;
import com.example.websocket.service.NotificationService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Three @KafkaListener methods on the same two topics, each bound to a different
 * consumer group — so each message is processed independently by all three.
 *
 * ── What flows through Kafka ─────────────────────────────────────────────────
 *   MESSAGE       → produced by SocketConnectionHandler on WS text frame
 *   TYPING        → ephemeral, no DB write
 *   READ_RECEIPT  → ephemeral, no DB write
 *   PRESENCE      → produced by SocketConnectionHandler on connect/disconnect
 *
 * ── What does NOT flow through Kafka ────────────────────────────────────────
 *   MESSAGE_EDIT, MESSAGE_DELETE, REACTION, PIN
 *       → REST controllers persist synchronously then call broadcastJsonToRoom().
 *         Routing these through Kafka adds latency with no benefit.
 *   NOTIFICATION, UNREAD_COUNT, GROUP_CREATED, MESSAGE_ID_ASSIGN
 *       → Direct WebSocket pushes from onMessagePersist / NotificationService /
 *         ChatController. Never Kafka-produced.
 *
 * ── Consumer group strategy ──────────────────────────────────────────────────
 *   ws-broadcast-{instanceId}  UNIQUE per server → every instance fans out to
 *                               its local WebSocket clients (broadcast pattern)
 *   chat-persistence           SHARED across cluster → exactly one server writes
 *                               to MySQL (prevents duplicate rows)
 *   push-notifications         SHARED across cluster → exactly one server sends
 *                               mobile push (prevents duplicate FCM/APNs pushes)
 */
@Service
public class ChatMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConsumer.class);
    private static final Pattern MENTION_PATTERN = Pattern.compile("@(\\w[\\w.-]{1,49})");

    private final SocketConnectionHandler socketConnectionHandler;
    private final ChatRoomService chatRoomService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final KafkaMonitorService monitor;

    public ChatMessageConsumer(SocketConnectionHandler socketConnectionHandler,
                               ChatRoomService chatRoomService,
                               NotificationService notificationService,
                               UserRepository userRepository,
                               KafkaMonitorService monitor) {
        this.socketConnectionHandler = socketConnectionHandler;
        this.chatRoomService         = chatRoomService;
        this.notificationService     = notificationService;
        this.userRepository          = userRepository;
        this.monitor                 = monitor;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Listener 1 — WebSocket Fan-out
    //
    //  Consumer group is UNIQUE per server instance (ws-broadcast-{instanceId}).
    //  Every server receives every Kafka message and fans it out to its own
    //  locally-connected WebSocket sessions.
    //
    //  Event routing:
    //    PRESENCE      → broadcastPresenceGlobally()  (all sessions, all rooms)
    //    MESSAGE       → broadcastToLocalSessions()   (only the target room)
    //    TYPING        → broadcastToLocalSessions()   (only the target room)
    //    READ_RECEIPT  → broadcastToLocalSessions()   (only the target room)
    //    GROUP_CREATED → no broadcast needed; ChatController pushes directly
    //                    via pushToUser() after the group is created in the DB
    //    Everything else (NOTIFICATION, UNREAD_COUNT, MESSAGE_ID_ASSIGN,
    //    MESSAGE_EDIT, MESSAGE_DELETE, REACTION, PIN) never arrives here —
    //    they are direct WS pushes, not Kafka events.
    // ═══════════════════════════════════════════════════════════════════════════
    @KafkaListener(
            topics          = {KafkaConfig.TOPIC_DM, KafkaConfig.TOPIC_GROUP},
            containerFactory = "wsBroadcastContainerFactory"
    )
    public void onMessageBroadcast(ChatMessageEvent event) {
        if (event.getEventType() == null) return;

        switch (event.getEventType()) {

            case PRESENCE:
                // PRESENCE must reach ALL sessions in ALL rooms — not just one room.
                // broadcastPresenceGlobally() iterates userRoomSessions.values().
                log.debug("[Kafka→WS] PRESENCE {} → {}", event.getSender(), event.getPresenceStatus());
                socketConnectionHandler.broadcastPresenceGlobally(
                        event.getSender(), event.getPresenceStatus());
                monitor.record(KafkaMonitorService.GROUP_BROADCAST, event,
                        "broadcastPresenceGlobally() → status=" + event.getPresenceStatus()
                        + " pushed to ALL open sessions", false);
                break;

            case MESSAGE:
                log.debug("[Kafka→WS] MESSAGE from {} in room {}",
                        event.getSender(), event.getRoomName());
                socketConnectionHandler.broadcastToLocalSessions(event);
                monitor.record(KafkaMonitorService.GROUP_BROADCAST, event,
                        "broadcastToLocalSessions() → MESSAGE pushed to room ["
                        + event.getRoomName() + "]"
                        + (event.getFileUrl() != null ? " (file: " + event.getFileName() + ")" : ""),
                        false);
                break;

            case TYPING:
                log.debug("[Kafka→WS] TYPING from {} in room {}",
                        event.getSender(), event.getRoomName());
                socketConnectionHandler.broadcastToLocalSessions(event);
                monitor.record(KafkaMonitorService.GROUP_BROADCAST, event,
                        "broadcastToLocalSessions() → isTyping=" + event.getIsTyping()
                        + " pushed to room [" + event.getRoomName() + "]", false);
                break;

            case READ_RECEIPT:
                log.debug("[Kafka→WS] READ_RECEIPT from {} for msgId {}",
                        event.getSender(), event.getMessageId());
                socketConnectionHandler.broadcastToLocalSessions(event);
                monitor.record(KafkaMonitorService.GROUP_BROADCAST, event,
                        "broadcastToLocalSessions() → READ_RECEIPT for msgId="
                        + event.getMessageId() + " pushed to room ["
                        + event.getRoomName() + "]", false);
                break;

            default:
                // GROUP_CREATED and any future event types that are produced
                // as direct pushes (not Kafka) should never reach this listener.
                // Log and skip to avoid accidental room broadcasts.
                log.debug("[Kafka→WS] Skipping event type {} in broadcast listener",
                        event.getEventType());
                monitor.record(KafkaMonitorService.GROUP_BROADCAST, event,
                        "SKIPPED — " + event.getEventType()
                        + " is not a Kafka-routed broadcast event", true);
                break;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Listener 2 — Database Persistence
    //
    //  Consumer group is SHARED ("chat-persistence").
    //  Exactly ONE server instance processes each message → no duplicate DB rows.
    //
    //  Only MESSAGE events require a DB write.
    //  TYPING, READ_RECEIPT, PRESENCE are ephemeral — intentionally not persisted.
    //  MESSAGE_EDIT, MESSAGE_DELETE, REACTION, PIN are handled by REST controllers
    //  synchronously — they never flow through Kafka.
    //
    //  After saving a MESSAGE this listener also:
    //    1. Pushes MESSAGE_ID_ASSIGN so frontends can attach the real DB id
    //    2. Pushes UNREAD_COUNT to every member who is NOT the sender
    //       — but only for GROUP rooms. DM recipients already get a
    //         NOTIFICATION event (which carries unreadCount) so sending
    //         UNREAD_COUNT too would double-count their badge.
    //    3. Creates an in-app NOTIFICATION for the DM recipient (DM rooms only)
    //    4. Creates MENTION notifications for any @username in the content
    //       — deduplicated, sender excluded, DM recipient not double-notified
    // ═══════════════════════════════════════════════════════════════════════════
    @KafkaListener(
            topics          = {KafkaConfig.TOPIC_DM, KafkaConfig.TOPIC_GROUP},
            containerFactory = "chatPersistenceContainerFactory"
    )
    public void onMessagePersist(ChatMessageEvent event) {
        if (event.getEventType() == null) return;

        if (event.getEventType() != ChatMessageEvent.EventType.MESSAGE) {
            // All non-MESSAGE event types are ephemeral or handled via REST —
            // nothing to persist. Record for the monitor and exit.
            monitor.record(KafkaMonitorService.GROUP_PERSISTENCE, event,
                    "SKIPPED — " + event.getEventType()
                    + " is ephemeral and requires no DB write", true);
            return;
        }

        try {
            log.debug("[Kafka→DB] Persisting MESSAGE from {} in room {}",
                    event.getSender(), event.getRoomName());

            // ── Step 1: Save the message to MySQL ─────────────────────────────
            Message saved = chatRoomService.saveMessage(
                    event.getRoomName(),
                    event.getSender(),
                    event.getContent(),
                    event.getFileUrl(),
                    event.getFileType(),
                    event.getFileName(),
                    event.getReplyToMessageId());

            StringBuilder actions = new StringBuilder();
            actions.append("INSERT messages → id=").append(saved != null ? saved.getId() : "?");

            // ── Step 2: Push MESSAGE_ID_ASSIGN to the room ────────────────────
            // The frontend rendered an optimistic bubble without a DB id.
            // Broadcasting the assigned id lets the browser patch data-id so
            // react / edit / delete / reply all work immediately.
            if (saved != null && saved.getId() != null) {
                try {
                    JSONObject idAssign = new JSONObject();
                    idAssign.put("eventType", "MESSAGE_ID_ASSIGN");
                    idAssign.put("sender",    event.getSender());
                    idAssign.put("roomName",  event.getRoomName());
                    idAssign.put("messageId", saved.getId());
                    idAssign.put("timestamp", event.getTimestamp() != null
                            ? event.getTimestamp().toString() : "");
                    socketConnectionHandler.broadcastJsonToRoom(event.getRoomName(), idAssign);
                    actions.append(" | broadcastJsonToRoom(MESSAGE_ID_ASSIGN id=")
                           .append(saved.getId()).append(")");
                } catch (Exception e) {
                    log.warn("[Consumer] Failed to push MESSAGE_ID_ASSIGN: {}", e.getMessage());
                }
            }

            // ── Step 3: Unread count + notifications ──────────────────────────
            boolean isDm = event.getRoomName() != null
                    && event.getRoomName().startsWith("dm__");

            if (isDm) {
                // DM room:
                //   • Create a NOTIFICATION for the recipient. NotificationService
                //     pushes the notification via pushToUser() and includes the
                //     authoritative unreadCount in the payload — so the badge is
                //     set to the exact DB value, not incremented blindly.
                //   • Do NOT also push UNREAD_COUNT — that would increment the
                //     badge a second time causing a mismatch.
                String dmRecipient = createDmNotification(event.getRoomName(), event.getSender());
                if (dmRecipient != null) {
                    actions.append(" | createNotification(MESSAGE) → ").append(dmRecipient);
                }
            } else {
                // Group room:
                //   • No per-message NOTIFICATION (would be too noisy for groups).
                //   • Push UNREAD_COUNT to every member except the sender so
                //     their sidebar badge increments live.
                pushUnreadCountToRoomMembers(event.getRoomName(), event.getSender(),
                        event.getContent());
                actions.append(" | pushToUser(UNREAD_COUNT) → all group members except sender");
            }

            // ── Step 4: @Mention notifications ───────────────────────────────
            // Scan content for @username patterns. Create a MENTION notification
            // for each valid mentioned user, with deduplication:
            //   • Skip the sender (can't mention yourself meaningfully)
            //   • Skip the DM recipient if they were already notified in Step 3
            //     (avoids double notification on DMs containing @otherUser)
            if (event.getContent() != null && event.getContent().contains("@")) {
                String dmRecipient = isDm
                        ? parseDmOtherUser(event.getRoomName(), event.getSender())
                        : null;
                int mentionCount = createMentionNotifications(
                        event.getContent(),
                        event.getSender(),
                        event.getRoomName(),
                        dmRecipient);          // excluded from mention notifications
                if (mentionCount > 0) {
                    actions.append(" | createNotification(MENTION) → ")
                           .append(mentionCount).append(" user(s)");
                }
            }

            monitor.record(KafkaMonitorService.GROUP_PERSISTENCE, event,
                    actions.toString(), false);

        } catch (Exception e) {
            log.error("[Kafka→DB] Failed to persist MESSAGE from {} in room {}: {}",
                    event.getSender(), event.getRoomName(), e.getMessage(), e);
            monitor.record(KafkaMonitorService.GROUP_PERSISTENCE, event,
                    "ERROR persisting message: " + e.getMessage(), false);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Listener 3 — Push Notifications (mobile / external — stub)
    //
    //  Consumer group is SHARED ("push-notifications").
    //  Exactly ONE server sends the mobile push per message.
    //
    //  Currently a no-op stub. To integrate Firebase FCM or Apple APNs,
    //  implement the push logic here. The shared group guarantees no duplicate
    //  pushes across a multi-instance cluster.
    // ═══════════════════════════════════════════════════════════════════════════
    @KafkaListener(
            topics          = {KafkaConfig.TOPIC_DM, KafkaConfig.TOPIC_GROUP},
            containerFactory = "pushNotificationContainerFactory"
    )
    public void onMessagePushNotify(ChatMessageEvent event) {
        if (event.getEventType() != ChatMessageEvent.EventType.MESSAGE) {
            monitor.record(KafkaMonitorService.GROUP_PUSH, event,
                    "SKIPPED — mobile push only for MESSAGE; "
                    + event.getEventType() + " is ephemeral", true);
            return;
        }
        log.debug("[Kafka→Push] Push stub — sender={} room={}",
                event.getSender(), event.getRoomName());
        // TODO: FCM / APNs integration goes here
        monitor.record(KafkaMonitorService.GROUP_PUSH, event,
                "FCM/APNs stub invoked — room=[" + event.getRoomName()
                + "] sender=[" + event.getSender() + "] (TODO: send mobile push)",
                false);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Private helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Pushes an UNREAD_COUNT event to every member of a GROUP room except the sender.
     * Each push carries the snippet so the sidebar preview updates instantly.
     *
     * NOT used for DM rooms — DM recipients receive a NOTIFICATION event from
     * {@link #createDmNotification} which already carries the authoritative
     * unreadCount field. Sending both would double-increment the bell badge.
     */
    private void pushUnreadCountToRoomMembers(String roomName, String sender, String content) {
        try {
            String snippet = (content != null && !content.isBlank())
                    ? (content.length() > 60 ? content.substring(0, 60) + "…" : content)
                    : "";

            final JSONObject payload = new JSONObject();
            payload.put("eventType",   "UNREAD_COUNT");
            payload.put("roomName",    roomName);
            payload.put("increment",   1);
            payload.put("lastMessage", snippet);
            payload.put("sender",      sender);

            List<String> members = chatRoomService.getMemberUsernames(roomName);
            for (String username : members) {
                if (!username.equals(sender)) {
                    socketConnectionHandler.pushToUser(username, payload);
                }
            }
        } catch (Exception e) {
            log.warn("[Consumer] Failed to push UNREAD_COUNT for room {}: {}",
                    roomName, e.getMessage());
        }
    }

    /**
     * Creates an in-app NOTIFICATION for the other participant in a DM room.
     * NotificationService saves the notification and then calls
     * {@code pushToUser()} with the authoritative {@code unreadCount} from
     * the DB — so the recipient's bell badge is set to the exact correct value.
     *
     * @return the recipient username if a notification was created, null otherwise
     */
    private String createDmNotification(String roomName, String sender) {
        try {
            String otherUser = parseDmOtherUser(roomName, sender);
            if (otherUser == null) return null;
            User recipient = userRepository.findByUsername(otherUser);
            if (recipient == null) return null;
            notificationService.createNotification(
                    recipient,
                    NotificationType.MESSAGE,
                    null,
                    sender + " sent you a message",
                    roomName);   // ← pass roomName so the WS push includes it
            return otherUser;
        } catch (Exception e) {
            log.warn("[Consumer] Failed to create DM notification for room {}: {}",
                    roomName, e.getMessage());
            return null;
        }
    }

    /**
     * Parses the other user's username out of a DM room name.
     * Room name format: "dm__userA__userB" (alphabetically sorted at creation).
     *
     * @param roomName the DM room name
     * @param sender   the username to exclude
     * @return the other participant's username, or null if parsing fails
     */
    private String parseDmOtherUser(String roomName, String sender) {
        if (roomName == null || !roomName.startsWith("dm__")) return null;
        String[] parts = roomName.substring(4).split("__");
        for (String part : parts) {
            if (!part.isEmpty() && !part.equals(sender)) return part;
        }
        return null;
    }

    /**
     * Scans message content for @mentions and creates a MENTION notification
     * for each valid recipient. Rules:
     *   • Sender never notified (can't @mention yourself)
     *   • {@code alreadyNotified} is skipped (DM recipient already has a MESSAGE
     *     notification from {@link #createDmNotification} — avoids duplicate)
     *   • Multiple @mentions of the same user → single notification (Set dedup)
     *   • Mentions of non-existent usernames → silently ignored
     *
     * @param content           raw message text
     * @param sender            sender username to exclude
     * @param roomName          room for notification content
     * @param alreadyNotified   username already notified this message (nullable)
     * @return count of mention notifications actually created
     */
    private int createMentionNotifications(String content, String sender,
                                           String roomName, String alreadyNotified) {
        int count = 0;
        try {
            Set<String> notified = new HashSet<>();
            // Pre-seed with users we must not notify again
            notified.add(sender);
            if (alreadyNotified != null) notified.add(alreadyNotified);

            Matcher matcher = MENTION_PATTERN.matcher(content);
            while (matcher.find()) {
                String mentioned = matcher.group(1);
                if (notified.add(mentioned)) {        // add() returns false if already present
                    User recipient = userRepository.findByUsername(mentioned);
                    if (recipient != null) {
                        notificationService.createNotification(
                                recipient,
                                NotificationType.MENTION,
                                null,
                                sender + " mentioned you in " + roomName);
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Consumer] Failed to create mention notifications in room {}: {}",
                    roomName, e.getMessage());
        }
        return count;
    }
}
