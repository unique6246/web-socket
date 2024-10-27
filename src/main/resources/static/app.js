let ws;
let username;
let roomName;

function connect() {
    username = document.getElementById("username").value;
    roomName = document.getElementById("room").value;

    ws = new WebSocket("ws://localhost:8080/hello?username=" + username);

    ws.onopen = () => {
        console.log("Connected as " + username + " in room " + roomName);
        document.getElementById("connectButton").disabled = true;

        // Load previous messages for the room
        fetch(`/api/chat/history/${roomName}`)
            .then(response => response.json())
            .then(messages => {
                messages.forEach(message => displayMessage(message.sender, message.content, message.sender === username));
            })
            .catch(error => console.error("Error loading chat history:", error));
    };

    ws.onmessage = function (event) {
        let messageData = JSON.parse(event.data);
        displayMessage(messageData.sender, messageData.message, messageData.sender === username);
    };

    ws.onclose = () => {
        console.log("Disconnected");
        document.getElementById("connectButton").disabled = false;
    };
}

function sendMessage() {
    let messageText = document.getElementById("message").value;

    let message = {
        sender: username,
        room: roomName,
        message: messageText
    };

    ws.send(JSON.stringify(message));
    document.getElementById("message").value = "";
}

function displayMessage(sender, messageText, isSender = false) {
    let messagesDiv = document.getElementById("messages");
    let messageElement = document.createElement("div");
    messageElement.className = "message " + (isSender ? "sender" : "receiver");
    messageElement.textContent = (isSender ? "You: " : sender + ": ") + messageText;
    messagesDiv.appendChild(messageElement);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}
