# ChatApp — Complete System Internals for Developers

> **Last updated:** April 2026 — reflects current codebase state.

---

## Table of Contents
1. [Architecture Overview](#1-architecture-overview)
2. [Authentication Flow](#2-authentication-flow)
3. [WebSocket Flow](#3-websocket-flow)
4. [Kafka / Event Flow](#4-kafka--event-flow)
5. [Feature Flows (End-to-End)](#5-feature-flows-end-to-end)
6. [Data Flow](#6-data-flow)
7. [Real-Time Updates](#7-real-time-updates)
8. [Internal Communication](#8-internal-communication)
9. [Sequence Diagrams](#9-sequence-diagrams)
10. [Design Decisions](#10-design-decisions)

---

## 1. Architecture Overview

### Component Map

```
┌─────────────────────────────────────────────────────────────────────┐
│                        BROWSER (Vanilla JS)                          │
│  Pages: index/login/register/chat/profile/dashboard/reset-password  │
│  ┌──────────────┐  ┌───────────────────────────────────────────┐    │
│  │  REST (HTTP) │  │  WebSocket (ws:// or wss://)              │    │
│  │ + HttpOnly   │  │  Opened AFTER JWT auth, secured by        │    │
│  │ JWT Cookie   │  │  one-time WsTicket in query param          │    │
│  └──────┬───────┘  └────────────────┬──────────────────────────┘    │
└─────────┼──────────────────────────-┼────────────────────────────────┘
          │ HTTP/REST                 │ WebSocket
          ▼                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│             Spring Boot 3 Application (Java 17)                     │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Security Layer                                              │   │
│  │  JwtAuthFilter (OncePerRequestFilter) → reads HttpOnly      │   │
│  │  cookie → validates via JwtService → sets SecurityContext   │   │
│  └──────────────────────────────────┬──────────────────────────┘   │
│                                     │                               │
│  ┌──────────────────────────────────▼──────────────────────────┐   │
│  │  Controllers (REST)                                          │   │
│  │  AuthController  │ ChatController   │ MessageController      │   │
│  │  FriendController│ NotifController  │ ProfileController      │   │
│  │  AdminController │ ModeratorCtrl    │ FileUploadController   │   │
│  │  KafkaMonitorCtrl│                                           │   │
│  └──────────────────────────────────┬──────────────────────────┘   │
│                                     │                               │
│  ┌──────────────────────────────────▼──────────────────────────┐   │
│  │  Service Layer                                               │   │
│  │  AuthService │ ChatRoomService │ MessageService              │   │
│  │  ReactionService │ NotificationService │ FriendshipService   │   │
│  │  UserProfileService │ EmailService │ OAuth2UserService       │   │
│  │  KafkaMonitorService │                                        │   │
│  └──────────────────────────────────┬──────────────────────────┘   │
│                                     │                               │
│  ┌──────────────┐   ┌───────────────▼────────────────────────┐     │
│  │ Kafka        │   │  Data Access Layer (Spring Data JPA)    │     │
│  │ Producer     │   │  Repos: UserRepo, MessageRepo,          │     │
│  │ ────────►    │   │         ChatRoomRepo, FriendshipRepo,   │     │
│  │ chat-msgs-dm │   │         NotificationRepo,               │     │
│  │ chat-msgs-   │   │         MessageReactionRepo,            │     │
│  │ group        │   │         EmailLogRepo                    │     │
│  └──────┬───────┘   └──────────────────┬──────────────────────┘     │
│         │                              │                            │
│  ┌──────▼───────────────────────────────────────────────────────┐  │
│  │  Kafka Consumers (3 Consumer Groups on same 2 topics)        │  │
│  │  ① wsBroadcast      → SocketConnectionHandler (WS push)      │  │
│  │  ② chatPersistence  → ChatRoomService.saveMessage (MySQL)     │  │
│  │  ③ pushNotifications → stub (extensible push gateway)        │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                     │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  SocketConnectionHandler (TextWebSocketHandler)            │    │
│  │  3 in-memory maps (ConcurrentHashMap / CopyOnWriteArraySet)│    │
│  │  - userRoomSessions: "user::room" → WebSocketSession       │    │
│  │  - roomSessions:     roomName    → Set<WebSocketSession>   │    │
│  │  - userSessions:     username   → Set<WebSocketSession>    │    │
│  │                                                            │    │
│  │  Special room: "__sidebar__" (receive-only, no presence)   │    │
│  └────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
          │                    │                    │
          ▼                    ▼                    ▼
    ┌──────────┐        ┌──────────┐        ┌───────────────┐
    │  MySQL   │        │  Redis   │        │   Cloudinary  │
    │  8.0     │        │  7.x     │        │  (CDN files)  │
    │ (JPA)    │        │ (token   │        │               │
    │          │        │ blacklist│        │               │
    └──────────┘        └──────────┘        └───────────────┘
          │                    │
          ▼                    ▼
    ┌──────────┐        ┌─────────────┐
    │  Apache  │        │  MailHog /  │
    │  Kafka   │        │  SMTP       │
    │ (2 topics│        │  (email     │
    │  5 parts)│        │  verify,    │
    └──────────┘        │  alerts)    │
                        └─────────────┘
```

### How They Connect

| From | To | How |
|------|----|-----|
| Browser | Spring Boot | HTTP/REST + HttpOnly JWT cookie |
| Browser | Spring Boot | WebSocket (raw, ws://) with one-time ticket |
| Spring Boot | MySQL | Spring Data JPA / Hibernate (JDBC) |
| Spring Boot | Redis | Spring Data Redis (token blacklist) |
| Spring Boot | Kafka | KafkaTemplate (producer) / @KafkaListener (consumer) |
| Spring Boot | Cloudinary | Cloudinary Java SDK (HTTP) |
| Spring Boot | SMTP | Spring Mail / JavaMailSender |

---

## 2. Authentication Flow

### Registration

```
POST /api/auth/register
  ↓
AuthController.register()
  → validate username (3–50 chars, alphanumeric + _.-), password strength, email format
  → AuthService.registerUser()
      → BCrypt.hash(password)
      → save User to MySQL (emailVerified = false, provider = null)
      → EmailService.sendVerificationEmail()   [async]
          → generate UUID token → store in email_verification_tokens table
          → send email with link: /api/auth/verify-email?token=...
  ← 200 { message: "User registered. Check your email to verify." }

GET /api/auth/verify-email?token=...
  ↓
AuthController.verifyEmail()
  → find EmailVerificationToken by token (JOIN FETCH user)
  → check: used? expired?
  → set user.emailVerified = true
  → EmailService.sendWelcomeEmail(user)   [async]
  ← redirect to /api/v1/login?verified=true
```

### Password Strength Rules

All passwords (registration, reset, change) must satisfy:
- Minimum 8 characters, maximum 128 characters
- At least one uppercase letter
- At least one lowercase letter
- At least one digit
- At least one special character (`!@#$%^&*` etc.)

### Login (step-by-step)

```
POST /api/auth/login  { username, password }
  ↓
AuthController.createAuthenticationToken()
  │
  ├─ [1] Input validation (username + password required)
  │
  ├─ [2] Per-user account lockout (DB-persisted, survives restarts)
  │       AuthService.isUserLocked(username)
  │       checks: user.lockedUntil > NOW()
  │       → 423 { error, retryAfter } if locked
  │
  ├─ [3] IP-based rate limit (secondary protection)
  │       LoginRateLimiter.isBlocked(ip)   [in-memory ConcurrentHashMap]
  │       5 failures per IP → block for 15 min
  │       → 429 if blocked
  │
  ├─ [4] Spring Security authenticate
  │       AuthenticationManager.authenticate(
  │         UsernamePasswordAuthenticationToken(username, password))
  │       → calls AuthService.loadUserByUsername()
  │           → UserRepository.findByUsername()
  │           → return UserDetails with BCrypt-encoded password
  │       → BCryptPasswordEncoder.matches(rawPwd, hashedPwd)
  │       → throws AuthenticationException on failure
  │           → rateLimiter.recordFailure(ip) + authService.recordUserFailure(username)
  │           → 5 failures → account lock → EmailService.sendAccountLockedEmail() [async]
  │
  ├─ [4a] OAuth-only guard
  │        if user.password is blank → 403 { error, oauthOnly:true, provider }
  │        (user registered via Google/GitHub and hasn't added a local password)
  │
  ├─ [5] Email verification gate
  │       if (!user.emailVerified) → 403 { error, unverified:true }
  │
  ├─ [6] Clear failure counters on success
  │
  ├─ [7] JWT generation
  │       JwtUtil.generateToken(UserDetails)
  │         → HMAC-SHA256 signed
  │         → claims: { sub: username, roles: [...], jti: UUID, exp: now+2h }
  │
  ├─ [8] Send login-notification email (async, non-blocking)
  │
  └─ [9] Set HttpOnly cookie
         Cookie: AUTH_TOKEN=<jwt>; Path=/; HttpOnly; SameSite=Lax; Max-Age=7200
         ← 200 { username, roles, displayName, avatarUrl, email }
         NOTE: JWT is NOT in the response body
```

### Per-Request Authentication (every REST call)

```
Any HTTP Request
  ↓
JwtAuthFilter.doFilterInternal()   ← runs before every servlet
  │
  ├─ JwtService.extractToken(request)
  │     loops cookies → finds "AUTH_TOKEN"
  │
  ├─ JwtService.validateToken(token)
  │     ├─ TokenBlacklistService.isBlacklisted(token)  [in-memory Map]
  │     ├─ JwtUtil.extractUsername(token)              [parse HMAC-SHA256]
  │     ├─ userRepository.existsByUsername(username)   [DB read]
  │     └─ JwtUtil.isTokenExpired(token)
  │
  ├─ JwtService.getAuthenticationFromToken(token)
  │     → AuthService.loadUserByUsername()
  │     → UsernamePasswordAuthenticationToken with GrantedAuthority list
  │
  └─ SecurityContextHolder.setAuthentication(auth)
         → Spring Security now knows who this request belongs to
```

### Logout

```
POST /api/auth/logout
  ↓
  → JwtService.invalidateToken(token)
      → TokenBlacklistService.blacklist(token, expiry)  [ConcurrentHashMap]
  → set-cookie AUTH_TOKEN="" Max-Age=0   (clears browser cookie)
  → UserProfileService.forceOffline(username)           [ignores manual override]
  → socketHandler.broadcastPresenceGlobally(username, "OFFLINE")
```

### OAuth2 — Google / GitHub (Production-safe flow)

```
/oauth2/authorization/google  (redirect from login page)
  ↓
Spring Security OAuth2 dance (PKCE + state parameter)
  ↓
OAuth2SuccessHandler.onAuthenticationSuccess()

  [1] Extract provider attributes:
      provider = "google" or "github"
      providerUserId = attrs["sub"] (Google) or attrs["id"] (GitHub)
      email   = attrs["email"]
      name    = attrs["name"]
      picture = attrs["picture"] (Google) or attrs["avatar_url"] (GitHub)

  [2] Find existing user:
      a. By email           → userRepository.findByEmail(email)
      b. Fallback: by       → userRepository.findByProviderAndProviderUserId(provider, id)
         provider+id

  [3a] NEW USER (not found):
      → auto-register:
          username = sanitized email prefix (unique, max 50 chars)
          password = ""   (EMPTY — NO local password, NO forced prompt)
          emailVerified = true  (OAuth provider verified it)
          provider = "google" / "github"
          providerUserId = <id from provider>
          roles = [USER]
      → userRepository.save(newUser)
      → EmailService.sendOAuthWelcomeEmail(user, provider)   [async]

  [3b] EXISTING USER (found):
      → if user.provider == null (previously registered with password):
          → link account: set provider + providerUserId + emailVerified=true
          → save
          → log "Linked google to existing user"

  [4] Issue JWT:
      → JwtUtil.generateToken(UserDetails)

  [5] Set HttpOnly cookie (same as password login):
      Set-Cookie: AUTH_TOKEN=<jwt>; Path=/; HttpOnly; SameSite=Lax; Max-Age=7200

  [6] Send login notification email (async)

  [7] Redirect:
      ADMIN → /api/v1/dashboard
      USER  → /api/v1/chat

NOTE: Online status is NOT set here.
The user becomes ONLINE when their WebSocket connection is established
in SocketConnectionHandler.afterConnectionEstablished(), which is the
single source of truth for presence state.
```

### OAuth Users and Passwords — How It Works

| Scenario | Behaviour |
|----------|-----------|
| User registers via Google | Account created with **empty** password. No password prompt. They simply log in with Google. |
| OAuth user wants local login too | User goes to **Settings → Security → "Add a password"** (`POST /api/auth/add-password`). Entirely voluntary, not required. |
| OAuth user tries to log in with username+password | Gets 403 `oauthOnly: true` message directing them to use Google login. |
| Regular user links Google later | Google OAuth login finds the account by email, links the provider, and now both login methods work. |

This is exactly how Facebook, Slack, and GitHub handle OAuth registration — no forced password setup.

### Password Management Endpoints

| Endpoint | Auth | Purpose |
|----------|------|---------|
| `POST /api/auth/login` | Public | Standard username + password login |
| `POST /api/auth/forgot-password` | Public | Request password reset email |
| `POST /api/auth/reset-password` | Public (token) | Set new password via email token |
| `POST /api/auth/change-password` | Authenticated | Change password (requires old password) |
| `POST /api/auth/add-password` | Authenticated | **Optional:** OAuth users add a local password |

---

## 3. WebSocket Flow

### Two Types of WebSocket Connections

#### 1. Chat Room Connection (`roomName = <actual room>`)
Full-featured: sends and receives messages, produces PRESENCE events on connect/disconnect.

#### 2. Sidebar Connection (`roomName = __sidebar__`)
Receive-only utility channel. Used by the sidebar to receive real-time updates (GROUP_CREATED, UNREAD_COUNT, NOTIFICATION, PRESENCE) **without** triggering spurious ONLINE/OFFLINE presence events. Any text frames sent on this connection are silently ignored.

### Connection Establishment

```
STEP 1 — Get a one-time ticket (must be authenticated)
POST /api/auth/ws-ticket
  ↓
AuthController.issueWsTicket()
  → WsTicketService.issueTicket(username)
      → SecureRandom 32-byte → Base64URL encoded string
      → stored in ConcurrentHashMap: ticket → (username, expiresAt = now+30s)
  ← { ticket: "abc123..." }

STEP 2 — Open WebSocket connection
new WebSocket("ws://host/ws?ticket=abc123...&roomName=general")
     OR
new WebSocket("ws://host/ws?ticket=abc123...&roomName=__sidebar__")

STEP 3 — Server HandshakeInterceptor (in WebSocketConfig)
  ↓
  → parse query params: ticket, roomName
  → WsTicketService.consumeTicket(ticket)
      → tickets.remove(ticket)    // ONE-TIME USE — removed immediately
      → check expiry (30 seconds)
      → return username OR null
  → if null → response.setStatus(401) → reject
  → if ok   → attributes.put("username", username)
              attributes.put("roomName", roomName)
              → accept handshake

STEP 4 — afterConnectionEstablished()
  → key = "username::roomName"
  → userRoomSessions.put(key, session)         // deduplicate tab
  → roomSessions.get(roomName).add(session)
  → userSessions.get(username).add(session)    // ALL rooms for pushToUser()

  → if roomName == "__sidebar__": STOP HERE — no presence update
  → else:
      → UserProfileService.setOnline(username)   // DB update (respects manual override)
      → publish PRESENCE event → Kafka → broadcast to all rooms
```

### Session Data Structures

```
userRoomSessions: ConcurrentHashMap<String, WebSocketSession>
  Key: "alice::general"
  Purpose: one active session per user per room, deduplicates extra tabs

roomSessions: ConcurrentHashMap<String, Set<WebSocketSession>>
  Key: "general"  OR  "__sidebar__"
  Purpose: fan-out to everyone in a room

userSessions: ConcurrentHashMap<String, Set<WebSocketSession>>
  Key: "alice"
  Purpose: push personal events (notifications, unread counts)
           regardless of which room the user is currently viewing
           — includes both chat room sessions AND sidebar sessions
```

### Sending a Message (Client → Server)

```
client: ws.send(JSON.stringify({ sender, room, message, eventType: "MESSAGE" }))
  ↓
SocketConnectionHandler.handleTextMessage()
  → if session is on "__sidebar__" → return (receive-only)
  → parse JSON
  → security check: session.attributes["roomName"] must equal json["room"]
  → determine eventType: MESSAGE / TYPING / READ_RECEIPT
  → build ChatMessageEvent (POJO)
  → ChatMessageProducer.send(event)
      → choose topic: "dm__*" → chat-messages-dm
                       else   → chat-messages-group
      → partition key = roomName  (guarantees order within room)
      → kafkaTemplate.send(topic, roomName, event)   [async CompletableFuture]
```

### Receiving a Message (Server → Client)

```
Kafka Consumer (wsBroadcast group) calls onMessageBroadcast(event)
  ↓
socketConnectionHandler.broadcastToLocalSessions(event)
  → roomSessions.get(event.getRoomName())
  → buildBroadcastJson(event)   // serialize to JSONObject
  → for each open session: session.sendMessage(TextMessage)
```

### Disconnect

```
afterConnectionClosed(session, status)
  → remove from userRoomSessions, roomSessions, userSessions
  → if roomName == "__sidebar__": STOP HERE — no presence update
  → if user has NO other REAL-ROOM open sessions:
      → UserProfileService.setOffline(username)
      → read dbUser.status (respects manual override)
      → broadcastPresenceGlobally(username, actualStatus)
          → pushes to ALL open sessions in ALL rooms
```

---

## 4. Kafka / Event Flow

### Topic Design

```
chat-messages-dm     (2 partitions, replication=1)
  → All DM rooms  (room name: "dm__alice__bob")

chat-messages-group  (3 partitions, replication=1)
  → All group/channel rooms

Partition key = roomName
  → consistent hash → same room always → same partition → ORDER guaranteed
```

### Consumer Groups

| Group ID | Factory Bean | Behavior |
|----------|-------------|----------|
| `ws-broadcast-{instanceId}` | `wsBroadcastContainerFactory` | **Unique per server instance** → every instance gets every message (fan-out) |
| `chat-persistence` | `chatPersistenceContainerFactory` | **Shared** → exactly one server writes to MySQL |
| `push-notifications` | `pushNotificationContainerFactory` | **Shared** → exactly one server processes push stubs |

### What Flows Through Kafka

| Event | Produced by | Topic | Why Kafka? |
|-------|------------|-------|-----------|
| `MESSAGE` | SocketConnectionHandler | dm or group | Cross-instance fan-out + DB persistence exactly once |
| `TYPING` | SocketConnectionHandler | dm or group | Cross-instance fan-out |
| `READ_RECEIPT` | SocketConnectionHandler | dm or group | Cross-instance fan-out |
| `PRESENCE` (connect) | SocketConnectionHandler | dm or group | Cross-instance fan-out |

### What Does NOT Flow Through Kafka (Direct WS Push)

| Event | Who Pushes | Why Not Kafka? |
|-------|-----------|---------------|
| `MESSAGE_EDIT` | MessageController → broadcastJsonToRoom() | REST already persisted; Kafka adds latency for no gain |
| `MESSAGE_DELETE` | MessageController → broadcastJsonToRoom() | Same |
| `REACTION` | MessageController → broadcastJsonToRoom() | Same |
| `PIN` | MessageController → broadcastJsonToRoom() | Same |
| `MESSAGE_ID_ASSIGN` | chatPersistence consumer → broadcastJsonToRoom() | Post-save backfill |
| `NOTIFICATION` | NotificationService → pushToUser() | Personal, direct |
| `UNREAD_COUNT` | chatPersistence consumer → pushToUser() | Personal, direct |
| `GROUP_CREATED` | ChatController → pushToUser() | Direct push to each member after DB creation |
| `PRESENCE` (disconnect) | SocketConnectionHandler → broadcastPresenceGlobally() | Direct, no Kafka |

### ChatMessageEvent — Fields

```java
eventType:       MESSAGE | TYPING | READ_RECEIPT | PRESENCE | REACTION
                 MESSAGE_EDIT | MESSAGE_DELETE | PIN | NOTIFICATION
                 UNREAD_COUNT | GROUP_CREATED
sender:          username of actor
roomName:        target room
content:         text content (MESSAGE, MESSAGE_EDIT)
fileUrl/Type/Name: file attachment (MESSAGE)
messageId:       DB id (REACTION, MESSAGE_EDIT, MESSAGE_DELETE, PIN, MESSAGE_ID_ASSIGN)
reactionEmoji:   emoji string (REACTION)
isTyping:        boolean (TYPING)
presenceStatus:  "ONLINE" | "OFFLINE" | "AWAY" | "DND" (PRESENCE)
replyToMessageId: thread parent id (MESSAGE)
recipientUsername / notificationId: (NOTIFICATION)
displayName:     group display name (GROUP_CREATED)
originServerId:  "server-1" — set by producer, used for debugging
```

### Consumer Processing per Event Type

```
Consumer: onMessageBroadcast() [ws-broadcast group — UNIQUE per instance]

  PRESENCE     → broadcastPresenceGlobally()   (all sessions, all rooms)
  MESSAGE      → broadcastToLocalSessions()    (room sessions only)
  TYPING       → broadcastToLocalSessions()    (room sessions only)
  READ_RECEIPT → broadcastToLocalSessions()    (room sessions only)
  Other        → SKIP (logged + monitored)

Consumer: onMessagePersist() [chat-persistence — SHARED, exactly one instance]

  MESSAGE      → ChatRoomService.saveMessage() → INSERT to messages table
                → push MESSAGE_ID_ASSIGN back via broadcastJsonToRoom()
                → if GROUP room: pushUnreadCountToRoomMembers()
                     (UNREAD_COUNT → each member except sender via pushToUser())
                → if DM room: NotificationService.createNotification(MESSAGE)
                     (includes unreadCount, NO separate UNREAD_COUNT push)
                → if @mention: NotificationService.createNotification(MENTION)
                     (deduplicated — sender and DM recipient excluded)
  Other        → SKIP (ephemeral)

Consumer: onMessagePushNotify() [push-notifications — SHARED, stub]

  MESSAGE      → stub (TODO: FCM / APNs integration)
  Other        → SKIP
```

---

## 5. Feature Flows (End-to-End)

### 5.1 User Login

```
Browser                  Spring Boot              MySQL/Redis
  │                           │                       │
  │── POST /api/auth/login ──►│                       │
  │   { username, password }  │── findByUsername ────►│
  │                           │◄─ User entity ────────│
  │                           │                       │
  │                           │ isUserLocked()?        │
  │                           │ isIPBlocked()?         │
  │                           │ BCrypt.matches()       │
  │                           │ generateJWT()          │
  │                           │                       │
  │◄── 200 + Set-Cookie ──────│                       │
  │    AUTH_TOKEN (HttpOnly)  │                       │
  │                           │                       │
  │── POST /api/auth/ws-ticket►│                       │
  │                           │ WsTicketService        │
  │                           │ issueTicket(username)  │
  │◄── { ticket: "xyz..." } ──│                       │
  │                           │                       │
  │── WS CONNECT ────────────►│                       │
  │   /ws?ticket=xyz&room=... │ HandshakeInterceptor   │
  │                           │ consumeTicket()        │
  │◄── 101 Switching ─────────│                       │
  │    Protocols              │ afterConnEstablished() │
  │                           │ setOnline()           │
  │                           │── PRESENCE via Kafka ─►│
```

### 5.2 Google OAuth Login / Registration

```
Browser                      Spring Boot (OAuth2)              MySQL
  │                               │                                │
  │── GET /oauth2/authorization/───►│                                │
  │      google                   │ Redirect to Google OAuth       │
  │◄── 302 to Google ─────────────│                                │
  │                               │                                │
  │  [User approves Google login] │                                │
  │── GET /login/oauth2/code/──────►│                                │
  │      google?code=...          │ OAuth2SuccessHandler           │
  │                               │── findByEmail(email) ─────────►│
  │                               │                                │
  │                               │ [NEW user] → auto-register     │
  │                               │   username = email prefix      │
  │                               │   password = "" (NO password)  │
  │                               │   emailVerified = true         │
  │                               │   provider = "google"          │
  │                               │   providerUserId = <id from provider>
  │                               │   roles = [USER]
      │                               │── INSERT user ─────────────────►│
  │                               │ sendOAuthWelcomeEmail [async]  │
  │                               │                                │
  │                               │ [EXISTING user] → link account │
  │                               │   (if not already linked)      │
  │                               │── UPDATE provider fields ──────►│
  │                               │                                │
  │                               │ issue JWT → Set-Cookie         │
  │◄── 302 → /api/v1/chat ────────│                                │
```

**No password setup after Google registration.** The user is already authenticated by Google. If they later want a local password, they can add one voluntarily from Settings.

### 5.3 Sending a Message

```
Browser                Spring Boot           Kafka           MySQL
  │                        │                   │               │
  │── WS send ────────────►│                   │               │
  │  { room, message }     │                   │               │
  │                        │ handleTextMessage()│               │
  │                        │── kafkaProducer ──►│               │
  │                        │   .send(event)     │               │
  │                        │                   │               │
  │                        │        ┌──────────┘               │
  │                        │        │ [wsBroadcast consumer]   │
  │                        │◄───────┘                          │
  │                        │ broadcastToLocalSessions()        │
  │◄── WS event ───────────│                                   │
  │  { eventType:MESSAGE,  │        ┌──────────┘               │
  │    sender, content... }│        │ [chatPersistence consumer]│
  │                        │        │ saveMessage()             │
  │                        │────────────────────────────────►│
  │                        │        │ INSERT INTO messages     │
  │                        │◄────────────────────────────────│
  │                        │        │ saved.getId() → backfill │
  │◄── WS event ───────────│        │ MESSAGE_ID_ASSIGN        │
  │  { eventType:          │        │                          │
  │    MESSAGE_ID_ASSIGN,  │        │                          │
  │    messageId: 42 }     │        │                          │
```

### 5.4 Unread Count — DM vs Group

| Room Type | Mechanism | Payload |
|-----------|-----------|---------|
| DM room | `NotificationService.createNotification()` → `pushToUser(recipient)` | `{ eventType: "NOTIFICATION", unreadCount: <DB value>, roomName, ... }` |
| GROUP room | `pushUnreadCountToRoomMembers()` → `pushToUser(each member)` | `{ eventType: "UNREAD_COUNT", roomName, increment: 1, lastMessage, sender }` |

**Key:** DM unread count comes from the authoritative DB count. Group unread count uses increment-by-1 for low latency. Both push to the user's `userSessions` set — meaning both the open chat tab and the sidebar connection receive it.

### 5.5 Group Creation — Real-Time Sidebar Update

```
Browser (Alice)          Spring Boot               Bob's Browser (sidebar WS)
  │                           │                             │
  │── POST /api/chat/rooms/───►│                             │
  │      group                │ createGroupRoom()           │
  │   { groupName, members }  │── INSERT chat_room ────────►│
  │                           │── INSERT chat_room_user ───►│
  │                           │                             │
  │                           │ Push GROUP_CREATED to       │
  │                           │ each member via pushToUser()│
  │                           │  { eventType: "GROUP_CREATED",│
  │                           │    roomName, displayName,   │
  │                           │    groupRole, createdBy }   │
  │◄── 200 { roomName, type,──│─────────────────────────────►│
  │          groupRole:ADMIN } │                         Sidebar receives
  │                           │                         GROUP_CREATED event
  │                           │                         → adds room to sidebar
  │                           │                         without page refresh
```

### 5.6 Reacting to a Message

```
Browser                 Spring Boot                  MySQL
  │                          │                          │
  │── POST /api/messages/42  │                          │
  │        /react            │                          │
  │   { emoji: "👍" }        │                          │
  │                          │ MessageController        │
  │                          │ .addReaction()           │
  │                          │── ReactionService ──────►│
  │                          │   .addReactionAndGetRoom()│
  │                          │   INSERT message_reactions│
  │                          │◄─────────────────────────│
  │                          │                          │
  │                          │ broadcastJsonToRoom()     │
  │                          │  { eventType: REACTION,  │
  │◄── WS event ─────────────│    messageId, emoji }    │
  │  (all users in room)     │                          │
  │                          │                          │
```

### 5.7 Friend Request → Real-Time Notification

```
Alice                  Spring Boot              Bob (online)
  │                        │                       │
  │── POST /api/friends     │                       │
  │      /request/bob ─────►│                       │
  │                        │ FriendshipService      │
  │                        │ .sendFriendRequest()   │
  │                        │── INSERT Friendship    │
  │                        │   (status=PENDING)     │
  │                        │                       │
  │                        │ NotificationService    │
  │                        │ .createNotification()  │
  │                        │── INSERT notification  │
  │                        │ [after TX commit]      │
  │                        │ pushToUser("bob", ...)│
  │◄── 200 ────────────────│───────────────────────►│
  │                        │                       WS event delivered
```

### 5.8 User Status Lifecycle

```
User Status Changes:
  Login (WS connect, non-sidebar)
    → setOnline()  [skips if manualStatusOverride = true]
    → PRESENCE event → Kafka → broadcastPresenceGlobally()

  Explicit status change (Profile page)
    → PATCH /api/profile  { status: "AWAY" }
    → UserProfileService.updateProfile()
        → user.setStatus(AWAY)
        → user.setManualStatusOverride(true)   [won't be overridden by WS]
    → broadcastPresenceGlobally(username, "AWAY")

  Set back to ONLINE (Profile page)
    → user.setManualStatusOverride(false)       [auto-status resumes]

  WS disconnect (last real-room session closed)
    → setOffline()  [skips if manualStatusOverride = true]
    → broadcastPresenceGlobally(username, "OFFLINE")

  Logout
    → forceOffline()  [ignores manualStatusOverride]
    → user.setManualStatusOverride(false)
    → broadcastPresenceGlobally(username, "OFFLINE")

Statuses: ONLINE | OFFLINE | AWAY | DND
```

---

## 6. Data Flow

### Sync vs Async Operations

| Operation | Path | Sync / Async |
|-----------|------|-------------|
| Login | REST → MySQL | **Sync** |
| Register | REST → MySQL → Email | **Sync** (email async) |
| OAuth login | OAuth redirect → MySQL → cookie | **Sync** (email async) |
| Send message | WS → Kafka → DB | **Async** (Kafka fan-out) |
| Edit message | REST → DB → WS push | **Sync** (no Kafka) |
| Delete message | REST → DB → WS push | **Sync** (no Kafka) |
| React | REST → DB → WS push | **Sync** (no Kafka) |
| Pin | REST → DB → WS push | **Sync** (no Kafka) |
| Friend request | REST → DB → WS notif | **Sync** |
| File upload | REST → Cloudinary → URL | **Sync** |
| Typing indicator | WS → Kafka → WS | **Async** (Kafka) |
| Presence (connect) | WS lifecycle → Kafka → broadcast | **Async** |
| Presence (disconnect) | WS lifecycle → direct broadcast | **Sync** (in-process) |
| Group creation | REST → DB → pushToUser | **Sync** (direct WS) |
| Unread count | Kafka consumer → pushToUser | **Async** (inside consumer) |

### Full Data Path — Chat Message

```
[1] Browser JS
    ws.send('{"sender":"alice","room":"general","message":"Hello","eventType":"MESSAGE"}')

[2] SocketConnectionHandler.handleTextMessage()
    → security guard: session room must match json room
    → build ChatMessageEvent POJO
    → ChatMessageProducer.send(event)

[3] KafkaTemplate
    → serialize to JSON (Jackson JsonSerializer)
    → send to topic "chat-messages-group", key="general"
    → partition chosen by hash("general") % 3

[4] Kafka Broker
    → appends to partition log
    → acks="all" → waits for all in-sync replicas before confirming

[5a] Consumer Group: ws-broadcast-server-1
     → ChatMessageConsumer.onMessageBroadcast(event)
     → SocketConnectionHandler.broadcastToLocalSessions(event)
     → roomSessions.get("general") → all open WebSocketSessions
     → for each session: session.sendMessage(TextMessage(json))
     → Browser renders message bubble

[5b] Consumer Group: chat-persistence
     → ChatMessageConsumer.onMessagePersist(event)
     → ChatRoomService.saveMessage()
     → INSERT INTO messages (sender, content, chatroom_id, timestamp, ...)
     → saved.getId() → broadcastJsonToRoom(MESSAGE_ID_ASSIGN)
     → Browser patches data-id on message bubble
     → pushUnreadCountToRoomMembers()  (GROUP rooms)
         → pushToUser(member, {UNREAD_COUNT, roomName, increment:1, lastMessage})
         → sidebar receives event, updates badge + preview

[5c] Consumer Group: push-notifications
     → ChatMessageConsumer.onMessagePushNotify(event) [stub]
```

---

## 7. Real-Time Updates

### Push Mechanisms

| Event | Mechanism |
|-------|-----------|
| New chat message | Kafka → wsBroadcast consumer → `broadcastToLocalSessions()` |
| Message edited | REST controller → `broadcastJsonToRoom()` directly |
| Message deleted | REST controller → `broadcastJsonToRoom()` directly |
| Emoji reaction | REST controller → `broadcastJsonToRoom()` directly |
| Pin/unpin | REST controller → `broadcastJsonToRoom()` directly |
| Typing indicator | Kafka → wsBroadcast consumer → `broadcastToLocalSessions()` |
| Presence (ONLINE) | WS connect → Kafka → `broadcastPresenceGlobally()` |
| Presence (OFFLINE) | WS disconnect (last session) → direct `broadcastPresenceGlobally()` |
| Presence (manual status) | Profile update → direct `broadcastPresenceGlobally()` |
| Notification (friend req, mention, DM) | `NotificationService` → `pushToUser()` after TX commit |
| Unread count badge (GROUP) | `chatPersistence` consumer → `pushToUser` with `UNREAD_COUNT` |
| Unread count badge (DM) | `NotificationService` → `pushToUser` with `NOTIFICATION` (includes `unreadCount`) |
| Group created (sidebar update) | `ChatController` → `pushToUser()` for each member |
| Message ID assignment | `chatPersistence` consumer → `broadcastJsonToRoom(MESSAGE_ID_ASSIGN)` |

### Kafka Monitor

`KafkaMonitorService` records every consumer event with:
- Which consumer group processed it
- The action taken (or SKIPPED with reason)
- Whether it was an error

Accessible at `/api/kafka-monitor` (ADMIN role required). The `kafka-monitor.html` page displays a live table of recent Kafka events for debugging and observability.

---

## 8. Internal Communication

### Service Dependency Map

```
AuthController
  └─► AuthService (loadUserByUsername, register, lock checks, add-password)
  └─► JwtUtil (generateToken, extractClaims)
  └─► JwtService (validateToken, invalidateToken)
  └─► WsTicketService (issueTicket)
  └─► UserProfileService (forceOffline on logout)
  └─► SocketConnectionHandler (broadcastPresenceGlobally)
  └─► EmailService (sendVerification, sendLoginNotification, sendAccountLocked)
  └─► LoginRateLimiter (isBlocked, recordFailure)

OAuth2SuccessHandler
  └─► JwtUtil (generateToken)
  └─► UserRepository (findByEmail, findByProviderAndProviderUserId)
  └─► RoleRepository (findByName USER)
  └─► EmailService (sendOAuthWelcomeEmail, sendLoginNotification)

SocketConnectionHandler
  └─► ChatMessageProducer (send to Kafka)
  └─► UserProfileService (setOnline, setOffline)
  └─► UserRepository (read status for PRESENCE event)
  └─► MessageRepository (read replyTo for broadcast JSON)

ChatMessageConsumer
  └─► SocketConnectionHandler (broadcastToLocalSessions, broadcastJsonToRoom, pushToUser)
  └─► ChatRoomService (saveMessage, getMemberUsernames)
  └─► NotificationService (createNotification for DM + @mention)
  └─► UserRepository (findByUsername for @mention lookup)
  └─► KafkaMonitorService (record every event)

ChatController
  └─► ChatRoomService (createOrGetDmRoom, createGroupRoom, getRoomsWithTypeByUserName)
  └─► SocketConnectionHandler (pushToUser for GROUP_CREATED to each member)

MessageController
  └─► MessageService (editMessage, deleteMessage, pinMessage, getPagedMessages)
  └─► ReactionService (addReaction, removeReaction)
  └─► SocketConnectionHandler (broadcastJsonToRoom — direct WS push)

NotificationService
  └─► NotificationRepository (saveAndFlush, countByRecipientAndIsReadFalse)
  └─► SocketConnectionHandler (pushToUser — called after TX commit via TransactionSynchronization)

FriendshipService
  └─► FriendshipRepository (save, find)
  └─► NotificationService (createNotification for FRIEND_REQUEST, FRIEND_ACCEPTED)

UserProfileService
  └─► UserRepository (save)
  └─► SocketConnectionHandler (broadcastPresenceGlobally on manual status change)
```

### Protocols

| Channel | Protocol | Details |
|---------|----------|---------|
| Browser ↔ Spring Boot (REST) | HTTP/1.1 | Stateless, JWT in HttpOnly cookie |
| Browser ↔ Spring Boot (realtime) | WebSocket (ws://) | Persistent, text frames, JSON payload |
| Spring Boot ↔ MySQL | JDBC (TCP) | Via HikariCP connection pool |
| Spring Boot ↔ Redis | RESP (TCP) | Spring Data Redis (token blacklist) |
| Spring Boot ↔ Kafka | Kafka protocol (TCP) | KafkaTemplate + @KafkaListener |
| Spring Boot ↔ Cloudinary | HTTPS | Cloudinary Java SDK |
| Spring Boot ↔ SMTP | SMTP (TCP) | JavaMailSender (`@Async`) |

---

## 9. Sequence Diagrams

### Full Message Flow (Multi-Instance)

```
Browser(Alice)   AppServer-1   Kafka-Broker    AppServer-2   Browser(Bob)
     │               │               │               │              │
     │──WS send──────►│               │               │              │
     │ {msg:"Hello"}  │               │               │              │
     │               │──produce──────►│               │              │
     │               │  key=roomName  │               │              │
     │               │               │──consume──────►│              │
     │               │               │ ws-broadcast-2 │              │
     │               │               │               │─broadcastTo──►│
     │               │               │               │  LocalSessions│
     │               │               │──consume──────►│              │
     │               │               │ ws-broadcast-1 │              │
     │               │◄──────────────│               │              │
     │◄─broadcastTo──│               │               │              │
     │  LocalSessions│               │               │              │
     │               │               │──consume──────►│              │
     │               │               │ chat-persistence              │
     │               │               │──saveMessage──►│              │
     │               │               │ INSERT MySQL   │              │
     │               │               │               │              │
     │               │◄─backfill─────│ MESSAGE_ID_ASSIGN             │
     │◄──────────────│               │  via broadcastJsonToRoom      │
     │ {eventType:   │               │               │              │
     │  MSG_ID_ASSIGN│               │               │              │
     │  messageId:42}│               │               │              │
     │               │               │               │              │
     │               │  UNREAD_COUNT─│               │─pushToUser──►│
     │               │  (GROUP rooms)│               │ sidebar badge│
```

### Authentication + WebSocket Handshake

```
Browser         JwtAuthFilter   AuthController   WsTicketService   WebSocketConfig
   │                  │               │                │                 │
   │──POST /login ────►│               │                │                 │
   │                  │──passthrough──►│                │                 │
   │                  │               │──generateJWT───│                 │
   │                  │               │──set cookie─────────────────────►│
   │◄─200 + cookie────│               │                │                 │
   │                  │               │                │                 │
   │──POST /ws-ticket─►│               │                │                 │
   │  (cookie auto    │──validates JWT►│                │                 │
   │   sent)          │               │──issueTicket───►│                 │
   │                  │               │                │ ConcurrentHashMap│
   │◄─{ ticket }──────│               │                │  ticket→username │
   │                  │               │                │                 │
   │──WS /ws?ticket=..►│(no JWT filter)│                │─consumeTicket───►│
   │                  │               │                │ removes ticket  │
   │                  │               │                │ (one-time)      │
   │                  │               │                │◄─username───────│
   │◄─101 Upgrade─────│               │                │  attributes set │
```

### Notification Flow

```
Actor(Alice)    FriendshipService   NotificationService   SocketHandler   Bob's Browser
    │                 │                    │                    │               │
    │─sendFriendReq──►│                    │                    │               │
    │                 │─INSERT Friendship──►│                    │               │
    │                 │─createNotification─►│                    │               │
    │                 │                    │─INSERT notification─►│               │
    │                 │                    │ [after TX commit]   │               │
    │                 │                    │─pushToUser("bob")───►│               │
    │                 │                    │                    │─sendMessage───►│
    │◄─200────────────│                    │                    │  { eventType:  │
    │                 │                    │                    │  NOTIFICATION, │
    │                 │                    │                    │  unreadCount,  │
    │                 │                    │                    │  content:...}  │
```

---

## 10. Design Decisions

### 1. HttpOnly Cookie for JWT (not Authorization header)
**Why:** The JWT is never readable by JavaScript. It cannot be stolen via XSS. The token travels automatically with every request. On logout, it is both blacklisted server-side and cleared from the browser.

### 2. One-Time WsTicket for WebSocket Auth
**Why:** WebSocket connections are established via a browser URL. If the JWT were in the URL, it would appear in browser history and server access logs. A 30-second one-time ticket eliminates all these attack surfaces. The ticket is consumed on first use.

### 3. Kafka for Chat Messages (not direct broadcast)
**Why:** Without Kafka, a message sent to server A would only reach users connected to A. Kafka ensures all server instances receive all messages and fan out to their local WebSocket clients — enabling horizontal scaling.

### 4. Two Kafka Topics (DM vs Group)
**Why:** DM traffic and group traffic have different throughput and ordering needs. Separating them prevents a busy group channel from delaying private DMs.

### 5. Three Consumer Groups on Same Topics
- `ws-broadcast-{id}`: **Unique** per instance → ALL instances receive ALL messages (fan-out)
- `chat-persistence`: **Shared** → only ONE instance writes to MySQL (prevents duplicate rows)
- `push-notifications`: **Shared** → only ONE instance sends the push (prevents duplicate notifications)

### 6. Direct WebSocket Push for Edit/Delete/React/Pin
**Why:** These are REST operations where the mutation is already persisted synchronously. Going through Kafka would add 5–50ms latency for no benefit. `broadcastJsonToRoom()` delivers in microseconds.

### 7. Sidebar WebSocket Room (`__sidebar__`)
**Why:** The sidebar needs real-time updates (new groups, unread counts, presence) but must NOT trigger ONLINE/OFFLINE presence events. A dedicated receive-only virtual room achieves this cleanly without any extra connection management.

### 8. OAuth Users — No Forced Password
**Why:** This is the correct production behavior (Facebook, Slack, GitHub all do this). OAuth users are authenticated by the provider. Forcing a password setup after Google registration is both unnecessary and a poor UX. The optional `POST /api/auth/add-password` endpoint lets users add a local password voluntarily.

### 9. Soft-Delete for Messages
**Why:** Hard-deleting rows would break reply threads, reaction history, and audit trails. `isDeleted=true` preserves the row while hiding content. The consumer/service returns `[deleted]` display text.

### 10. Per-User Account Lockout (DB-persisted) + IP Rate Limiting (in-memory)
- **Per-user (DB):** Survives server restarts. Attacker can't bypass by triggering a restart.
- **Per-IP (in-memory):** Fast, no DB hit. Protects against distributed password spraying.

### 11. Manual Status Override
**Why:** Users who set AWAY or DND expect that status to stick. The `manualStatusOverride` flag ensures WS connect/disconnect events don't automatically overwrite a user's intentional status. Only explicit logout or setting ONLINE clears this flag.

### 12. Notification WS Push After Transaction Commit
**Why:** `NotificationService` uses `TransactionSynchronizationManager.registerSynchronization()` to push the WebSocket notification AFTER the DB transaction commits. This prevents pushing a notification ID that might not yet be visible to the recipient's DB query due to transaction isolation.

---

## Entity Relationship Summary

```
User ──────┬────── ChatRoomUser ──── ChatRoom
           │           │                 │
           │       (isGroupAdmin)     Messages
           │                             │
           │                        MessageReactions
           │
           ├────── Friendship (PENDING/ACCEPTED/BLOCKED)
           ├────── Notification (MESSAGE/MENTION/FRIEND_REQUEST/FRIEND_ACCEPTED)
           ├────── EmailVerificationToken
           ├────── PasswordResetToken
           └────── Role (USER/ADMIN/MODERATOR)

Message self-join: message.replyTo → message (thread parent)
```

---

## Configuration Quick Reference

| Property | Default | Purpose |
|----------|---------|---------|
| `server.instance.id` | `server-1` | Unique ID per replica, used in `ws-broadcast-{id}` consumer group |
| `kafka.ws-broadcast.group-id` | `ws-broadcast-server-1` | Must differ between instances |
| `jwt.secret` | (long default) | HMAC-SHA256 key, min 64 chars |
| `app.email.enabled` | `true` | Toggle email sending |
| `app.base-url` | `http://localhost:8080` | Used in email links |
| `app.cors.allowed-origins` | `http://localhost:8080,http://localhost:3000` | CORS whitelist |
| `spring.data.redis.*` | localhost:6379 | Token blacklist store |
| `spring.kafka.bootstrap-servers` | localhost:9092 | Kafka broker address |
| `cloudinary.*` | via env | File upload CDN |
| `spring.security.oauth2.client.registration.google.*` | via env | Google OAuth credentials |
| `spring.security.oauth2.client.registration.github.*` | via env | GitHub OAuth credentials |
