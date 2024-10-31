# Project Title

## Overview
This project implements a real-time chat application with secure communication features using WebSocket and JWT for authentication. It allows users to register, log in, create chat rooms, send messages, and upload files while ensuring message privacy through end-to-end encryption.


## Features

- **End-to-End Encryption**:
    - Messages are secured using a robust key mechanism, ensuring that only the intended recipients can read them.

- **Secure Communication**:
    - Implemented JSON Web Tokens (JWT) to prevent unauthorized access and ensure secure communication between users.

- **Real-Time, Event-Driven Communication**:
    - Supports real-time messaging through WebSocket, allowing for dynamic and responsive user interactions.

- **File Uploads:** Support for file sharing within chat rooms.


# Installation
## Getting Started

Follow these steps to set up and run the Spring Boot application.

### Prerequisites

- **Java 17** (or higher)
- **Maven** (version 3.8.1 or higher)

### Clone the repository:
   ```bash
   git clone https://github.com/unique6246/web-socket.git

   cd web-socket
   ```
### Build the application:
   ```bash
   mvn clean install
   ```
### Run the application:
   ```bash
  mvn spring-boot:run
   ```

### Access the application:
   ```bash
  http://localhost:8080/index.html
   ```


# API Reference (Backend API's)

### User Authentication

#### User Login
```http
POST /api/auth/login
```

| Parameter | Type     | Description                |
| :-------- | :------- | :------------------------- |
| `username` | `string` | **Required**. User's username.|
| `password` | `string` | **Required**. User's password.|


#### User Registration

```http
POST /api/auth/register
```

| Parameter | Type     | Description                       |
| :-------- | :------- | :-------------------------------- |
| `username` | `string` | **Required**. User's username.|
| `password` | `string` | **Required**. User's password.|

### Chat Room
#### Create Chat Room

```http
POST /api/chat/rooms/create/{roomName}
```
| Parameter | Type     | Description                       |
| :-------- | :------- | :-------------------------------- |
| `roomName` | `string` | **Required**. The name of the chat room.
| `Authorization` | `string` | **Required**. Bearer token for user authentication.|

#### Get All Rooms for User

```http
GET /api/rooms
```
| Parameter | Type     | Description                       |
| :-------- | :------- | :-------------------------------- |
| `Authorization` | `string` | **Required**. Bearer token for user authentication.|

#### Get Chat History Messages

```http
GET /api/chat/history/{roomName}
```
| Parameter | Type     | Description                       |
| :-------- | :------- | :-------------------------------- |
| `roomName` | `string` | **Required**. The name of the chat room.
| `Authorization` | `string` | **Required**. Bearer token for user authentication.|





### File Upload

#### Upload File

```http
POST /api/files/upload
```
| Parameter | Type     | Description                       |
| :-------- | :------- | :-------------------------------- |
| `file` | `string` | **Required**. The file to upload.
| `room` | `string` | **Required**. The name of the chat room associated with the file.
| `Authorization` | `string` | **Required**. Bearer token for user authentication.|

# Web-socket

The chat application uses WebSocket for real-time communication, allowing users to send and receive messages instantly. Below are the key components and methods for managing WebSocket connections.

```
•ws://localhost:8080/ws: The WebSocket server endpoint.
```
### onopen Event
`Triggered when the connection is successfully established`

*socket.onopen = function ()*

### onmessage Event
`Triggered when a message is received from the server.`

*socket.onmessage = function (event)*

### onclose  Event
`Triggered when the connection is closed.`

*socket.onclose = function ()*

### Reference Documentation

For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/3.3.5/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/3.3.5/maven-plugin/build-image.html)
* [Spring Web](https://docs.spring.io/spring-boot/3.3.5/reference/web/servlet.html)
* [WebSocket](https://docs.spring.io/spring-boot/3.3.5/reference/messaging/websockets.html)

### Guides

The following guides illustrate how to use some features concretely:

* [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
* [Serving Web Content with Spring MVC](https://spring.io/guides/gs/serving-web-content/)
* [Building REST services with Spring](https://spring.io/guides/tutorials/rest/)
* [Using WebSocket to build an interactive web application](https://spring.io/guides/gs/messaging-stomp-websocket/)



