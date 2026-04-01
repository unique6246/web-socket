# 💬 ChatApp — Real-Time WebSocket Chat

A full-featured real-time chat application built with **Spring Boot 3**, **WebSocket**, **Apache Kafka**, **Redis**, **MySQL**, and **Cloudinary**. Supports DMs, group rooms, friend requests, file sharing, notifications, moderation tools, and a full admin dashboard.

---

## 🚀 Features

| Category | Features |
|---|---|
| **Authentication** | JWT (HttpOnly cookie), registration, email verification, password reset, login rate limiting |
| **Real-Time Messaging** | WebSocket (raw), Kafka fan-out for multi-instance broadcasting, typing indicators, presence (online/away/DND/offline) |
| **Rooms** | 1-on-1 DMs, named group rooms, admin-managed member roles, leave/remove members |
| **Messages** | Send, edit, soft-delete, reply-to, pin, search, chat history |
| **Reactions** | Emoji reactions on messages with live counts |
| **Friends** | Send/accept/decline/cancel friend requests, unfriend, pending request badges, real-time notifications |
| **Notifications** | In-app bell, unread count badge, mark-read / mark-all-read, real-time WebSocket push |
| **File Uploads** | Images, video, audio, generic files via Cloudinary; inline preview in chat |
| **User Profiles** | Display name, bio, phone, avatar upload, status |
| **Moderation** | Moderator tools per room, message pin/delete by moderators |
| **Admin Dashboard** | Manage users (create/delete/assign roles), manage chat rooms, view stats |

---

## 🛠 Tech Stack

| Layer | Technology |
|---|---|
| **Backend** | Java 17, Spring Boot 3.3.5, Spring Security, Spring WebSocket |
| **Messaging** | Apache Kafka 7.6 (Confluent), Zookeeper |
| **Database** | MySQL 8.0 + Spring Data JPA / Hibernate |
| **Cache / Session** | Redis 7 (token blacklist, presence, session store) |
| **File Storage** | Cloudinary (images, video, audio, files) |
| **Auth** | JWT (jjwt 0.11.5), HttpOnly cookies |
| **Email** | Spring Mail + MailHog (dev), configurable SMTP (prod) |
| **Frontend** | Vanilla HTML/CSS/JS (no framework) |
| **Build** | Maven 3, Docker, Docker Compose |

---

## 📁 Project Structure

```
src/main/
├── java/com/example/websocket/
│   ├── admin/              # AdminController — user & room management
│   ├── config/             # Security, WebSocket, CORS, JWT filter, rate limiter
│   ├── controller/         # Auth, Chat, Message, Friends, Notifications, Profile, Moderation
│   ├── fileStorage/        # Cloudinary file upload service
│   ├── JWT/                # JwtUtil, JwtService, WsTicketService
│   ├── kafka/              # Kafka producers & consumers (chat, notifications, broadcast)
│   ├── model/              # JPA entities (User, Message, ChatRoom, Friendship, …)
│   ├── repo/               # Spring Data repositories
│   └── service/            # Business logic services
└── resources/
    ├── application.properties
    └── static/             # Frontend (chat.html, dashboard.html, profile.html, script.js, style.css, …)
```

---

## ⚙️ Prerequisites

- **Java 17+**
- **Maven 3.8+**
- **Docker & Docker Compose** (recommended for full stack)
- **Cloudinary account** (for file uploads)

---

## 🏃 Running Locally

### Option 1 — Docker Compose (Recommended)

Starts the app + MySQL + Kafka + Zookeeper + Redis + MailHog + Kafka UI in one command.

**1. Create a `.env` file** in the project root:

```env
# Database
DB_NAME=otdb
DB_USERNAME=chatuser
DB_PASSWORD=yourpassword
MYSQL_ROOT_PASSWORD=rootpassword

# JWT
JWT_SECRET=your-very-long-secret-key-at-least-64-characters

# Cloudinary
CLOUDINARY_CLOUD_NAME=your_cloud_name
CLOUDINARY_API_KEY=your_api_key
CLOUDINARY_API_SECRET=your_api_secret

# App
APP_BASE_URL=http://localhost:8085
EMAIL_ENABLED=true
```

**2. Build & run:**

```bash
docker compose up --build
```

**3. Access the app:**

| Service | URL |
|---|---|
| ChatApp | http://localhost:8085 |
| Kafka UI | http://localhost:8090 |
| MailHog (email) | http://localhost:8025 |
| MySQL | localhost:3306 |

---

### Option 2 — Run Locally (without Docker)

Ensure MySQL, Redis, and Kafka are running locally, then:

```bash
# Clone
git clone https://github.com/unique6246/web-socket.git
cd web-socket

# Build
mvn clean install

# Run (with env vars or defaults from application.properties)
mvn spring-boot:run
```

Access at: **http://localhost:8080**

---

## 🌐 Pages

| Page | URL |
|---|---|
| Login | `/api/v1/login` |
| Register | `/api/v1/register` |
| Chat | `/api/v1/chat` |
| Profile | `/api/v1/profile` |
| Admin Dashboard | `/api/v1/dashboard` _(ADMIN role required)_ |
| Forgot Password | `/api/v1/forgot-password` |

---

## 📡 API Reference

All authenticated endpoints use an **HttpOnly JWT cookie** (set automatically on login). No `Authorization` header needed.

---

### 🔐 Auth — `/api/auth`

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/api/auth/register` | ❌ | Register a new user |
| `POST` | `/api/auth/login` | ❌ | Login — sets HttpOnly JWT cookie |
| `POST` | `/api/auth/logout` | ✅ | Logout — blacklists token, clears cookie |
| `GET` | `/api/auth/me` | ✅ | Get current user's session info & roles |
| `POST` | `/api/auth/ws-ticket` | ✅ | Get a one-time WebSocket connection ticket |
| `POST` | `/api/auth/forgot-password` | ❌ | Send password reset email |
| `POST` | `/api/auth/reset-password` | ❌ | Reset password with token |
| `GET` | `/api/auth/verify-email` | ❌ | Verify email address |
| `POST` | `/api/auth/change-password` | ✅ | Change password (authenticated) |

**Register body:**
```json
{ "username": "alice", "email": "alice@example.com", "password": "Secret@123", "displayName": "Alice" }
```

**Login body:**
```json
{ "username": "alice", "password": "Secret@123" }
```

---

### 💬 Chat Rooms — `/api/chat`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/chat/users` | List all users (People tab) |
| `GET` | `/api/chat/my-rooms` | List rooms the current user is in |
| `POST` | `/api/chat/rooms/dm/{username}` | Start or retrieve a DM with a user |
| `POST` | `/api/chat/rooms/group` | Create a named group room |
| `GET` | `/api/chat/rooms/{roomName}/members` | List members of a room |
| `GET` | `/api/chat/rooms/{roomName}/my-role` | Get caller's role in the room |
| `POST` | `/api/chat/rooms/create/{roomName}` | Create / join a room (legacy) |
| `POST` | `/api/chat/rooms/{roomName}/add-member/{username}` | Add a member (group admin only) |
| `DELETE` | `/api/chat/rooms/{roomName}/remove-member/{username}` | Remove a member (group admin only) |
| `DELETE` | `/api/chat/rooms/{roomName}/leave` | Leave a group room |

---

### 📨 Messages — `/api/messages`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/messages/room/{roomName}?limit=50` | Get chat history |
| `GET` | `/api/messages/room/{roomName}/search?q=text` | Search messages in a room |
| `GET` | `/api/messages/room/{roomName}/pinned` | Get pinned messages |
| `PATCH` | `/api/messages/{id}` | Edit a message |
| `DELETE` | `/api/messages/{id}` | Soft-delete a message |
| `POST` | `/api/messages/{id}/pin` | Pin / unpin a message |
| `POST` | `/api/messages/{id}/react` | Add / toggle an emoji reaction |
| `GET` | `/api/messages/{id}/reactions` | Get reaction counts for a message |

---

### 🤝 Friends — `/api/friends`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/friends` | Get accepted friends list |
| `GET` | `/api/friends/pending` | Get incoming pending friend requests |
| `POST` | `/api/friends/request/{username}` | Send a friend request |
| `POST` | `/api/friends/accept/{requestId}` | Accept a friend request |
| `DELETE` | `/api/friends/decline/{requestId}` | Decline / cancel / unfriend |
| `POST` | `/api/friends/block/{username}` | Block a user |

---

### 🔔 Notifications — `/api/notifications`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/notifications?page=0&limit=20` | Get paginated notifications |
| `GET` | `/api/notifications/unread-count` | Get unread count |
| `PATCH` | `/api/notifications/{id}/read` | Mark a notification as read |
| `PATCH` | `/api/notifications/read-all` | Mark all notifications as read |

---

### 👤 User Profile — `/api/users`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/users/me/profile` | Get current user's profile |
| `PUT` | `/api/users/me/profile` | Update profile (displayName, bio, phone, status) |
| `POST` | `/api/users/me/avatar` | Upload avatar image |
| `GET` | `/api/users/{username}/profile` | Get any user's public profile |
| `GET` | `/api/users/search?q=term` | Search users by username or display name |

---

### 📁 File Upload — `/api/files`

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/files/upload` | Upload a file to Cloudinary (multipart/form-data) |

Returns `{ fileUrl, fileName, type }` — the URL is sent via WebSocket to appear inline in chat.

---

### 🛡 Admin — `/admin` _(ADMIN role required)_

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/admin/stats` | Total users, rooms, messages |
| `GET` | `/admin/users` | List all users |
| `POST` | `/admin/users` | Create a user |
| `DELETE` | `/admin/users/{id}` | Delete a user |
| `PUT` | `/admin/users/{username}/roles/assign` | Assign a role |
| `PUT` | `/admin/users/{username}/roles/remove` | Remove a role |
| `GET` | `/admin/chatrooms` | List all chat rooms |
| `POST` | `/admin/chatrooms` | Create a chat room |
| `DELETE` | `/admin/chatrooms/{id}` | Delete a chat room |

---

## 🔌 WebSocket

Connect after obtaining a one-time ticket from `POST /api/auth/ws-ticket`:

```
ws://localhost:8080/ws?ticket=<ticket>&roomName=<room>
```

### Outbound (client → server)

```json
// Send a message
{ "sender": "alice", "room": "general", "message": "Hello!" }

// Reply to a message
{ "sender": "alice", "room": "general", "message": "Got it", "replyToMessageId": 42 }

// Typing indicator
{ "eventType": "TYPING", "room": "general", "isTyping": true }

// Send a file (after uploading via REST)
{ "sender": "alice", "room": "general", "fileUrl": "https://...", "fileName": "photo.jpg", "fileType": "image/jpeg" }
```

### Inbound event types (server → client)

| `eventType` | Description |
|---|---|
| `MESSAGE` | New chat message |
| `MESSAGE_EDIT` | Message was edited |
| `MESSAGE_DELETE` | Message was soft-deleted |
| `MESSAGE_ID_ASSIGN` | DB id assigned to an optimistically-rendered message |
| `TYPING` | Typing indicator update |
| `PRESENCE` | User online/away/DND/offline status change |
| `REACTION` | Emoji reaction updated |
| `PIN` | Message pinned/unpinned |
| `NOTIFICATION` | In-app notification (friend request, accept, mention, etc.) |
| `UNREAD_COUNT` | Unread message count for a room |

---

## 🔑 Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | MySQL host |
| `DB_PORT` | `3306` | MySQL port |
| `DB_NAME` | `otdb` | Database name |
| `DB_USERNAME` | `root` | DB username |
| `DB_PASSWORD` | _(required)_ | DB password |
| `JWT_SECRET` | _(long default)_ | JWT signing secret (min 64 chars) |
| `CLOUDINARY_CLOUD_NAME` | _(required)_ | Cloudinary cloud name |
| `CLOUDINARY_API_KEY` | _(required)_ | Cloudinary API key |
| `CLOUDINARY_API_SECRET` | _(required)_ | Cloudinary API secret |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `KAFKA_HOST` | `localhost` | Kafka broker host |
| `KAFKA_PORT` | `9092` | Kafka broker port |
| `SMTP_HOST` | `localhost` | SMTP server host |
| `SMTP_PORT` | `1025` | SMTP server port |
| `EMAIL_ENABLED` | `false` | Enable email sending |
| `APP_BASE_URL` | `http://localhost:8080` | Base URL (used in email links) |
| `INSTANCE_ID` | `server-1` | Unique ID per app replica (for Kafka consumer groups) |
| `SERVER_PORT` | `8080` | Spring Boot server port |

---

## 🏗 Architecture

```
Browser
  │
  ├── REST (HTTP + HttpOnly JWT Cookie)
  │     └── Spring Boot Controllers
  │
  └── WebSocket (ws-ticket auth)
        └── SocketConnectionHandler
              │
              ├── Kafka Producer  ──► chat-messages topic
              │                         │
              │                         ├── chat-persistence consumer  → MySQL
              │                         ├── ws-broadcast consumer      → all WebSocket sessions
              │                         └── push-notifications consumer → Notification table
              │
              └── Direct broadcast for: edits, deletes, reactions, pins, typing, presence
```

---

## 📄 License

This project is for educational / portfolio purposes.

---

## 👤 Author

**unique6246** — [github.com/unique6246](https://github.com/unique6246)
