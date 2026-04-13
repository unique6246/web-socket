# Part II — Complete Internal Request Lifecycle

> **Last updated:** April 2026 — reflects current codebase state.
>
> This section traces every system interaction from the frontend keystroke to the final database commit and WebSocket push — including the exact file, method, data structure, and sync/async classification at every hop.

---

## Table of Contents (Part II)

1. [Entry Point — Frontend Request](#ii1-entry-point--frontend-request)
2. [Backend Handling — Security + Controller + Service](#ii2-backend-handling--security--controller--service)
3. [Kafka Producer Flow](#ii3-kafka-producer-flow)
4. [Kafka Topic and Partition Usage](#ii4-kafka-topic-and-partition-usage)
5. [Consumer Processing Logic](#ii5-consumer-processing-logic)
6. [Database Updates](#ii6-database-updates)
7. [Real-Time Propagation via WebSocket](#ii7-real-time-propagation-via-websocket)
8. [End-to-End Flow Diagrams](#ii8-end-to-end-flow-diagrams)
9. [Event-Driven Flow Explanation](#ii9-event-driven-flow-explanation)

---

## II.1 Entry Point — Frontend Request

All user interactions originate from the browser's vanilla JS files:
- `static/chat.html` + `script.js` — chat UI, WebSocket client, sidebar real-time updates
- `static/login.html` / `register.html` / `profile.html` / `dashboard.html` — form pages
- `static/kafka-monitor.html` — Kafka event observability (ADMIN only)

### Two Distinct Entry Channels

#### Channel A — REST over HTTP

Every form submission, button click, or API call is a plain `fetch()` with the `HttpOnly` cookie automatically attached by the browser.

```
User types a message and presses Enter
  ↓
script.js: ws.send(JSON.stringify({
    sender:    currentUser,
    room:      currentRoom,
    message:   "Hello everyone",
    eventType: "MESSAGE"
}))
  ↓
[WebSocket frame → Spring Boot /ws endpoint]
```

```
User clicks "React 👍" on a message
  ↓
script.js: fetch('/api/messages/42/react', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ emoji: '👍' })
    // AUTH_TOKEN cookie is attached automatically by the browser
})
  ↓
[HTTP POST → Spring Boot /api/messages/{id}/react]
```

#### Channel B — WebSocket (ws://)

Two types of WebSocket connections exist:

**Chat room connection** — opened when entering a room:
```
Page load → chat.html
  ↓
script.js: fetch('/api/auth/ws-ticket', { method: 'POST' })
           .then(r => r.json())
           .then(({ ticket }) => {
               ws = new WebSocket(
                   `ws://localhost:8080/ws?ticket=${ticket}&roomName=${room}`
               )
           })
```

**Sidebar connection** — opened once on page load for real-time sidebar updates:
```
Page load → chat.html
  ↓
script.js: fetch('/api/auth/ws-ticket', { method: 'POST' })
           .then(r => r.json())
           .then(({ ticket }) => {
               sidebarWs = new WebSocket(
                   `ws://localhost:8080/ws?ticket=${ticket}&roomName=__sidebar__`
               )
               // Receives: GROUP_CREATED, UNREAD_COUNT, NOTIFICATION, PRESENCE
               // Never sends any frames — receive-only utility channel
           })
```

### What the Frontend Sends

| Action | Channel | Payload |
|--------|---------|---------|
| Login | HTTP POST `/api/auth/login` | `{ username, password }` |
| Register | HTTP POST `/api/auth/register` | `{ username, password, email }` |
| Send chat message | WebSocket frame | `{ sender, room, message, eventType:"MESSAGE" }` |
| Typing indicator | WebSocket frame | `{ sender, room, isTyping:true, eventType:"TYPING" }` |
| Read receipt | WebSocket frame | `{ sender, room, messageId, eventType:"READ_RECEIPT" }` |
| Edit message | HTTP PATCH `/api/messages/{id}` | `{ content }` |
| Delete message | HTTP DELETE `/api/messages/{id}` | — |
| Add reaction | HTTP POST `/api/messages/{id}/react` | `{ emoji }` |
| Remove reaction | HTTP DELETE `/api/messages/{id}/react/{emoji}` | — |
| Pin message | HTTP POST `/api/messages/{id}/pin` | — |
| Friend request | HTTP POST `/api/friends/request/{username}` | — |
| Upload file | HTTP POST `/api/files/upload` | `MultipartFile` |
| Get WS ticket | HTTP POST `/api/auth/ws-ticket` | — |
| Create group | HTTP POST `/api/chat/rooms/group` | `{ groupName, members[] }` |
| Start DM | HTTP POST `/api/chat/rooms/dm/{username}` | — |
| Add password (OAuth user) | HTTP POST `/api/auth/add-password` | `{ newPassword }` |

---

## II.2 Backend Handling — Security + Controller + Service

### Step 1 — Security Filter (`JwtAuthFilter.java`)

**File:** `config/JwtAuthFilter.java`
**Type:** `OncePerRequestFilter` — runs on EVERY HTTP request before any controller

```
Incoming HTTP Request
  ↓
JwtAuthFilter.doFilterInternal(request, response, chain)

  ① jwtService.extractToken(request)
       → loops request.getCookies()
       → finds cookie with name "AUTH_TOKEN"
       → returns token String (or null if no cookie)

  ② jwtService.validateToken(token)
       → tokenBlacklistService.isBlacklisted(token)
           File: JWT/TokenBlacklistService.java
           Structure: ConcurrentHashMap<String, Date>  { token → expiryDate }
           Returns: true if logout already called
       → jwtUtil.extractUsername(token)
           File: JWT/JwtUtil.java
           Parses HMAC-SHA256 JWT → Claims.getSubject()
       → userRepository.existsByUsername(username)   [DB read, sync]
       → jwtUtil.isTokenExpired(token)
           Checks Claims.getExpiration() < new Date()

  ③ jwtService.getAuthenticationFromToken(token)
       → authService.loadUserByUsername(username)
           File: service/AuthService.java
           → userRepository.findByUsername(username)  [DB read]
           → builds List<GrantedAuthority> from user.getRoles()
           → returns Spring UserDetails
       → new UsernamePasswordAuthenticationToken(userDetails, null, authorities)

  ④ SecurityContextHolder.getContext().setAuthentication(auth)

  ⑤ chain.doFilter(request, response)
       → request continues to controller

Data Structures at this step:
  - Token: plain String (JWT compact form)
  - Claims: io.jsonwebtoken.Claims { sub, roles[], jti, iat, exp }
  - Authentication: UsernamePasswordAuthenticationToken
  - SecurityContext: ThreadLocal<SecurityContext> (per HTTP thread)
```

**Sync/Async:** Fully **synchronous**.

---

### Step 2 — WebSocket Handshake (`WebSocketConfig.java`)

**File:** `config/WebSocketConfig.java`
**Type:** `HandshakeInterceptor.beforeHandshake()`

This path is taken INSTEAD of JwtAuthFilter — WebSocket upgrade requests bypass the JWT filter and use the ticket system.

```
GET /ws?ticket=abc123&roomName=general   (HTTP Upgrade request)
  ↓
HandshakeInterceptor.beforeHandshake()

  ① parseQueryParams(request.getURI().getQuery())
       → splits "ticket=abc123&roomName=general"
       → returns Map<String, String>

  ② wsTicketService.consumeTicket(ticket)
       File: JWT/WsTicketService.java
       Structure: ConcurrentHashMap<String, TicketData>
                  { ticketString → TicketData(username, expiresAt) }
       → tickets.remove(ticket)        // ONE-TIME: removed immediately
       → Instant.now().isAfter(expiresAt)?  → null (expired)
       → returns username String

  ③ if (username == null) → response.setStatus(401) → return false (reject)

  ④ attributes.put("username", username)   // stored on WS session
     attributes.put("roomName", roomName)

  ⑤ return true → WebSocket upgrade proceeds

Data Structures:
  - WS query params: Map<String, String>
  - TicketData: record { String username, Instant expiresAt }
  - WS session attributes: Map<String, Object>

Note: roomName can be "__sidebar__" for the sidebar utility channel.
      The handshake logic is identical — the distinction is handled
      in afterConnectionEstablished().
```

**Sync/Async:** Fully **synchronous**.

---

### Step 3 — Controller Layer

#### Path A: Chat Message (WebSocket)

**File:** `config/SocketConnectionHandler.java`
**Method:** `handleTextMessage(session, message)`

```
WebSocket text frame arrives
  ↓
handleTextMessage(WebSocketSession session, TextMessage message)

  ① Check: is this a sidebar connection?
       sessionRoom = session.getAttributes().get("roomName")
       if SIDEBAR_ROOM.equals(sessionRoom) → return  // receive-only, ignore all frames

  ② new JSONObject(message.getPayload())
       Parses raw JSON string into org.json.JSONObject

  ③ sender   = session.getAttributes().get("username")   // trusted, from ticket auth
     roomName = json.optString("room", null)

  ④ Security guard:
     session.getAttributes().get("roomName") must equal json["room"]
     → If mismatch: log warning, return silently (prevents room spoofing)

  ⑤ eventType = ChatMessageEvent.EventType.valueOf(json.optString("eventType", "MESSAGE"))

  ⑥ Build ChatMessageEvent POJO:
       event.setEventType(eventType)
       event.setSender(sender)
       event.setRoomName(roomName)
       event.setTimestamp(LocalDateTime.now())
       + type-specific fields:
         TYPING       → isTyping (boolean)
         READ_RECEIPT → messageId (Long)
         MESSAGE      → content, fileUrl, fileType, fileName, replyToMessageId

  ⑦ chatMessageProducer.send(event)
       → [proceeds to Kafka Producer — see II.3]
```

#### Path B: REST Mutation (Edit / Delete / React / Pin)

**File:** `controller/MessageController.java`
**Methods:** `editMessage()`, `deleteMessage()`, `addReaction()`, `pinMessage()`

```
HTTP PATCH /api/messages/{id}   { content: "new text" }
  ↓
MessageController.editMessage(id, body, request)

  ① extractUsername(request)
       → jwtUtil.extractUsername(jwtService.extractToken(request))
       → reads from AUTH_TOKEN cookie → parses JWT → returns username String

  ② messageService.editMessage(id, username, newContent.trim())
       File: service/MessageService.java
       [DB update — see II.6]

  ③ Build JSONObject for broadcast:
       { eventType:"MESSAGE_EDIT", messageId:id, content:newContent,
         roomName:roomName, sender:username, timestamp:... }

  ④ socketHandler.broadcastJsonToRoom(roomName, ws)
       [Direct WS push — see II.7, bypasses Kafka entirely]

  ⑤ return ResponseEntity.ok(dto)
```

#### Path C: File Upload

**File:** `fileStorage/FileStorageService.java`

```
HTTP POST /api/files/upload   (MultipartFile)
  ↓
FileStorageService.storeFile(file)

  ① Determine resource type from MIME:
       "image/*" → "image"
       "video/*" → "video"
       else      → "raw"   (pdf, doc, zip …)

  ② publicId = "chat-files/" + UUID + "_" + sanitizedFileName

  ③ cloudinary.uploader().upload(file.getBytes(), options)
       [Synchronous HTTPS to Cloudinary CDN]

  ④ Returns UploadResult { secureUrl, publicId, resourceType }

  ⑤ Controller returns { fileUrl, fileType, fileName }
       → Frontend includes fileUrl in next WebSocket message
```

**Sync/Async:** File upload is **synchronous** (blocks until Cloudinary responds).

#### Path D: Group Creation (REST + direct WS push)

**File:** `controller/ChatController.java`
**Method:** `createGroup()`

```
HTTP POST /api/chat/rooms/group  { groupName, members: ["bob", "carol"] }
  ↓
ChatController.createGroup(body, request)

  ① extractUsername(request) → currentUser (e.g. "alice")

  ② memberSet = [currentUser, ...members]   (creator is always included)

  ③ chatRoomService.createGroupRoom(groupName, memberSet, currentUser)
       File: service/ChatRoomService.java
       → if name already taken → 409 CONFLICT
       → INSERT chat_rooms (type=GROUP)
       → INSERT chat_room_user for each member
           creator: isGroupAdmin = true
           others:  isGroupAdmin = false

  ④ Build GROUP_CREATED JSONObject:
       { eventType: "GROUP_CREATED", roomName, displayName:groupName, createdBy:currentUser }

  ⑤ For each member in memberSet:
       notification.put("groupRole", member.equals(currentUser) ? "ADMIN" : "MEMBER")
       socketHandler.pushToUser(member, notification)
       → hits userSessions[member] → sends to ALL their open sessions
         (both chat room sessions AND sidebar session receive it)

  ← 200 { roomName, type:"GROUP", groupRole:"ADMIN" }
```

---

### Step 4 — Service Layer

| Service | File | Responsibility |
|---------|------|---------------|
| `AuthService` | `service/AuthService.java` | UserDetailsService, register, lock/unlock accounts, add-password for OAuth users |
| `ChatRoomService` | `service/ChatRoomService.java` | Create rooms, save messages, member management, last-message preview |
| `MessageService` | `service/MessageService.java` | Edit, delete, pin, paginate messages |
| `ReactionService` | `service/ReactionService.java` | Add/remove emoji reactions, idempotency check |
| `NotificationService` | `service/NotificationService.java` | Create + persist + push notifications via WS (after TX commit) |
| `FriendshipService` | `service/FriendshipService.java` | Friend request CRUD → triggers notifications |
| `UserProfileService` | `service/UserProfileService.java` | Update profile, manual status override, setOnline/setOffline/forceOffline |
| `EmailService` | `service/EmailService.java` | Send HTML emails via SMTP (`@Async`) |
| `OAuth2UserService` | `service/OAuth2UserService.java` | Used by Spring Security to load OAuth2 user details during provider handshake |
| `KafkaMonitorService` | `kafka/KafkaMonitorService.java` | Record and expose Kafka consumer events for observability |

---

## II.3 Kafka Producer Flow

**File:** `kafka/ChatMessageProducer.java`

```
chatMessageProducer.send(ChatMessageEvent event)

  ① event.setOriginServerId(instanceId)
       instanceId = "${server.instance.id:server-1}"  from application.properties
       → stamps which server instance produced this event (for debugging)

  ② Topic routing:
       String topic = KafkaConfig.topicForRoom(event.getRoomName())
       →  roomName.startsWith("dm__")  → "chat-messages-dm"
       →  anything else                → "chat-messages-group"

  ③ kafkaTemplate.send(topic, event.getRoomName(), event)
       Key:   event.getRoomName()    (e.g. "general", "dm__alice__bob")
       Value: ChatMessageEvent       serialized as JSON via JsonSerializer

  ④ CompletableFuture<SendResult> handling:
       future.whenComplete((result, ex) -> {
           if (ex != null)  → log.error(...)
           else             → log.debug(partition, offset, room, sender)
       })
       → Non-blocking callback

Producer Configuration (KafkaConfig.java):
  ACKS_CONFIG         = "all"       → strongest durability (waits for all in-sync replicas)
  RETRIES_CONFIG      = 3           → retry transient failures
  ENABLE_IDEMPOTENCE  = true        → prevents duplicate messages on retry
  KEY_SERIALIZER      = StringSerializer
  VALUE_SERIALIZER    = JsonSerializer   (Jackson)
```

**Sync/Async:** `kafkaTemplate.send()` returns a `CompletableFuture` — **non-blocking/async**.

### Data Structure Passed Through Kafka

```java
// kafka/ChatMessageEvent.java  (implements Serializable)
ChatMessageEvent {
    EventType eventType;       // MESSAGE | TYPING | READ_RECEIPT | PRESENCE
                               // REACTION | MESSAGE_EDIT | MESSAGE_DELETE
                               // PIN | NOTIFICATION | UNREAD_COUNT | GROUP_CREATED
    String sender;             // authenticated username (never from client payload)
    String roomName;           // "general" or "dm__alice__bob"
    String content;            // text (MESSAGE, MESSAGE_EDIT)
    String fileUrl;            // Cloudinary CDN URL
    String fileType;           // MIME type  e.g. "image/png"
    String fileName;           // original filename
    LocalDateTime timestamp;   // server-assigned, NOT client time
    String originServerId;     // "server-1" — set by producer
    Long messageId;            // DB id for REACTION / EDIT / DELETE / PIN / MSG_ID_ASSIGN
    String reactionEmoji;      // emoji string for REACTION
    Boolean isTyping;          // TYPING events
    String presenceStatus;     // "ONLINE" | "OFFLINE" | "AWAY" | "DND"
    Long replyToMessageId;     // thread parent for MESSAGE
    String recipientUsername;  // NOTIFICATION events
    Long notificationId;       // NOTIFICATION events
    String displayName;        // GROUP_CREATED events
}
```

Wire format (JSON on Kafka):
```json
{
  "eventType": "MESSAGE",
  "sender": "alice",
  "roomName": "general",
  "content": "Hello everyone",
  "fileUrl": null,
  "timestamp": "2026-04-13T10:30:00",
  "originServerId": "server-1",
  "replyToMessageId": null
}
```

---

## II.4 Kafka Topic and Partition Usage

**File:** `kafka/KafkaConfig.java`

### Topic Layout

```
Kafka Broker
│
├── chat-messages-dm      (2 partitions, replication factor = 1)
│   │
│   ├── Partition 0  →  hash("dm__alice__charlie") % 2 == 0
│   └── Partition 1  →  hash("dm__alice__bob")     % 2 == 1
│
└── chat-messages-group   (3 partitions, replication factor = 1)
    │
    ├── Partition 0  →  hash("general") % 3 == 0
    ├── Partition 1  →  hash("engineering") % 3 == 1
    └── Partition 2  →  hash("random") % 3 == 2
```

### Why Two Topics?

| Concern | chat-messages-dm | chat-messages-group |
|---------|-----------------|---------------------|
| Audience | 2 users | Many users |
| Volume | Low | High |
| Priority | High (private) | Normal |
| Partitions | 2 | 3 |
| Isolation | Busy groups cannot delay DMs | — |

### Partition Key = roomName

```
Kafka default partitioner uses: hash(key) % numPartitions

Key = roomName → same room → same hash → same partition → FIFO order

Example:
  hash("general") % 3 = 1
  All messages for "general" → Partition 1 → consumed in arrival order
  No reordering possible within a room
```

### Consumer Group Assignment

```
Topic: chat-messages-group
Partitions: P0, P1, P2

Consumer Group "ws-broadcast-server-1"     (unique per instance)
  └── Consumer-A owns P0, P1, P2           (one instance gets all partitions)

Consumer Group "ws-broadcast-server-2"     (different group = full copy)
  └── Consumer-B owns P0, P1, P2           (second instance also gets all)

Consumer Group "chat-persistence"          (shared = load-balanced)
  └── Consumer-C owns P0
  └── Consumer-D owns P1
  └── Consumer-E owns P2
      (3 workers share 3 partitions → each message processed by exactly one worker)

Consumer Group "push-notifications"        (shared = exactly-once)
  └── Consumer-F owns P0, P1, P2
```

**The critical pattern:**
- **Unique group per instance** (`ws-broadcast-*`) → ALL instances receive ALL messages → each pushes to its local WebSocket clients (fan-out)
- **Shared group** (`chat-persistence`, `push-notifications`) → exactly ONE instance processes each message → no duplicate DB writes

---

## II.5 Consumer Processing Logic

**File:** `kafka/ChatMessageConsumer.java`

The consumer class has **three separate `@KafkaListener` methods** — all on the same two topics, each bound to a different container factory (= different consumer group).

### Listener 1 — WebSocket Broadcast

```java
@KafkaListener(
    topics = { "chat-messages-dm", "chat-messages-group" },
    containerFactory = "wsBroadcastContainerFactory"    // group: ws-broadcast-{instanceId}
)
public void onMessageBroadcast(ChatMessageEvent event)
```

```
onMessageBroadcast(event)
  switch (event.getEventType()):

  ─── PRESENCE ─────────────────────────────────────────────────────────
    socketConnectionHandler.broadcastPresenceGlobally(sender, presenceStatus)
      → iterates ALL userRoomSessions.values() (every session in every room)
      → sends { eventType:"PRESENCE", sender, presenceStatus, timestamp }
      → monitor.record(GROUP_BROADCAST, event, "broadcastPresenceGlobally()")

  ─── MESSAGE ──────────────────────────────────────────────────────────
    socketConnectionHandler.broadcastToLocalSessions(event)
      → roomSessions.get(event.getRoomName())
      → for each open session: session.sendMessage(json)
      → monitor.record(GROUP_BROADCAST, event, "broadcastToLocalSessions() → MESSAGE")

  ─── TYPING ───────────────────────────────────────────────────────────
    socketConnectionHandler.broadcastToLocalSessions(event)
      → monitor.record(GROUP_BROADCAST, event, "... → isTyping=...")

  ─── READ_RECEIPT ─────────────────────────────────────────────────────
    socketConnectionHandler.broadcastToLocalSessions(event)
      → monitor.record(GROUP_BROADCAST, event, "... → READ_RECEIPT msgId=...")

  ─── default (any other type) ─────────────────────────────────────────
    SKIP
    → monitor.record(GROUP_BROADCAST, event, "SKIPPED — not a Kafka-routed event", skipped=true)
```

**What is broadcast per event type (JSON to WS clients):**

```
MESSAGE       → { eventType, sender, roomName, message, fileUrl, fileType,
                  fileName, id, replyTo{id,sender,content}, timestamp }
TYPING        → { eventType, sender, roomName, isTyping, timestamp }
PRESENCE      → { eventType, sender, presenceStatus, timestamp }
READ_RECEIPT  → { eventType, sender, roomName, messageId, timestamp }
```

---

### Listener 2 — Database Persistence

```java
@KafkaListener(
    topics = { "chat-messages-dm", "chat-messages-group" },
    containerFactory = "chatPersistenceContainerFactory"   // group: chat-persistence
)
public void onMessagePersist(ChatMessageEvent event)
```

Processes only MESSAGE events; all others are skipped:

```
onMessagePersist(event)

  if (event.getEventType() != MESSAGE):
    → monitor.record(GROUP_PERSISTENCE, event, "SKIPPED — not a MESSAGE", skipped=true)
    → return

  try:
    ─── Step 1: Save to MySQL ────────────────────────────────────────────
    chatRoomService.saveMessage(roomName, sender, content, fileUrl,
                                 fileType, fileName, replyToMessageId)
      File: service/ChatRoomService.java
      → chatRoomRepository.findByRoomName(roomName)
      → new Message(); set fields
      → if replyToMessageId != null:
          messageRepository.findById(replyToMessageId) → msg.setReplyTo(parent)
      → if hasFile: set fileUrl/fileType/fileName, messageType = IMAGE or FILE
      → else: set content, messageType = TEXT
      → messageRepository.save(msg) → INSERT INTO messages
      → return saved Message with DB-assigned id

    ─── Step 2: MESSAGE_ID_ASSIGN ────────────────────────────────────────
    if (saved != null && saved.getId() != null):
      JSONObject idAssign = {
        eventType: "MESSAGE_ID_ASSIGN",
        sender, roomName, messageId: saved.getId(), timestamp
      }
      socketConnectionHandler.broadcastJsonToRoom(roomName, idAssign)
      → frontend patches data-id on optimistic message bubble

    ─── Step 3: Unread count (GROUP) or Notification (DM) ────────────────
    isDm = roomName.startsWith("dm__")

    if (isDm):
      createDmNotification(roomName, sender)
        → parseDmOtherUser("dm__alice__bob", "alice") → "bob"
        → notificationService.createNotification(
              recipient, NotificationType.MESSAGE, null,
              sender + " sent you a message", roomName)
          → INSERT notification
          → [after TX commit]: pushToUser("bob", {
                eventType: "NOTIFICATION",
                notificationId, type, content,
                unreadCount: <DB count>,   // authoritative DB value
                roomName
              })
      NOTE: No separate UNREAD_COUNT push for DMs —
            the NOTIFICATION payload already carries the authoritative count.

    else (GROUP):
      pushUnreadCountToRoomMembers(roomName, sender, content)
        → chatRoomService.getMemberUsernames(roomName)   [DB read]
        → for each member != sender:
            pushToUser(member, {
              eventType: "UNREAD_COUNT",
              roomName, increment: 1,
              lastMessage: content.substring(0, 60),
              sender
            })
            → userSessions.get(member) → all sessions (chat + sidebar)

    ─── Step 4: @Mention notifications ──────────────────────────────────
    if (content.contains("@")):
      MENTION_PATTERN = Pattern.compile("@(\\w[\\w.-]{1,49})")
      for each matched username:
        skip if: sender, DM recipient (already notified), already notified this message
        userRepository.findByUsername(mentioned)
        notificationService.createNotification(
            recipient, NotificationType.MENTION, null,
            sender + " mentioned you in " + roomName)

  catch Exception:
    log.error + monitor.record error

```

---

### Listener 3 — Push Notifications (stub)

```java
@KafkaListener(
    topics = { "chat-messages-dm", "chat-messages-group" },
    containerFactory = "pushNotificationContainerFactory"   // group: push-notifications
)
public void onMessagePushNotify(ChatMessageEvent event)
```

```
onMessagePushNotify(event)
  if (event.getEventType() != MESSAGE):
    → monitor.record(GROUP_PUSH, event, "SKIPPED — mobile push only for MESSAGE", skipped=true)
    → return
  log.debug("Push stub — sender={} room={}", ...)
  // TODO: FCM / APNs integration goes here
  monitor.record(GROUP_PUSH, event, "FCM/APNs stub invoked (TODO: send mobile push)", false)
```

Currently a stub. Adding Firebase FCM or Apple APNs requires only this one method.

---

## II.6 Database Updates

**Database:** MySQL 8.0 via Spring Data JPA / Hibernate (HikariCP connection pool)

### Tables and When They Are Written

| Table | Entity File | Written by | Trigger |
|-------|------------|-----------|---------|
| `users` | `model/User.java` | `AuthService.registerUser()` | Registration |
| `users` | `model/User.java` | `OAuth2SuccessHandler` | New OAuth registration or account link |
| `users` (status, lastSeen) | `model/User.java` | `UserProfileService.setOnline/setOffline/forceOffline()` | WS connect/disconnect, logout |
| `users` (manualStatusOverride) | `model/User.java` | `UserProfileService.updateProfile()` | Manual status change |
| `users` (failedLoginAttempts, lockedUntil) | `model/User.java` | `AuthService.recordUserFailure/Success()` | Failed/successful login |
| `users` (password) | `model/User.java` | `AuthService.addPasswordToOAuthAccount()` | OAuth user adds a local password |
| `email_verification_tokens` | `model/EmailVerificationToken.java` | `AuthService.registerUser()` | Registration |
| `password_reset_tokens` | `model/PasswordResetToken.java` | `AuthService.requestPasswordReset()` | Forgot-password flow |
| `chat_rooms` | `model/ChatRoom.java` | `ChatRoomService.createOrUpdateChatRoom()`, `createGroupRoom()`, `createOrGetDmRoom()` | Room creation |
| `chat_room_user` | `model/ChatRoomUser.java` | `ChatRoomService.*` | User joins / leaves room |
| `messages` | `model/Message.java` | `ChatRoomService.saveMessage()` | Kafka `chatPersistence` consumer |
| `messages` (content, isEdited) | `model/Message.java` | `MessageService.editMessage()` | REST PATCH |
| `messages` (isDeleted) | `model/Message.java` | `MessageService.deleteMessage()` | REST DELETE |
| `messages` (isPinned) | `model/Message.java` | `MessageService.pinMessage()` | REST POST /pin |
| `message_reactions` | `model/MessageReaction.java` | `ReactionService.addReaction()` | REST POST /react |
| `notifications` | `model/Notification.java` | `NotificationService.createNotification()` | DM message, @mention, friend request/accept |
| `friendships` | `model/Friendship.java` | `FriendshipService.*` | Friend request, accept, block |
| `email_logs` | `model/EmailLog.java` | `EmailService.sendHtml()` | Any email sent |

### Message Entity Write Path (Complete)

```
ChatRoomService.saveMessage(roomName, sender, content, fileUrl, fileType, fileName, replyToId)
  @Transactional
  ↓
  ① chatRoomRepository.findByRoomName(roomName)
       SELECT * FROM chat_rooms WHERE room_name = ?

  ② new Message()
       msg.setSender(sender)           // String, denormalized for query perf
       msg.setChatRoom(chatRoom)       // FK → chat_rooms.id
       msg.setTimestamp(LocalDateTime.now())

  ③ File handling:
       if (fileUrl != null && !fileUrl.isBlank()):
           msg.setFileUrl(fileUrl)
           msg.setFileType(fileType)
           msg.setFileName(fileName)
           msg.setMessageType(fileType.startsWith("image/") ? IMAGE : FILE)
       else:
           msg.setContent(content)
           msg.setMessageType(TEXT)

  ④ Reply threading:
       if (replyToId != null):
           messageRepository.findById(replyToId) → msg.setReplyTo(parent)
           // Self-join: messages.reply_to_message_id → messages.id

  ⑤ messageRepository.save(msg)
       INSERT INTO messages
         (sender, content, chatroom_id, timestamp, file_url, file_type,
          file_name, message_type, reply_to_message_id, is_deleted, is_edited, is_pinned)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false, false, false)

  ⑥ Returns saved Message with generated id (auto-increment PK)
```

### Key JPA Relationships

```
ChatRoom (1) ──── (many) Message
Message  (1) ──── (many) MessageReaction
Message  (M2O)──► Message          [replyTo self-join]
User     (M2M)──► Role             [users_roles join table]
User     (1) ──── (many) ChatRoomUser ◄──── (many) ChatRoom
User     (M2O)──► Friendship.requester
User     (M2O)──► Friendship.addressee
User     (1) ──── (many) Notification

ChatRoomUser extra field: isGroupAdmin (boolean)
  → true for group creator
  → false for all other members
  → used for permission checks in edit/delete/pin/member-management

Fetch strategy:
  All @ManyToOne → FetchType.LAZY (no N+1 on list queries)
  All @OneToMany → FetchType.LAZY
  DTO projection used in controllers to avoid serialization of lazy proxies
```

---

## II.7 Real-Time Propagation via WebSocket

**File:** `config/SocketConnectionHandler.java`

### Four Push Methods

#### `broadcastToLocalSessions(event)` — Room-wide, Kafka-driven

```
Called by: ChatMessageConsumer.onMessageBroadcast()
Purpose:   Fan-out a Kafka-consumed event to all WS sessions in a room

broadcastToLocalSessions(ChatMessageEvent event)
  ↓
  ① buildBroadcastJson(event)
       → switch(event.getEventType()):
           MESSAGE      → { sender, roomName, message, fileUrl, fileType, fileName,
                            id, replyTo{id,sender,content}, timestamp }
           TYPING       → { sender, roomName, isTyping, timestamp }
           READ_RECEIPT → { sender, roomName, messageId, timestamp }

  ② TextMessage outgoing = new TextMessage(json.toString())

  ③ Set<WebSocketSession> targets = roomSessions.get(event.getRoomName())
       ConcurrentHashMap lookup, O(1)

  ④ for (WebSocketSession s : targets)   // CopyOnWriteArraySet, thread-safe
         if (s.isOpen())
             s.sendMessage(outgoing)
```

#### `broadcastJsonToRoom(roomName, payload)` — Room-wide, REST-driven

```
Called by: MessageController (edit, delete, react, pin)
           ChatMessageConsumer.onMessagePersist() (MESSAGE_ID_ASSIGN)
Purpose:   Direct WS push bypassing Kafka — for sync REST mutations

broadcastJsonToRoom(String roomName, JSONObject payload)
  ↓
  ① Set<WebSocketSession> targets = roomSessions.get(roomName)
  ② TextMessage outgoing = new TextMessage(payload.toString())
  ③ for each open session: s.sendMessage(outgoing)
```

#### `pushToUser(username, payload)` — Personal, direct

```
Called by: NotificationService.createNotification()   [after TX commit]
           ChatMessageConsumer.pushUnreadCountToRoomMembers()
           ChatController.createGroup()  [GROUP_CREATED to each member]
Purpose:   Push to a specific user across ALL their open sessions
           (chat room sessions + sidebar session)

pushToUser(String username, JSONObject payload)
  ↓
  ① Set<WebSocketSession> sessions = userSessions.get(username)
       ConcurrentHashMap<String, Set<WebSocketSession>>
       Key: username → ALL this user's open WS sessions (all rooms + sidebar)
  ② TextMessage msg = new TextMessage(payload.toString())
  ③ for each open session: s.sendMessage(msg)

NOTE: Because the sidebar connection is stored in userSessions, it receives
      NOTIFICATION, UNREAD_COUNT, GROUP_CREATED, PRESENCE pushes automatically
      without any separate routing logic.
```

#### `broadcastPresenceGlobally(username, status)` — Global

```
Called by: ChatMessageConsumer.onMessageBroadcast() [for Kafka PRESENCE events]
           SocketConnectionHandler.afterConnectionClosed()
           UserProfileService.updateProfile()   [manual status change]
           AuthController.logout()
Purpose:   Notify every connected user that someone's status changed

broadcastPresenceGlobally(String username, String status)
  ↓
  ① JSONObject = { eventType:"PRESENCE", sender:username,
                   presenceStatus:status, timestamp:... }
  ② userRoomSessions.values().forEach(s -> s.sendMessage(msg))
       ConcurrentHashMap — iterates ALL sessions in ALL rooms (including __sidebar__)
```

### WebSocket Session Lifecycle

```
CONNECT (non-sidebar room)
  afterConnectionEstablished(session)
    key = "username::roomName"
    userRoomSessions.put(key, session)        // evict+close previous session if exists
    roomSessions["roomName"].add(session)
    userSessions["username"].add(session)
    userProfileService.setOnline(username)    // UPDATE users SET status=ONLINE
                                              // (skips if manualStatusOverride=true)
    chatMessageProducer.send(PRESENCE event)  // → Kafka → broadcast to all

CONNECT (sidebar = "__sidebar__")
  afterConnectionEstablished(session)
    same session map registration as above
    STOP — no setOnline(), no PRESENCE event

SEND MESSAGE (non-sidebar session)
  handleTextMessage(session, message)
    parse JSON → build ChatMessageEvent → chatMessageProducer.send()

SEND MESSAGE (sidebar session)
  handleTextMessage(session, message)
    if SIDEBAR_ROOM → return  // silently ignored

DISCONNECT (non-sidebar room)
  afterConnectionClosed(session, status)
    remove from userRoomSessions, roomSessions, userSessions
    if NO other real-room sessions remain for this user:
      userProfileService.setOffline(username)   // UPDATE users SET status=OFFLINE
                                                // (skips if manualStatusOverride=true)
      broadcastPresenceGlobally(username, dbUser.status.name())

DISCONNECT (sidebar)
  afterConnectionClosed(session, status)
    remove from maps
    STOP — no presence update
```

---

## II.8 End-to-End Flow Diagrams

### Diagram A — Sending a Chat Message (Complete Path)

```
 Browser (Alice)        AppServer-1          Kafka           AppServer-2        Browser (Bob)
      │                      │                 │                  │                   │
      │ ── WS frame ────────►│                 │                  │                   │
      │ {room:"general",     │ handleText      │                  │                   │
      │  message:"Hi",       │ Message()       │                  │                   │
      │  eventType:"MESSAGE"}│ build Event     │                  │                   │
      │                      │── kafkaTemplate─►│                  │                   │
      │                      │  .send(         │                  │                   │
      │                      │   "chat-msgs-   │                  │                   │
      │                      │    group",      │                  │                   │
      │                      │   key="general",│                  │                   │
      │                      │   event)        │                  │                   │
      │                      │                 │                  │                   │
      │                      │           [Append to              │                   │
      │                      │            Partition 1]           │                   │
      │                      │                 │                  │                   │
      │                      │    ┌────────────┤                  │                   │
      │                      │    │ ws-broadcast-server-1         │                   │
      │                      │◄───┘            │                  │                   │
      │                      │ broadcastTo     │    ┌─────────────┤                   │
      │                      │ LocalSessions() │    │ ws-broadcast-server-2            │
      │◄── WS frame ─────────│                 │    │             │                   │
      │ {eventType:MESSAGE,   │                 │    └────────────►│                   │
      │  sender:"alice",      │                 │                  │ broadcastTo       │
      │  content:"Hi"...}     │                 │                  │ LocalSessions()   │
      │                      │                 │                  │──── WS frame ────►│
      │                      │    ┌────────────┤                  │                   │
      │                      │    │ chat-persistence              │                   │
      │                      │    ▼             │                  │                   │
      │                      │ saveMessage()   │                  │                   │
      │                      │ INSERT INTO     │                  │                   │
      │                      │ messages ← id=42│                  │                   │
      │                      │                 │                  │                   │
      │                      │ broadcastJson   │                  │                   │
      │                      │ ToRoom(         │                  │                   │
      │◄── WS frame ─────────│  MSG_ID_ASSIGN) │                  │                   │
      │ {eventType:          │                 │                  │                   │
      │  MESSAGE_ID_ASSIGN,  │                 │                  │                   │
      │  messageId:42}       │                 │                  │                   │
      │                      │ pushUnreadCount │                  │                   │
      │                      │ ToRoomMembers() │                  │──── WS frame ────►│
      │                      │                 │                  │ {eventType:       │
      │                      │                 │                  │  UNREAD_COUNT,    │
      │                      │                 │                  │  roomName:general,│
      │                      │                 │                  │  increment:1}     │
      │                      │                 │                  │ sidebar badge +1  │
```

---

### Diagram B — Login + WebSocket Handshake

```
 Browser              JwtAuthFilter       AuthController      WsTicketService    SocketHandler
    │                      │                   │                    │                  │
    │─ POST /login ────────►│                   │                    │                  │
    │  {user, pass}         │ (public route,     │                    │                  │
    │                       │  filter passes)    │                    │                  │
    │                       │──────────────────►│                    │                  │
    │                       │                   │ isUserLocked()?    │                  │
    │                       │                   │ isIPBlocked()?     │                  │
    │                       │                   │ authenticate()     │                  │
    │                       │                   │ BCrypt.matches()   │                  │
    │                       │                   │ generateToken()    │                  │
    │◄─ 200 + Set-Cookie ───│                   │                    │                  │
    │   AUTH_TOKEN=<jwt>    │                   │                    │                  │
    │                       │                   │                    │                  │
    │─ POST /ws-ticket ─────►│                   │                    │                  │
    │  (cookie auto-sent)   │ validateToken()   │                    │                  │
    │                       │──────────────────►│                    │                  │
    │                       │                   │ issueTicket(user) ►│                  │
    │                       │                   │                    │ SecureRandom     │
    │                       │                   │                    │ tickets[xyz]=    │
    │                       │                   │                    │  {user,now+30s}  │
    │◄─ {ticket:"xyz..."}───│                   │◄── "xyz" ──────────│                  │
    │                       │                   │                    │                  │
    │─ WS GET /ws?ticket=xyz─►│                   │                    │                  │
    │  &roomName=general    │ (bypasses JWT     │                    │                  │
    │                       │  filter entirely) │                    │                  │
    │                       │                   HandshakeInterceptor │                  │
    │                       │                   consumeTicket(xyz)──►│                  │
    │                       │                   │                    │ tickets.remove() │
    │                       │                   │                    │ → "alice"        │
    │                       │                   │◄── "alice" ────────│                  │
    │                       │                   attributes[username]=alice              │
    │                       │                   attributes[roomName]=general            │
    │◄─ 101 Switching ──────│                   │                    │                  │
    │   Protocols           │                   │                    │                  │
    │                       │                   │                    │  afterConnection  │
    │                       │                   │                    │  Established()    │
    │                       │                   │                    │  setOnline()      │
    │                       │                   │                    │  PRESENCE → Kafka │
```

---

### Diagram C — React to a Message (Sync Path, No Kafka)

```
 Browser (Alice)       JwtAuthFilter    MessageController    ReactionService   SocketHandler
      │                     │                  │                   │                 │
      │─ POST /messages/42 ─►│                  │                   │                 │
      │    /react            │ read cookie      │                   │                 │
      │  {emoji:"👍"}        │ validate JWT     │                   │                 │
      │                      │─────────────────►│                   │                 │
      │                      │                  │ extractUsername() │                 │
      │                      │                  │─────────────────►│                 │
      │                      │                  │                   │ findByMsgId()   │
      │                      │                  │                   │ checkIdempotent │
      │                      │                  │                   │ INSERT reaction │
      │                      │                  │                   │ return roomName │
      │                      │                  │◄────────────────│                 │
      │                      │                  │ build JSONObject  │                 │
      │                      │                  │ {eventType:REACTION,               │
      │                      │                  │  messageId:42,   │                 │
      │                      │                  │  emoji:"👍"...}  │                 │
      │                      │                  │────────────────────────────────────►│
      │                      │                  │                   │  broadcastJson  │
      │                      │                  │                   │  ToRoom(general)│
      │◄── WS frame ──────────────────────────────────────────────────────────────────│
      │  {eventType:REACTION, messageId:42, emoji:"👍", sender:"alice"}               │
      │◄─ 200 {message:"Reaction added."} ────────────────────────│                 │
```

---

### Diagram D — Friend Request → Instant Notification

```
 Browser (Alice)    FriendshipController  FriendshipService  NotificationService   SocketHandler   Browser (Bob)
      │                    │                    │                    │                   │               │
      │─ POST /friends/────►│                    │                    │                   │               │
      │   request/bob       │                    │                    │                   │               │
      │                     │────────────────────►│                    │                   │               │
      │                     │                    │ INSERT friendship  │                   │               │
      │                     │                    │ (status=PENDING)   │                   │               │
      │                     │                    │────────────────────►│                   │               │
      │                     │                    │                    │ INSERT notification│               │
      │                     │                    │                    │ saveAndFlush()     │               │
      │                     │                    │                    │ [after TX commit]: │               │
      │                     │                    │                    │────────────────────►│               │
      │                     │                    │                    │                   │ pushToUser    │
      │                     │                    │                    │                   │ ("bob", ...)  │
      │                     │                    │                    │                   │──────────────►│
      │◄─ 200 ──────────────│                    │                    │                   │ {eventType:   │
      │                     │                    │                    │                   │  NOTIFICATION,│
      │                     │                    │                    │                   │  unreadCount, │
      │                     │                    │                    │                   │  content:...} │
```

---

### Diagram E — Group Creation → Sidebar Real-Time Update

```
 Browser (Alice)        ChatController         ChatRoomService      Bob's Sidebar WS
      │                      │                       │                     │
      │─ POST /chat/rooms/───►│                       │                     │
      │   group              │ extractUsername()     │                     │
      │ {groupName:"Dev",    │ memberSet = {alice,   │                     │
      │  members:["bob"]}    │   bob}                │                     │
      │                      │──────────────────────►│                     │
      │                      │                       │ createGroupRoom()   │
      │                      │                       │ INSERT chat_rooms   │
      │                      │                       │ INSERT chat_room_user│
      │                      │◄──────────────────────│                     │
      │                      │ Build GROUP_CREATED   │                     │
      │                      │ payload               │                     │
      │                      │                       │                     │
      │                      │ for each member:      │                     │
      │                      │ pushToUser("alice",   │                     │
      │                      │  {GROUP_CREATED,      │                     │
      │                      │   groupRole:"ADMIN"}) │                     │
      │                      │ pushToUser("bob",     │                     │
      │                      │  {GROUP_CREATED,      │                     │
      │                      │   groupRole:"MEMBER"})│──────────────────────►│
      │◄─ 200 ───────────────│                       │                Bob's sidebar WS
      │  {roomName:"Dev",    │                       │                receives event,
      │   type:"GROUP",      │                       │                adds "Dev" group
      │   groupRole:"ADMIN"} │                       │                to sidebar list
```

---

## II.9 Event-Driven Flow Explanation

### What "Event-Driven" Means Here

This system uses **two levels of event-driven architecture**:

1. **Kafka-level events** — asynchronous, durable, partitioned, multi-consumer
2. **WebSocket-level events** — synchronous in-process push, ephemeral, in-memory

Not everything goes through Kafka. The system deliberately chooses which path each event takes based on whether cross-instance fan-out or low latency matters more.

---

### Event Classification

#### Class 1 — Kafka-Routed Events (fan-out, ordered delivery)

| Event | Why Kafka? |
|-------|-----------|
| New chat `MESSAGE` | Must reach all instances for fan-out; needs DB persistence exactly once |
| `TYPING` | Must reach cross-instance sessions; no DB write needed |
| `READ_RECEIPT` | Cross-instance; no DB write |
| `PRESENCE` (WS connect) | Announce online status to all rooms across all instances |

**Flow:**
```
WS Handler → ChatMessageProducer.send() → Kafka topic
  ↓                                              ↓
(returns immediately,             [async] Consumer Group A: ws-broadcast (per instance)
 non-blocking)                               → broadcastToLocalSessions()
                                  [async] Consumer Group B: chat-persistence (shared)
                                           → saveMessage() / DB write [once]
                                  [async] Consumer Group C: push-notifications (shared)
                                           → push stub [once]
```

#### Class 2 — Direct WebSocket Push (sync, low-latency mutations)

| Event | Why NOT Kafka? |
|-------|---------------|
| `MESSAGE_EDIT` | REST already authenticated + persisted; Kafka adds unnecessary roundtrip |
| `MESSAGE_DELETE` | Same |
| `REACTION` (add/remove) | Same — DB write already done in the REST call |
| `PIN` | Same |
| `MESSAGE_ID_ASSIGN` | Backfill pushed immediately after DB save (in `chatPersistence` consumer) |
| `NOTIFICATION` | Push directly to the specific user after DB insert (after TX commit) |
| `UNREAD_COUNT` (GROUP) | Push directly to each room member after message save |
| `GROUP_CREATED` | Direct push to each member — no fan-out needed (known members list) |
| `PRESENCE` (disconnect) | Direct broadcast — server already knows the state |

**Flow:**
```
HTTP Request → Controller → Service → DB write (sync)
                                            ↓
                         socketHandler.broadcastJsonToRoom(roomName, json)
                                  OR
                         socketHandler.pushToUser(username, json)
                                            ↓
                         [in-process, microseconds]
                         roomSessions / userSessions → for each WS session → send
```

#### Class 3 — In-Process Events (no Kafka, no WS)

| Event | Handling |
|-------|---------|
| JWT validation | In-memory, per-request filter |
| WS ticket issue/consume | In-memory ConcurrentHashMap |
| Token blacklist check | In-memory ConcurrentHashMap |
| IP rate limit | In-memory ConcurrentHashMap |
| Account lockout check | DB read (persists across restarts) |
| Email sending | `@Async` thread pool (Spring TaskExecutor) |

---

### Sync vs Async — Master Table

| Operation | File | Sync/Async | Reason |
|-----------|------|-----------|--------|
| Login | `AuthController.java` | **Sync** | Response needed immediately |
| Register | `AuthController.java` | **Sync** | Validation response needed |
| OAuth login | `OAuth2SuccessHandler.java` | **Sync** | Cookie + redirect immediate |
| Email send | `EmailService.java` | **Async** (`@Async`) | Non-blocking, user doesn't wait |
| JWT validate | `JwtAuthFilter.java` | **Sync** | Blocks request until authenticated |
| WS ticket issue | `WsTicketService.java` | **Sync** | Immediate response needed |
| WS ticket consume | `WebSocketConfig.java` | **Sync** | Handshake decision immediate |
| Send message (WS→Kafka) | `SocketConnectionHandler.java` | **Async** | `CompletableFuture`, returns immediately |
| Kafka produce ack | `ChatMessageProducer.java` | **Async callback** | `whenComplete()` handler |
| WS broadcast (Kafka consumer) | `ChatMessageConsumer.java` | **Async** | Kafka listener thread |
| DB save (chat-persistence) | `ChatMessageConsumer.java` | **Async** | Kafka listener thread |
| Edit message | `MessageController.java` | **Sync** | REST, DB write before response |
| Edit WS broadcast | `MessageController.java` | **Sync** (in-process) | After DB write, before response |
| Delete message | `MessageController.java` | **Sync** | Same as edit |
| Add reaction | `MessageController.java` | **Sync** | REST + direct WS push |
| Pin message | `MessageController.java` | **Sync** | REST + direct WS push |
| File upload | `FileStorageService.java` | **Sync** | Blocks until Cloudinary responds |
| Create notification | `NotificationService.java` | **Sync** DB write + **Async** WS push | DB write sync; WS push after TX commit |
| Friend request | `FriendshipService.java` | **Sync** | DB write + notification in same call |
| Presence (WS connect) | `SocketConnectionHandler.java` | **Async** | Via Kafka |
| Presence (WS disconnect) | `SocketConnectionHandler.java` | **Sync** (direct) | `broadcastPresenceGlobally()` |
| Presence (manual status) | `UserProfileService.java` | **Sync** | `broadcastPresenceGlobally()` after DB save |
| Presence (logout) | `AuthController.java` | **Sync** | `broadcastPresenceGlobally()` after cookie clear |
| Unread count push | `ChatMessageConsumer.java` | **Async** | Inside Kafka consumer |
| Group creation push | `ChatController.java` | **Sync** | Direct `pushToUser()` after DB save |

---

### Complete Event Taxonomy

```
All System Events
│
├── Kafka Events (async, durable, cross-instance)
│   Topics: chat-messages-dm, chat-messages-group
│   │
│   ├── MESSAGE           → produced by SocketConnectionHandler on WS text frame
│   ├── TYPING            → produced by SocketConnectionHandler on WS typing frame
│   ├── READ_RECEIPT      → produced by SocketConnectionHandler on WS receipt frame
│   └── PRESENCE (online) → produced by SocketConnectionHandler on WS connect
│
├── Direct WS Push Events (sync, in-process, bypass Kafka)
│   Via: socketHandler.broadcastJsonToRoom() or .pushToUser() or .broadcastPresenceGlobally()
│   │
│   ├── MESSAGE_EDIT      → MessageController → broadcastJsonToRoom()
│   ├── MESSAGE_DELETE    → MessageController → broadcastJsonToRoom()
│   ├── REACTION          → MessageController → broadcastJsonToRoom()
│   ├── PIN               → MessageController → broadcastJsonToRoom()
│   ├── MESSAGE_ID_ASSIGN → ChatMessageConsumer (chatPersistence) → broadcastJsonToRoom()
│   ├── NOTIFICATION      → NotificationService → pushToUser() [after TX commit]
│   ├── UNREAD_COUNT      → ChatMessageConsumer (chatPersistence) → pushToUser()
│   ├── GROUP_CREATED     → ChatController → pushToUser() [to each member]
│   └── PRESENCE (offline)→ SocketConnectionHandler.afterConnectionClosed()
│                           → broadcastPresenceGlobally() [direct, no Kafka]
│
└── Internal-Only Events (no Kafka, no WS)
    ├── JWT issue/validate       → JwtUtil, JwtService
    ├── WS ticket issue/consume  → WsTicketService
    ├── Token blacklist          → TokenBlacklistService
    ├── IP rate limit            → LoginRateLimiter
    └── Email delivery           → EmailService (@Async thread)
```
