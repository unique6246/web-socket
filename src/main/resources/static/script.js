/* ════════════════════════════════════════════════════════════
   ChatApp – script.js
   ════════════════════════════════════════════════════════════ */

// ──────────────────────────────────────────────
//  Session / Auth helpers
//
//  SECURITY MODEL:
//  • The JWT lives ONLY in an HttpOnly cookie set by the server.
//  • JavaScript never sees, stores, or sends the token.
//  • sessionStorage holds ONLY non-sensitive display data:
//    username (display name) and roles (for UI rendering).
//  • All API calls use credentials:'include' so the browser
//    automatically attaches the HttpOnly cookie.
//  • The Network tab will show the cookie header but NOT its
//    value (HttpOnly cookies are redacted in DevTools).
// ──────────────────────────────────────────────
function getUsername() { return sessionStorage.getItem("username"); }
function getRoles()    { return JSON.parse(sessionStorage.getItem("roles") || "[]"); }
function hasRole(r)    { return getRoles().includes(r); }

function clearSession() {
    sessionStorage.removeItem("username");
    sessionStorage.removeItem("roles");
}

/**
 * All API calls — no Authorization header needed.
 * The HttpOnly cookie is sent automatically by the browser.
 */
function fetchWithAuth(url, opts = {}) {
    opts.credentials = 'include'; // sends HttpOnly cookie automatically
    // Remove any leftover Authorization header — token is cookie-only
    if (opts.headers) delete opts.headers['Authorization'];
    return fetch(url, opts).then(r => {
        if (r.status === 401) {
            clearSession();
            window.location.href = "/api/v1/login";
            throw new Error("Session expired");
        }
        if (r.status === 403) {
            throw new Error("Access denied. You do not have permission.");
        }
        if (!r.ok) throw new Error("Request failed: " + r.status);
        return r;
    });
}

/**
 * Server-side guard: calls /api/auth/me (uses HttpOnly cookie).
 * Verifies identity with the backend before rendering any protected page.
 */
async function requireAuthWithRole(requiredRoles, redirectUrl) {
    try {
        const res = await fetch("/api/auth/me", { credentials: 'include' });
        if (!res.ok) {
            clearSession();
            window.location.href = "/api/v1/login";
            return false;
        }
        const data = await res.json();
        const roles = data.roles || [];
        // Refresh display data from server
        sessionStorage.setItem("roles",    JSON.stringify(roles));
        sessionStorage.setItem("username", data.username);

        if (requiredRoles && requiredRoles.length > 0) {
            const hasRequired = requiredRoles.some(r => roles.includes(r));
            if (!hasRequired) {
                alert("Access denied. You do not have the required permissions.");
                window.location.href = redirectUrl || "/api/v1/login";
                return false;
            }
        }
        return true;
    } catch (e) {
        clearSession();
        window.location.href = "/api/v1/login";
        return false;
    }
}

async function requireAuth() {
    return requireAuthWithRole([], "/api/v1/login");
}

// ──────────────────────────────────────────────
//  Utility
// ──────────────────────────────────────────────
function escapeHtml(t) {
    const d = document.createElement("div");
    d.appendChild(document.createTextNode(t || ""));
    return d.innerHTML;
}
function formatTime(d) { return new Date(d).toLocaleTimeString([], { hour:'2-digit', minute:'2-digit' }); }
function formatDateLabel(d) {
    const date = new Date(d), today = new Date(), yest = new Date();
    yest.setDate(today.getDate() - 1);
    if (date.toDateString() === today.toDateString()) return "Today";
    if (date.toDateString() === yest.toDateString())  return "Yesterday";
    return date.toLocaleDateString([], { weekday:'long', month:'short', day:'numeric' });
}
function showBanner(id, msg) {
    const el = document.getElementById(id);
    if (!el) { console.error(msg); return; }
    el.textContent = msg; el.style.display = "block";
    setTimeout(() => el && (el.style.display = "none"), 4000);
}
function hideBanner(id) { const el = document.getElementById(id); if (el) el.style.display = "none"; }

// ──────────────────────────────────────────────
//  Login / Register (auth pages)
// ──────────────────────────────────────────────
function handleLogin(e) {
    e.preventDefault(); hideBanner("loginError");
    const username = document.getElementById("username").value.trim();
    const password = document.getElementById("password").value;

    if (!username) return showBanner("loginError", "Username is required.");
    if (!password) return showBanner("loginError", "Password is required.");

    const submitBtn = e.target.querySelector("button[type='submit']");
    if (submitBtn) { submitBtn.disabled = true; submitBtn.textContent = "Logging in…"; }

    fetch("/api/auth/login", {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ username, password })
    })
    .then(r => {
        if (r.status === 429) return r.json().then(d => { throw new Error(d.error || "Too many attempts. Try later."); });
        if (!r.ok) return r.text().then(t => { throw new Error(JSON.parse(t)?.error || "Login failed"); });
        return r.json();
    })
    .then(data => {
        // Server set the HttpOnly cookie — JS never sees the token.
        // Store ONLY display data (not sensitive).
        sessionStorage.setItem("username", data.username);
        sessionStorage.setItem("roles",    JSON.stringify(data.roles || []));
        window.location.href = (data.roles || []).includes("ROLE_ADMIN") ? "/api/v1/dashboard" : "/api/v1/chat";
    })
    .catch(err => {
        showBanner("loginError", err.message);
        if (submitBtn) { submitBtn.disabled = false; submitBtn.textContent = "Login"; }
    });
}

function handleRegister(e) {
    e.preventDefault(); hideBanner("registerError");
    const username = document.getElementById("newUsername").value.trim();
    const email    = document.getElementById("newEmail")?.value.trim() || "";
    const password = document.getElementById("newPassword").value;
    const confirm  = document.getElementById("confirmPassword")?.value;
    if (confirm !== undefined && password !== confirm) return showBanner("registerError", "Passwords do not match.");
    if (password.length < 8) return showBanner("registerError", "Password must be at least 8 characters.");
    if (!/[A-Z]/.test(password)) return showBanner("registerError", "Password must contain at least one uppercase letter.");
    if (!/[a-z]/.test(password)) return showBanner("registerError", "Password must contain at least one lowercase letter.");
    if (!/\d/.test(password)) return showBanner("registerError", "Password must contain at least one digit.");
    if (!/[!@#$%^&*()\-_=+\[\]{};':"\\|,.<>\/?`~]/.test(password)) return showBanner("registerError", "Password must contain at least one special character.");
    if (!email) return showBanner("registerError", "Email address is required.");
    fetch("/api/auth/register", {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, email, password })
    })
    .then(r => r.ok ? r.json() : r.text().then(t => { throw new Error(JSON.parse(t)?.error || t || "Registration failed"); }))
    .then(data => {
        const el = document.getElementById("registerSuccess");
        if (el) { el.textContent = data.message || "Registered!"; el.style.display = "block"; }
        setTimeout(() => window.location.href = "/api/v1/login", 1500);
    })
    .catch(err => showBanner("registerError", err.message));
}

// ──────────────────────────────────────────────
//  WebSocket state
// ──────────────────────────────────────────────
let socket          = null;
let currentRoomName = null;     // internal room name (dm__a__b or groupName)
let currentRoomType = null;     // "DM" | "GROUP"
let currentRoomDisplay = null;  // friendly display name shown in header
let currentGroupRole   = null;  // "ADMIN" | "MEMBER" | null (null for DMs)

const unreadCounts = {};        // roomName -> count

// ──────────────────────────────────────────────
//  Role-aware nav
// ──────────────────────────────────────────────
function setupRoleUI() {
    const adminLink  = document.getElementById("adminPanelLink");
    const badgeEl    = document.getElementById("roleBadge");
    const usernameEl = document.getElementById("loggedInUser");
    if (usernameEl) usernameEl.textContent = getUsername() || "";
    if (adminLink) adminLink.style.display  = hasRole("ROLE_ADMIN") ? "inline-block" : "none";
    if (badgeEl) {
        const label = getRoles().map(r => r.replace("ROLE_","")).join(", ");
        badgeEl.textContent = label;
        badgeEl.style.display = label ? "inline-block" : "none";
    }
}

// ──────────────────────────────────────────────
//  Sidebar tabs
// ──────────────────────────────────────────────
function initTabs() {
    document.querySelectorAll(".stab[data-tab]").forEach(btn => {
        btn.addEventListener("click", e => {
            // Don't trigger tab switch when clicking the + action button inside
            if (e.target.classList.contains("stab-action")) return;
            switchTab(btn.dataset.tab);
        });
    });
}
function switchTab(tabName) {
    document.querySelectorAll(".stab[data-tab]").forEach(b => b.classList.toggle("active", b.dataset.tab === tabName));
    document.querySelectorAll(".sidebar-tab-content").forEach(c => c.classList.toggle("active", c.id === "tab-"+tabName));
}

// ──────────────────────────────────────────────
//  People list  (all users except self)
// ──────────────────────────────────────────────
let allUsers = [];  // cached

function loadPeopleList() {
    fetchWithAuth("/api/chat/users")
        .then(r => r.json())
        .then(users => {
            allUsers = users;
            renderPeopleList(users);
        })
        .catch(err => console.error("Failed to load users:", err));
}

function renderPeopleList(users) {
    const list = document.getElementById("peopleList");
    if (!list) return;
    list.innerHTML = "";
    if (!users || users.length === 0) {
        list.innerHTML = '<span class="muted-hint">No other users yet.</span>'; return;
    }
    users.forEach(u => {
        const item = document.createElement("div");
        item.className = "room-item user-item";
        item.dataset.username = u.username;
        item.innerHTML = `
            <div class="user-avatar">${u.username.charAt(0).toUpperCase()}</div>
            <span class="room-item-name">${escapeHtml(u.username)}</span>
            <span class="room-badge" style="display:none;"></span>`;
        item.addEventListener("click", () => openDm(u.username));
        list.appendChild(item);
    });
}

function filterPeople(query) {
    const q = query.toLowerCase();
    const filtered = q ? allUsers.filter(u => u.username.toLowerCase().includes(q)) : allUsers;
    renderPeopleList(filtered);
}

// ──────────────────────────────────────────────
//  DM rooms sidebar
// ──────────────────────────────────────────────
function openDm(otherUsername) {
    switchTab("dms");
    fetchWithAuth(`/api/chat/rooms/dm/${encodeURIComponent(otherUsername)}`, { method: 'POST',
        headers: { 'Content-Type': 'application/json' } })
        .then(r => r.json())
        .then(data => {
            ensureRoomInList("dmList", data.roomName, otherUsername, "DM", null);
            switchRoom(data.roomName, "DM", otherUsername, null);
        })
        .catch(err => showBanner("roomError", "Could not open DM: " + err.message));
}

// ──────────────────────────────────────────────
//  Group rooms sidebar
// ──────────────────────────────────────────────
function loadMyRooms() {
    fetchWithAuth("/api/chat/my-rooms")
        .then(r => r.json())
        .then(rooms => {
            rooms.forEach(room => {
                if (room.type === "DM") {
                    const display = dmDisplayName(room.roomName);
                    ensureRoomInList("dmList", room.roomName, display, "DM", null);
                } else {
                    ensureRoomInList("groupList", room.roomName, room.roomName, "GROUP", room.groupRole || "MEMBER");
                }
            });
            ["dmList","groupList"].forEach(id => {
                const el = document.getElementById(id);
                if (el && !el.querySelector(".room-item")) {
                    el.innerHTML = `<span class="muted-hint">${id==="dmList"?"No DMs yet.":"No groups yet."}</span>`;
                }
            });
        })
        .catch(err => console.error("loadMyRooms error:", err));
}

function dmDisplayName(roomName) {
    // roomName format: dm__alice__bob
    const parts = roomName.replace(/^dm__/, "").split("__");
    return parts.find(p => p !== getUsername()) || roomName;
}

function ensureRoomInList(listId, roomName, displayName, type, groupRole) {
    const list = document.getElementById(listId);
    if (!list) return;
    list.querySelector(".muted-hint")?.remove();
    if (list.querySelector(`[data-room="${CSS.escape(roomName)}"]`)) return; // already there

    const item = document.createElement("div");
    item.className = "room-item";
    item.dataset.room      = roomName;
    item.dataset.groupRole = groupRole || "";
    item.innerHTML = `
        <div class="user-avatar ${type === 'GROUP' ? 'group-av' : ''}">${type==="GROUP" ? "🏠" : displayName.charAt(0).toUpperCase()}</div>
        <span class="room-item-name">${escapeHtml(displayName)}</span>
        <span class="room-badge" style="display:none;"></span>`;
    item.addEventListener("click", () => switchRoom(roomName, type, displayName, item.dataset.groupRole || null));
    list.appendChild(item);
}

// ──────────────────────────────────────────────
//  Active room highlight & unread
// ──────────────────────────────────────────────
function setActiveItem(roomName) {
    document.querySelectorAll(".room-item").forEach(el =>
        el.classList.toggle("active", el.dataset.room === roomName || el.dataset.username === roomName));
    unreadCounts[roomName] = 0;
    updateBadge(roomName);
}
function incrementUnread(roomName) {
    if (roomName === currentRoomName) return;
    unreadCounts[roomName] = (unreadCounts[roomName] || 0) + 1;
    updateBadge(roomName);
}
function updateBadge(key) {
    // key can be roomName or username
    const item = document.querySelector(`.room-item[data-room="${CSS.escape(key)}"], .room-item[data-username="${CSS.escape(key)}"]`);
    if (!item) return;
    const badge = item.querySelector(".room-badge");
    const count = unreadCounts[key] || 0;
    badge.textContent = count > 99 ? "99+" : count;
    badge.style.display = count > 0 ? "inline-flex" : "none";
    item.classList.toggle("has-unread", count > 0);
}

// ──────────────────────────────────────────────
//  Open / switch a chat room
// ──────────────────────────────────────────────
function switchRoom(roomName, type, displayName, groupRole) {
    if (roomName === currentRoomName) return;
    if (socket) { socket.onclose = null; socket.close(); socket = null; }
    currentRoomName    = null;
    currentRoomType    = type;
    currentRoomDisplay = displayName;
    currentGroupRole   = groupRole || null;

    setActiveItem(roomName);

    document.getElementById("emptyState").style.display = "none";
    document.getElementById("chatWindow").style.display = "flex";
    document.getElementById("chatRoomTitle").textContent = displayName;
    document.getElementById("chatRoomSub").textContent   = type === "DM" ? "Direct Message" : "Group Chat";
    document.getElementById("chatRoomIcon").textContent  = type === "DM" ? "👤" : "🏠";
    document.getElementById("messages").innerHTML = '<div class="loading-msgs">Loading…</div>';
    document.getElementById("onlineDot").className = "online-dot connecting";

    // Always show Leave for groups; hide for DMs
    document.getElementById("leaveRoomButton").style.display = type === "GROUP" ? "inline-block" : "none";
    // Always show members button for groups
    document.getElementById("membersBtn").style.display = type === "GROUP" ? "inline-flex" : "none";
    // Close members panel when switching rooms
    document.getElementById("membersPanel").style.display = "none";

    const isMod = hasRole("ROLE_ADMIN") || hasRole("ROLE_MODERATOR");
    document.getElementById("modTools").style.display    = isMod ? "block" : "none";
    document.getElementById("modToolsBtn").style.display = isMod ? "inline-flex" : "none";

    // For groups: show/hide Add Member based on role; fetch from server if role unknown
    if (type === "GROUP") {
        if (groupRole) {
            applyGroupRoleUI(groupRole);
        } else {
            // Fetch role from server (e.g. when switching from a cached sidebar item)
            fetchWithAuth(`/api/chat/rooms/${encodeURIComponent(roomName)}/my-role`)
                .then(r => r.json())
                .then(data => {
                    currentGroupRole = data.groupRole;
                    applyGroupRoleUI(data.groupRole);
                    // Update sidebar item's cached role
                    const el = document.querySelector(`.room-item[data-room="${CSS.escape(roomName)}"]`);
                    if (el) el.dataset.groupRole = data.groupRole;
                })
                .catch(() => applyGroupRoleUI("MEMBER"));
        }
    } else {
        document.getElementById("addMemberBtn").style.display = "none";
    }

    connectWebSocket(roomName);
}

function applyGroupRoleUI(role) {
    // Only admin can add members
    document.getElementById("addMemberBtn").style.display = role === "ADMIN" ? "inline-flex" : "none";
}

// ──────────────────────────────────────────────
//  WebSocket — uses one-time ticket (NOT the JWT)
// ──────────────────────────────────────────────
function connectWebSocket(roomName) {
    fetch("/api/auth/ws-ticket", {
        method: 'POST',
        credentials: 'include'
    })
    .then(r => {
        if (!r.ok) { clearSession(); window.location.href = "/api/v1/login"; throw new Error("Auth failed"); }
        return r.json();
    })
    .then(data => {
        // Step 2: open WS with the opaque ticket — NOT the real JWT
        const proto = location.protocol === "https:" ? "wss" : "ws";
        socket = new WebSocket(
            `${proto}://${location.host}/ws?ticket=${encodeURIComponent(data.ticket)}&roomName=${encodeURIComponent(roomName)}`
        );
        socket.onopen = () => {
            currentRoomName = roomName;
            document.getElementById("onlineDot").className = "online-dot connected";
            loadChatHistory(roomName);
        };
        socket.onmessage = ev => {
            try {
                const msg = JSON.parse(ev.data);
                if (msg.roomName === currentRoomName) appendLiveMessage(msg);
                else incrementUnread(msg.roomName);
            } catch(e) {}
        };
        socket.onclose = () => { document.getElementById("onlineDot").className = "online-dot disconnected"; };
        socket.onerror = () => {
            showBanner("roomError", "WebSocket error. Reconnect?");
            document.getElementById("onlineDot").className = "online-dot disconnected";
        };
    })
    .catch(err => showBanner("roomError", "Could not connect: " + err.message));
}

// ──────────────────────────────────────────────
//  Send message
// ──────────────────────────────────────────────
function sendMessage() {
    const input = document.getElementById("messageInput");
    const text = input.value.trim();
    if (!text || !currentRoomName) return;
    if (!socket || socket.readyState !== WebSocket.OPEN) { showBanner("roomError","Not connected."); return; }
    socket.send(JSON.stringify({ sender: getUsername(), room: currentRoomName, message: text }));
    input.value = ""; input.focus();
}

// ──────────────────────────────────────────────
//  Leave group room
// ──────────────────────────────────────────────
function leaveRoom() {
    if (!currentRoomName || currentRoomType !== "GROUP") return;
    const room = currentRoomName;
    if (socket) { socket.onclose = null; socket.close(); socket = null; }
    currentRoomName = null;
    document.querySelector(`.room-item[data-room="${CSS.escape(room)}"]`)?.remove();
    document.getElementById("chatWindow").style.display = "none";
    document.getElementById("emptyState").style.display = "flex";
    fetchWithAuth(`/api/chat/rooms/${encodeURIComponent(room)}/leave`, { method:'DELETE' }).catch(console.error);
}

// ──────────────────────────────────────────────
//  Chat history
// ──────────────────────────────────────────────
function loadChatHistory(roomName) {
    fetchWithAuth(`/api/chat/history/${encodeURIComponent(roomName)}`)
        .then(r => r.json())
        .then(msgs => {
            document.getElementById("messages").innerHTML = "";
            if (!msgs || msgs.length === 0) {
                document.getElementById("messages").innerHTML = '<div class="no-msgs-hint">No messages yet. Say hello 👋</div>';
                return;
            }
            resetGroupState();
            renderHistory(msgs);
            scrollToBottom();
        })
        .catch(() => { document.getElementById("messages").innerHTML = '<div class="no-msgs-hint">Could not load history.</div>'; });
}

// ──────────────────────────────────────────────
//  Message rendering / grouping
// ──────────────────────────────────────────────
let lastSender = null, lastDate = null, lastGroupEl = null;
function resetGroupState() { lastSender = null; lastDate = null; lastGroupEl = null; }

function renderHistory(msgs) {
    resetGroupState();
    msgs.forEach(msg => {
        const ts = msg.timestamp ? new Date(msg.timestamp) : new Date();
        const dl = formatDateLabel(ts);
        if (dl !== lastDate) { appendDateSep(dl); lastDate = dl; lastSender = null; lastGroupEl = null; }
        if (msg.fileUrl) { appendFileBubble(msg.sender, msg.fileUrl, msg.fileName, msg.sender===getUsername(), ts); lastSender=null; lastGroupEl=null; }
        else appendTextBubble(msg.sender, msg.content, msg.sender===getUsername(), ts);
    });
}

function appendLiveMessage(msg) {
    const isSelf = msg.sender === getUsername();
    const ts = new Date(), dl = formatDateLabel(ts);
    document.getElementById("messages").querySelector(".no-msgs-hint")?.remove();
    if (dl !== lastDate) { appendDateSep(dl); lastDate=dl; lastSender=null; lastGroupEl=null; }
    if (msg.fileUrl) { appendFileBubble(msg.sender,msg.fileUrl,msg.fileName,isSelf,ts); lastSender=null; lastGroupEl=null; }
    else appendTextBubble(msg.sender, msg.message, isSelf, ts);
    scrollToBottom();
}

function appendDateSep(label) {
    const el = document.createElement("div"); el.className="date-separator";
    el.innerHTML=`<span>${escapeHtml(label)}</span>`;
    document.getElementById("messages").appendChild(el);
}

function appendTextBubble(sender, text, isSelf, ts) {
    if (sender === lastSender && lastGroupEl) {
        const b = document.createElement("div"); b.className="bubble"; b.textContent=text||"";
        lastGroupEl.querySelector(".group-bubbles").appendChild(b);
        const t = lastGroupEl.querySelector(".group-time"); if(t) t.textContent=formatTime(ts);
    } else {
        const g = makeGroup(sender, isSelf, ts);
        const b = document.createElement("div"); b.className="bubble"; b.textContent=text||"";
        g.querySelector(".group-bubbles").appendChild(b);
        document.getElementById("messages").appendChild(g);
        lastGroupEl=g; lastSender=sender;
    }
}

function appendFileBubble(sender, fileUrl, fileName, isSelf, ts) {
    const g = makeGroup(sender, isSelf, ts);
    const b = document.createElement("div"); b.className="bubble bubble-file";
    const name = fileName || fileUrl.split("/").pop();
    const ext  = name.split(".").pop().toLowerCase();
    const img  = ["png","jpg","jpeg","gif","webp"].includes(ext) ? `<img src="${escapeHtml(fileUrl)}" class="file-preview-img" alt="${escapeHtml(name)}"/>` : "📎";
    b.innerHTML = `${img} <a href="${escapeHtml(fileUrl)}" target="_blank" class="file-link">${escapeHtml(name)}</a>`;
    g.querySelector(".group-bubbles").appendChild(b);
    document.getElementById("messages").appendChild(g);
}

function makeGroup(sender, isSelf, ts) {
    const g = document.createElement("div"); g.className=`msg-group ${isSelf?"self":"other"}`;
    const av = sender ? sender.charAt(0).toUpperCase() : "?";
    const label = isSelf ? "You" : escapeHtml(sender);
    g.innerHTML=`
        <div class="group-avatar">${av}</div>
        <div class="group-body">
            <div class="group-meta"><span class="group-sender">${label}</span><span class="group-time">${formatTime(ts)}</span></div>
            <div class="group-bubbles"></div>
        </div>`;
    return g;
}

function scrollToBottom() { const el=document.getElementById("messages"); if(el) el.scrollTop=el.scrollHeight; }

// ──────────────────────────────────────────────
//  File upload
// ──────────────────────────────────────────────
function handleFileUpload(e) {
    const file = e.target.files[0]; if(!file) return;
    const fd = new FormData(); fd.append("file", file);
    fetchWithAuth("/api/files/upload", { method:"POST", body:fd })
        .then(r=>r.json())
        .then(data => { if(socket?.readyState===WebSocket.OPEN) socket.send(JSON.stringify({ sender:getUsername(), room:currentRoomName, fileUrl:data.fileUrl, fileName:data.fileName, fileType:data.type })); })
        .catch(err=>showBanner("roomError","Upload failed: "+err.message));
    e.target.value="";
}

// ──────────────────────────────────────────────
//  New Group Modal
// ──────────────────────────────────────────────
let selectedMembers = new Set();

function openGroupModal() {
    selectedMembers = new Set();
    document.getElementById("groupNameInput").value = "";
    document.getElementById("memberSearchInput").value = "";
    document.getElementById("groupModalError").style.display = "none";
    renderMemberPickList(allUsers);
    renderChips();
    document.getElementById("groupModal").style.display = "flex";
    document.getElementById("groupNameInput").focus();
}
function closeGroupModal() { document.getElementById("groupModal").style.display = "none"; }

function renderMemberPickList(users) {
    const list = document.getElementById("memberPickList"); list.innerHTML="";
    users.forEach(u => {
        const item = document.createElement("div");
        item.className = "pick-item" + (selectedMembers.has(u.username) ? " selected" : "");
        item.dataset.username = u.username;
        item.innerHTML = `<div class="user-avatar sm">${u.username.charAt(0).toUpperCase()}</div><span>${escapeHtml(u.username)}</span><span class="pick-check">${selectedMembers.has(u.username)?"✓":""}</span>`;
        item.addEventListener("click", () => toggleMember(u.username));
        list.appendChild(item);
    });
}

function toggleMember(username) {
    if (selectedMembers.has(username)) selectedMembers.delete(username);
    else selectedMembers.add(username);
    renderChips();
    renderMemberPickList(allUsers.filter(u => {
        const q = document.getElementById("memberSearchInput").value.toLowerCase();
        return !q || u.username.toLowerCase().includes(q);
    }));
}

function renderChips() {
    const row = document.getElementById("selectedMemberChips"); row.innerHTML="";
    selectedMembers.forEach(name => {
        const chip = document.createElement("span"); chip.className="chip";
        chip.innerHTML=`${escapeHtml(name)} <button onclick="toggleMember('${escapeHtml(name)}')" class="chip-remove">✕</button>`;
        row.appendChild(chip);
    });
}

function filterMemberList(query) {
    const q = query.toLowerCase();
    const filtered = q ? allUsers.filter(u=>u.username.toLowerCase().includes(q)) : allUsers;
    renderMemberPickList(filtered);
}

function submitCreateGroup() {
    const groupName = document.getElementById("groupNameInput").value.trim();
    const errEl = document.getElementById("groupModalError");
    errEl.style.display="none";
    if (!groupName) { errEl.textContent="Group name is required."; errEl.style.display="block"; return; }
    if (selectedMembers.size === 0) { errEl.textContent="Select at least one member."; errEl.style.display="block"; return; }

    fetchWithAuth("/api/chat/rooms/group", {
        method:"POST",
        headers:{"Content-Type":"application/json"},
        body: JSON.stringify({ groupName, members: [...selectedMembers] })
    })
    .then(r=>r.json())
    .then(data => {
        closeGroupModal();
        switchTab("groups");
        ensureRoomInList("groupList", data.roomName, data.roomName, "GROUP", data.groupRole || "ADMIN");
        switchRoom(data.roomName, "GROUP", data.roomName, data.groupRole || "ADMIN");
    })
    .catch(err => { errEl.textContent = err.message || "Failed to create group."; errEl.style.display="block"; });
}

// ──────────────────────────────────────────────
//  Add Member to Group Modal
// ──────────────────────────────────────────────
let addMemberSelected = null;  // single username string

function openAddMemberModal() {
    addMemberSelected = null;
    document.getElementById("addMemberSearchInput").value = "";
    document.getElementById("addMemberError").style.display = "none";
    document.getElementById("confirmAddMemberBtn").disabled = true;
    document.getElementById("confirmAddMemberBtn").textContent = "Add Selected";
    document.getElementById("addMemberGroupName").textContent = currentRoomDisplay || currentRoomName;

    // Use the dedicated /members endpoint — returns [{username, groupAdmin}, ...]
    fetchWithAuth(`/api/chat/rooms/${encodeURIComponent(currentRoomName)}/members`)
        .then(r => r.json())
        .then(memberObjs => {
            const existing = new Set(memberObjs.map(m => m.username));
            const candidates = allUsers.filter(u => !existing.has(u.username));
            renderAddMemberPickList(candidates);
        })
        .catch(() => renderAddMemberPickList(allUsers));

    document.getElementById("addMemberModal").style.display = "flex";
    document.getElementById("addMemberSearchInput").focus();
}

function closeAddMemberModal() {
    document.getElementById("addMemberModal").style.display = "none";
    addMemberSelected = null;
}

function renderAddMemberPickList(users) {
    const list = document.getElementById("addMemberPickList");
    list.innerHTML = "";
    if (!users || users.length === 0) {
        list.innerHTML = '<div style="padding:12px;color:#94a3b8;font-size:.85em;text-align:center;">All users are already in this group.</div>';
        return;
    }
    // Store the current candidate list on the list element so filterAddMemberList can re-use it
    list._candidates = users;
    users.forEach(u => {
        const item = document.createElement("div");
        item.className = "pick-item" + (addMemberSelected === u.username ? " selected" : "");
        item.dataset.username = u.username;
        item.innerHTML = `
            <div class="user-avatar sm">${u.username.charAt(0).toUpperCase()}</div>
            <span>${escapeHtml(u.username)}</span>
            <span class="pick-check">${addMemberSelected === u.username ? "✓" : ""}</span>`;
        item.addEventListener("click", () => {
            addMemberSelected = addMemberSelected === u.username ? null : u.username;
            document.getElementById("confirmAddMemberBtn").disabled = !addMemberSelected;
            const q = document.getElementById("addMemberSearchInput").value.toLowerCase();
            renderAddMemberPickList(q ? users.filter(x => x.username.toLowerCase().includes(q)) : users);
        });
        list.appendChild(item);
    });
}

function filterAddMemberList(query) {
    const list = document.getElementById("addMemberPickList");
    const candidates = list._candidates || allUsers;
    const q = query.toLowerCase();
    renderAddMemberPickList(q ? candidates.filter(u => u.username.toLowerCase().includes(q)) : candidates);
}

function confirmAddMember() {
    if (!addMemberSelected || !currentRoomName) return;
    // Capture BEFORE closing the modal (closeAddMemberModal sets addMemberSelected = null)
    const usernameToAdd = addMemberSelected;
    const errEl = document.getElementById("addMemberError");
    errEl.style.display = "none";
    const btn = document.getElementById("confirmAddMemberBtn");
    btn.disabled = true;
    btn.textContent = "Adding…";

    fetchWithAuth(`/api/chat/rooms/${encodeURIComponent(currentRoomName)}/add-member/${encodeURIComponent(usernameToAdd)}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" }
    })
    .then(r => r.json())
    .then(() => {
        closeAddMemberModal();
        // Show success using the captured local variable — not the now-null addMemberSelected
        const banner = document.getElementById("roomError");
        if (banner) {
            banner.textContent = `✅ ${usernameToAdd} was added to the group.`;
            banner.style.cssText = "display:block;background:#f0fdf4;color:#166534;border-color:#bbf7d0;";
            setTimeout(() => { banner.style.cssText = "display:none;"; }, 3500);
        }
    })
    .catch(err => {
        errEl.textContent = err.message || "Failed to add member.";
        errEl.style.display = "block";
        btn.disabled = false;
        btn.textContent = "Add Selected";
    });
}

// ──────────────────────────────────────────────
//  Members Panel
// ──────────────────────────────────────────────
function openMembersPanel() {
    const panel = document.getElementById("membersPanel");
    const list  = document.getElementById("membersPanelList");
    list.innerHTML = '<div class="members-loading">Loading…</div>';
    panel.style.display = "flex";

    fetchWithAuth(`/api/chat/rooms/${encodeURIComponent(currentRoomName)}/members`)
        .then(r => r.json())
        .then(members => {
            list.innerHTML = "";
            members.forEach(m => {
                const isMe      = m.username === getUsername();
                const isAdmin   = m.groupAdmin;
                const callerAdmin = currentGroupRole === "ADMIN";

                const row = document.createElement("div");
                row.className = "member-row";
                row.innerHTML = `
                    <div class="user-avatar sm">${m.username.charAt(0).toUpperCase()}</div>
                    <span class="member-name">${escapeHtml(m.username)}${isMe ? " <span class='you-tag'>(you)</span>" : ""}</span>
                    ${isAdmin ? "<span class='admin-crown' title='Group Admin'>👑</span>" : ""}
                    <div class="member-actions">
                        ${callerAdmin && !isAdmin && !isMe
                            ? `<button class="btn-remove-member" onclick="removeMember('${escapeHtml(m.username)}')" title="Remove from group">✕</button>`
                            : ""}
                        ${isMe && !isAdmin
                            ? `<button class="btn-leave-member" onclick="leaveRoom()" title="Leave group">Leave</button>`
                            : ""}
                    </div>`;
                list.appendChild(row);
            });
        })
        .catch(() => { list.innerHTML = '<div class="members-loading">Could not load members.</div>'; });
}

function closeMembersPanel() {
    document.getElementById("membersPanel").style.display = "none";
}

function removeMember(username) {
    if (!confirm(`Remove ${username} from the group?`)) return;
    fetchWithAuth(`/api/chat/rooms/${encodeURIComponent(currentRoomName)}/remove-member/${encodeURIComponent(username)}`, {
        method: "DELETE"
    })
    .then(r => r.json())
    .then(() => {
        openMembersPanel(); // refresh panel
        const banner = document.getElementById("roomError");
        if (banner) {
            banner.textContent = `✅ ${username} was removed from the group.`;
            banner.style.cssText = "display:block;background:#f0fdf4;color:#166534;border-color:#bbf7d0;";
            setTimeout(() => { banner.style.cssText = "display:none;"; }, 3000);
        }
    })
    .catch(err => showBanner("roomError", err.message || "Failed to remove member."));
}

// ──────────────────────────────────────────────
//  DOMContentLoaded bootstrap
// ──────────────────────────────────────────────
document.addEventListener("DOMContentLoaded", () => {

    /* Auth pages */
    document.getElementById("loginForm")?.addEventListener("submit", handleLogin);
    document.getElementById("registerForm")?.addEventListener("submit", handleRegister);

    /* Chat page only — skip event wiring if not on chat.html */
    if (!document.getElementById("peopleList")) return;

    /* Logout — server blacklists the HttpOnly cookie token and clears it */
    document.getElementById("logoutButton")?.addEventListener("click", () => {
        if (socket) { socket.onclose = null; socket.close(); }
        fetch("/api/auth/logout", { method: 'POST', credentials: 'include' })
            .finally(() => { clearSession(); window.location.href = "/api/v1/login"; });
    });

    /* People search */
    document.getElementById("peopleSearch")?.addEventListener("input", e => filterPeople(e.target.value));

    /* Send */
    document.getElementById("sendMessageButton")?.addEventListener("click", sendMessage);
    document.getElementById("messageInput")?.addEventListener("keydown", e => { if(e.key==="Enter"&&!e.shiftKey){e.preventDefault();sendMessage();} });

    /* Leave */
    document.getElementById("leaveRoomButton")?.addEventListener("click", leaveRoom);

    /* File attach */
    document.getElementById("attachFileButton")?.addEventListener("click", () => document.getElementById("fileInput")?.click());
    document.getElementById("fileInput")?.addEventListener("change", handleFileUpload);

    /* Mod tools */
    document.getElementById("modToolsBtn")?.addEventListener("click", () => {
        const bar=document.getElementById("modTools"); if(bar) bar.style.display=bar.style.display==="none"?"block":"none";
    });

    /* New group button (inside groups tab label) */
    document.getElementById("newGroupBtn")?.addEventListener("click", e => { e.stopPropagation(); openGroupModal(); });
    document.getElementById("closeGroupModal")?.addEventListener("click", closeGroupModal);
    document.getElementById("cancelGroupBtn")?.addEventListener("click", closeGroupModal);
    document.getElementById("createGroupBtn")?.addEventListener("click", submitCreateGroup);
    document.getElementById("groupNameInput")?.addEventListener("keydown", e => { if(e.key==="Enter") submitCreateGroup(); });
    document.getElementById("memberSearchInput")?.addEventListener("input", e => filterMemberList(e.target.value));

    /* Members panel */
    document.getElementById("membersBtn")?.addEventListener("click", openMembersPanel);
    document.getElementById("closeMembersPanel")?.addEventListener("click", closeMembersPanel);

    /* Add Member modal */
    document.getElementById("addMemberBtn")?.addEventListener("click", openAddMemberModal);
    document.getElementById("closeAddMemberModal")?.addEventListener("click", closeAddMemberModal);
    document.getElementById("cancelAddMemberBtn")?.addEventListener("click", closeAddMemberModal);
    document.getElementById("confirmAddMemberBtn")?.addEventListener("click", confirmAddMember);
    document.getElementById("addMemberSearchInput")?.addEventListener("input", e => filterAddMemberList(e.target.value));
    document.getElementById("addMemberModal")?.addEventListener("click", e => { if (e.target === e.currentTarget) closeAddMemberModal(); });

    /* Close modal on backdrop click */
    document.getElementById("groupModal")?.addEventListener("click", e => { if(e.target===e.currentTarget) closeGroupModal(); });
});
