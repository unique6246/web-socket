// Utility functions
function getSessionToken() {
    return sessionStorage.getItem("token");
}

function getSessionUsername() {
    return sessionStorage.getItem("username");
}

function fetchWithAuth(url, options = {}) {
    options.headers = {
        ...options.headers,
        'Authorization': `Bearer ${getSessionToken()}`,
    };
    return fetch(url, options).then(response => {
        if (!response.ok) throw new Error("Failed request");
        return response;
    });
}

function handleError(error, customMessage = "An error occurred") {
    alert(`${customMessage}: ${error.message}`);
}

let pollingInterval; // To store the polling interval ID

// Login and Registration Handlers
function handleLogin(e) {
    e.preventDefault();
    const username = document.getElementById("username").value;
    const password = document.getElementById("password").value;

    fetch("http://localhost:8080/api/auth/login", {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
    })
        .then(response => {
            if (!response.ok) throw new Error("Login failed");
            return response.json();
        })
        .then(data => {
            sessionStorage.setItem("token", data.token);
            sessionStorage.setItem("username", username);
            window.location.href = "chat.html";
        })
        .catch(error => handleError(error, "Login failed"));
}

function handleRegister(e) {
    e.preventDefault();
    const username = document.getElementById("newUsername").value;
    const password = document.getElementById("newPassword").value;

    fetch("http://localhost:8080/api/auth/register", {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
    })
        .then(response => {
            if (!response.ok) throw new Error("Registration failed");
            return response.text();
        })
        .then(message => {
            alert(message);
            window.location.href = "login.html";
        })
        .catch(error => handleError(error, "Registration failed"));
}

function createRoom() {
    const roomName = document.getElementById("roomNameInput").value;
    if (!roomName) return alert("Room name is required");

    fetchWithAuth(`http://localhost:8080/api/chat/rooms/create/${roomName}`, { method: 'POST' })
        .then(response => response.json())
        .then(data => {
            alert(`Room '${data.roomName}' created successfully`);
            return loadInitialRoomMessages();
        })
        .catch(error => handleError(error, "Room creation failed"));
}

function displayRooms(rooms) {
    const roomList = document.getElementById("roomList");
    roomList.innerHTML = rooms.length ? '' : "No rooms available.";

    rooms.forEach(room => {
        const roomButton = document.createElement("button");
        roomButton.textContent = room.roomName;
        roomButton.className = "room-button";
        roomButton.addEventListener("click", () => startChat(room.roomName));
        roomList.appendChild(roomButton);
    });
}

// Chat Management
let socket;
let currentRoomName = null;

function startChat(roomName) {
    if (!getSessionToken() || !getSessionUsername()) return alert("Please log in to join a room");

    if (currentRoomName !== null) return alert("You must leave the current room before joining another one.");

    socket = new WebSocket(`ws://localhost:8080/ws?token=${getSessionToken()}&roomName=${roomName}`);

    socket.onopen = () => initializeChat(roomName);
    socket.onmessage = event => handleIncomingMessage(event);
    socket.onclose = () => endChat();
}

function initializeChat(roomName) {
    document.getElementById("chatWindow").style.display = "block";
    document.getElementById("chatRoomTitle").textContent = `Chat Room: ${roomName}`;
    document.getElementById("leaveRoomButton").style.display = "inline";

    document.getElementById("messages").innerHTML = '';
    currentRoomName = roomName;
    loadChatHistory(roomName);

    const sendMessageButton = document.getElementById("sendMessageButton");
    sendMessageButton.onclick = sendMessage;

    // Start polling for new messages
}

function handleIncomingMessage(event) {
    const message = JSON.parse(event.data);
    const roomName = message.roomName;

    if (roomName === currentRoomName) {
        // Display the message in the chat
        if (message.fileUrl && message.fileUrl.startsWith("/uploads/")) {
            displayFileMessage(message.sender, message.fileUrl, message.sender === getSessionUsername());
        } else {
            displayMessage(message.sender, message.message, message.sender === getSessionUsername());
        }
    }
    return refreshRoomList(message.room);

}
function refreshRoomList(activeRoomName) {
    return loadInitialRoomMessages().then(() => {
        // Move the active room to the top of the list
        const roomList = document.getElementById("roomList");
        const rooms = Array.from(roomList.children);
        const activeRoom = rooms.find(room => room.textContent === activeRoomName);

        if (activeRoom) {
            roomList.prepend(activeRoom); // Move active room to the top
        }
    });
}


function sendMessage() {
    const messageText = document.getElementById("messageInput").value.trim();
    if (!messageText) return alert("Message cannot be empty.");
    const message = { sender: getSessionUsername(), room: currentRoomName, message: messageText, isFile: false };

    if (socket?.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(message));
        document.getElementById("messageInput").value = ''; // Clear input after sending
    } else {
        console.error("Cannot send message; WebSocket is not open");
    }
}

function endChat() {
    if (socket) {
        socket.close(); // Ensure WebSocket is closed
        socket = null;
    }
    document.getElementById("chatWindow").style.display = "none";
    document.getElementById("leaveRoomButton").style.display = "none";
    document.getElementById("messages").innerHTML = ''; // Clear messages
    currentRoomName = null;

    }

// File Upload Handler
function handleFileUpload(event) {
    const file = event.target.files[0];
    if (!file) return;

    const formData = new FormData();
    formData.append("file", file);
    formData.append("room", currentRoomName);

    fetchWithAuth("http://localhost:8080/api/files/upload", {
        method: "POST",
        body: formData,
    })
        .then(response => response.json())
        .then(data => sendFileMessage(data.fileUrl, data.fileName, data.type, true))
        .catch(error => console.error("File upload failed:", error));
}

function sendFileMessage(fileUrl, name, type, isFile) {
    if (socket?.readyState === WebSocket.OPEN) {
        const file = { sender: getSessionUsername(), room: currentRoomName, fileUrl: fileUrl, fileName: name, fileType: type, isFile: isFile };
        socket.send(JSON.stringify(file));
    }
}

// Display Helpers
function displayMessage(sender, messageText, isSender = false) {
    const messageElement = document.createElement("div");
    messageElement.className = `message ${isSender ? "sender" : "receiver"}`;
    messageElement.textContent = `${isSender ? "You" : sender}: ${messageText}`;
    appendMessage(messageElement);
}

function displayFileMessage(sender, fileUrl, isSender = false) {
    const fileMessageElement = document.createElement("div");
    fileMessageElement.className = `message ${isSender ? "sender" : "receiver"}`;

    const link = document.createElement("a");
    link.href = fileUrl;
    link.target = "_blank";
    link.textContent = isSender ? `You sent a file: ${fileUrl.split("/").pop()}` : `${sender} sent a file: ${fileUrl.split("/").pop()}`;

    fileMessageElement.appendChild(link);
    appendMessage(fileMessageElement);
}

function appendMessage(messageElement) {
    const messagesDiv = document.getElementById("messages");
    messagesDiv.appendChild(messageElement);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

// Load Chat History
function loadChatHistory(roomName) {
    fetchWithAuth(`http://localhost:8080/api/chat/history/${roomName}`)
        .then(response => response.json())
        .then(messages => {
            messages.forEach(msg => {
                if (msg.fileUrl && msg.fileUrl.startsWith("/uploads/")) {
                    displayFileMessage(msg.sender, msg.fileUrl, msg.sender === getSessionUsername());
                } else {
                    displayMessage(msg.sender, msg.content, msg.sender === getSessionUsername());
                }
            });
        })
        .catch(error => console.error("Error loading chat history:", error));
}

// Event Listeners
document.getElementById("loginForm")?.addEventListener("submit", handleLogin);
document.getElementById("registerForm")?.addEventListener("submit", handleRegister);
document.getElementById("createRoomButton")?.addEventListener("click", createRoom);
document.getElementById("fileInput")?.addEventListener("change", handleFileUpload);
document.getElementById("leaveRoomButton")?.addEventListener("click", endChat);
document.getElementById("attachFileButton")?.addEventListener("click", () => document.getElementById("fileInput").click());
document.addEventListener("DOMContentLoaded", () => {
    return loadInitialRoomMessages();
});
function loadInitialRoomMessages() {
    return fetchWithAuth("http://localhost:8080/api/rooms") // Adjust this URL as necessary to get room messages
        .then(response => response.json())
        .then(rooms => {displayRooms(rooms)})
        .catch(error => console.error("Error loading rooms:", error));
}