// Login handling
document.getElementById("loginForm")?.addEventListener("submit", function (e) {
    e.preventDefault();
    const username = document.getElementById("username").value;
    const password = document.getElementById("password").value;

    fetch("http://localhost:8080/api/auth/login", {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
    })
        .then(response => response.json())
        .then(data => {
            sessionStorage.setItem("token", data.token);
            sessionStorage.setItem("username", username);
            window.location.href = "chat.html";
        })
        .catch(error => alert("Login failed: " + error.message));
});

// Registration handling
document.getElementById("registerForm")?.addEventListener("submit", function (e) {
    e.preventDefault();
    const username = document.getElementById("newUsername").value;
    const password = document.getElementById("newPassword").value;

    fetch("http://localhost:8080/api/auth/register", {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
    })
        .then(response => response.text())
        .then(message => {
            alert(message);
            window.location.href = "login.html";
        })
        .catch(error => alert("Registration failed: " + error.message));
});

// Room creation
document.getElementById("createRoomButton")?.addEventListener("click", function () {
    const roomName = document.getElementById("roomNameInput").value;
    if (!roomName) return alert("Room name is required");

    fetch(`http://localhost:8080/api/chat/rooms/create/${roomName}`, {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${sessionStorage.getItem("token")}` }
    })
        .then(response => response.json())
        .then(data => {
            alert(`Room '${data.roomName}' created successfully`);
            loadRooms(); // Load the updated list of rooms if needed
        })
        .catch(error => alert("Room creation failed: " + error.message));
});

// Load rooms and display them as clickable buttons
function loadRooms() {
    fetch("http://localhost:8080/api/rooms", {
        headers: { 'Authorization': `Bearer ${sessionStorage.getItem("token")}` }
    })
        .then(response => response.json())
        .then(rooms => {
            const roomList = document.getElementById("roomList");
            roomList.innerHTML = ''; // Clear existing rooms
            if (rooms.length === 0) {
                roomList.textContent = "No rooms available.";
            } else {
                rooms.forEach(room => {
                    const roomButton = document.createElement("button");
                    roomButton.textContent = room.roomName;
                    roomButton.className = "room-button";
                    roomButton.addEventListener("click", () => startChat(room.roomName));
                    roomList.appendChild(roomButton);
                });
            }
        })
        .catch(error => console.error("Failed to load rooms:", error));
}

// Global WebSocket and room state
let socket;
let currentRoomName = null;

// Function to start chat in a selected room
function startChat(roomName) {
    const token = sessionStorage.getItem("token");
    const username = sessionStorage.getItem("username");

    if (!token || !username) {
        alert("Please log in to join a room");
        return;
    }

    // Prevent entering a new room if already connected to one
    if (currentRoomName) {
        alert("You must leave the current room before joining another one.");
        return;
    }

    // Initialize WebSocket with token and roomName
    socket = new WebSocket(`ws://localhost:8080/ws?token=${token}&roomName=${roomName}`);

    socket.onopen = function () {
        console.log("Connected to room:", roomName);
        document.getElementById("chatWindow").style.display = "block";
        document.getElementById("chatRoomTitle").textContent = `Chat Room: ${roomName}`;
        document.getElementById("leaveRoomButton").style.display = "inline"; // Show the leave button

        // Clear previous messages and load chat history for the new room
        document.getElementById("messages").innerHTML = '';
        loadChatHistory(roomName);

        // Set current room name to track the active chat room
        currentRoomName = roomName;
    };

    socket.onmessage = function (event) {
        console.log("Received message:", event.data);
        const message = JSON.parse(event.data);
        displayMessage(message.sender, message.message, message.sender === username);
    };

    socket.onclose = function () {
        console.log("Disconnected from room:", roomName);
        document.getElementById("chatWindow").style.display = "none";
        document.getElementById("leaveRoomButton").style.display = "none";
        currentRoomName = null;
    };

    // Remove previous click listener and add new listener for sending messages
    const sendMessageButton = document.getElementById("sendMessageButton");
    sendMessageButton.removeEventListener("click", sendMessageHandler);
    sendMessageButton.addEventListener("click", sendMessageHandler);
}

// Function to handle sending messages
function sendMessageHandler() {
    const messageInput = document.getElementById("messageInput");
    const messageText = messageInput.value.trim(); // Trim whitespace from input

    if (!messageText) {
        alert("Message cannot be empty."); // Optionally, display an alert or handle it as needed
        return; // Exit the function if the message is empty
    }

    const message = {
        sender: sessionStorage.getItem("username"),
        room: currentRoomName,
        message: messageText
    };

    console.log("Sending message:", message);
    if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(message));
        // displayMessage("You", message.message, true);
        messageInput.value = '';
    } else {
        console.error("Cannot send message; WebSocket is not open");
    }
}

// Function to leave the chat room
function leaveChat() {
    if (socket && socket.readyState === WebSocket.OPEN) {
        socket.close();
        console.log("Left chat room:", currentRoomName);
    }

    // Clear the room name to allow joining a new room
    currentRoomName = null;
    document.getElementById("chatWindow").style.display = "none";
    document.getElementById("leaveRoomButton").style.display = "none"; // Hide leave button
}

// Attach leaveChat to the Leave button
document.getElementById("leaveRoomButton")?.addEventListener("click", leaveChat);

// Load chat history for a specific room
function loadChatHistory(roomName) {
    fetch(`/api/chat/history/${roomName}`, {
        headers: { 'Authorization': `Bearer ${sessionStorage.getItem("token")}` }
    })
        .then(response => response.json())
        .then(messages => {
            const messagesDiv = document.getElementById("messages");
            messagesDiv.innerHTML = '';
            messages.forEach(msg => displayMessage(msg.sender, msg.content, msg.sender === sessionStorage.getItem("username")));
        })
        .catch(error => console.error("Error loading chat history:", error));
}

// Display a message in the chat window
function displayMessage(sender, messageText, isSender = false) {
    const messagesDiv = document.getElementById("messages");
    const messageElement = document.createElement("div");
    messageElement.className = "message " + (isSender ? "sender" : "receiver");
    messageElement.textContent = (isSender ? "You: " : sender + ": ") + messageText;
    messagesDiv.appendChild(messageElement);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

// Load rooms on page load
document.addEventListener("DOMContentLoaded", loadRooms);
