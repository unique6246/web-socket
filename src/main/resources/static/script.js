/* ════════════════════════════════════════════════════════════
   ChatApp – script.js
   ════════════════════════════════════════════════════════════ */

// ──────────────────────────────────────────────
//  Session / Auth helpers
// ──────────────────────────────────────────────
function getUsername()    { return sessionStorage.getItem("username"); }
function getDisplayName() { return sessionStorage.getItem("displayName") || getUsername(); }
function getAvatarUrl()   { return sessionStorage.getItem("avatarUrl") || ""; }
function getRoles()       { return JSON.parse(sessionStorage.getItem("roles") || "[]"); }
function hasRole(r)       { return getRoles().includes(r); }

function clearSession() {
    sessionStorage.removeItem("username");
    sessionStorage.removeItem("roles");
    sessionStorage.removeItem("displayName");
    sessionStorage.removeItem("avatarUrl");
}

function fetchWithAuth(url, opts = {}) {
    opts.credentials = 'include';
    if (opts.headers) delete opts.headers['Authorization'];
    return fetch(url, opts).then(r => {
        if (r.status === 401) { clearSession(); window.location.href = "/api/v1/login"; throw new Error("Session expired"); }
        if (r.status === 403) { throw new Error("Access denied."); }
        if (!r.ok) return r.json().then(d => { throw new Error(d.error || "Request failed: " + r.status); }).catch(e => { if (e.message && e.message !== "Request failed: " + r.status) throw e; throw new Error("Request failed: " + r.status); });
        return r;
    });
}

async function requireAuthWithRole(requiredRoles, redirectUrl) {
    try {
        const res = await fetch("/api/auth/me", { credentials: 'include' });
        if (!res.ok) { clearSession(); window.location.href = "/api/v1/login"; return false; }
        const data = await res.json();

        // Store session data first so it's available regardless of what happens next
        const roles = data.roles || [];
        sessionStorage.setItem("roles",       JSON.stringify(roles));
        sessionStorage.setItem("username",    data.username);
        sessionStorage.setItem("displayName", data.displayName || data.username);
        sessionStorage.setItem("avatarUrl",   data.avatarUrl || "");

        // ── Email verification gate ───────────────────────────────────────
        if (data.emailVerified === false) {
            clearSession();
            window.location.href = "/api/v1/login?unverified=true&email=" + encodeURIComponent(data.email || "");
            return false;
        }

        if (requiredRoles && requiredRoles.length > 0) {
            const hasRequired = requiredRoles.some(r => roles.includes(r));
            if (!hasRequired) { alert("Access denied."); window.location.href = redirectUrl || "/api/v1/login"; return false; }
        }
        return true;
    } catch (e) { clearSession(); window.location.href = "/api/v1/login"; return false; }
}
async function requireAuth() { return requireAuthWithRole([], "/api/v1/login"); }

// ──────────────────────────────────────────────
//  Utility
// ──────────────────────────────────────────────
function escapeHtml(t) { const d = document.createElement("div"); d.appendChild(document.createTextNode(t || "")); return d.innerHTML; }

/**
 * Parses a date value that may be a JS Date, a timestamp number,
 * or a LocalDateTime string from the server (e.g. "2024-03-29T14:23:45.123").
 * Java's LocalDateTime.toString() has no timezone suffix — we treat it as UTC.
 */
function parseServerDate(d) {
    if (!d) return new Date();
    if (d instanceof Date) return d;
    if (typeof d === 'number') return new Date(d);
    // Java LocalDateTime: "2024-03-29T14:23:45" or "2024-03-29T14:23:45.123"
    // Append 'Z' if there's no timezone indicator so JS treats it as UTC
    const s = String(d);
    if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s) && !/[Z+]/.test(s)) {
        return new Date(s + 'Z');
    }
    return new Date(s);
}

function formatTime(d) {
    return parseServerDate(d).toLocaleTimeString([], { hour:'2-digit', minute:'2-digit' });
}

function formatRelativeTime(d) {
    const date = parseServerDate(d);
    const now = new Date();
    const diffMs = now - date;
    const diffMin = Math.floor(diffMs / 60000);
    if (diffMin < 1)  return 'just now';
    if (diffMin < 60) return `${diffMin}m ago`;
    const diffH = Math.floor(diffMin / 60);
    if (diffH < 24)   return `${diffH}h ago`;
    return date.toLocaleDateString([], { month:'short', day:'numeric' });
}

function formatDateLabel(d) {
    const date = parseServerDate(d);
    const today = new Date(), yest = new Date();
    yest.setDate(today.getDate() - 1);
    if (date.toDateString() === today.toDateString()) return "Today";
    if (date.toDateString() === yest.toDateString())  return "Yesterday";
    return date.toLocaleDateString([], { weekday:'long', month:'short', day:'numeric' });
}
function showBanner(id, msg, type) {
    const el = document.getElementById(id);
    if (!el) { console.error(msg); return; }
    el.textContent = msg;
    if (type === "success") el.style.cssText = "display:block;background:#f0fdf4;color:#166534;border-color:#bbf7d0;";
    else el.style.display = "block";
    setTimeout(() => el && (el.style.display = "none"), 4000);
}
function hideBanner(id) { const el = document.getElementById(id); if (el) el.style.display = "none"; }

// Show/hide password helper
function togglePw(id, btn) {
    const el = document.getElementById(id);
    if (!el) return;
    el.type = el.type === "password" ? "text" : "password";
    btn.textContent = el.type === "password" ? "👁" : "🙈";
}

// ──────────────────────────────────────────────
//  Avatar helpers
// ──────────────────────────────────────────────
function makeAvatarEl(username, avatarUrl, size) {
    const cls = size === "sm" ? "user-avatar sm" : "user-avatar";
    if (avatarUrl) {
        return `<img src="${escapeHtml(avatarUrl)}" class="${cls} avatar-img" alt="${escapeHtml(username)}" onerror="this.outerHTML='<div class=${JSON.stringify(cls)}>${escapeHtml(username.charAt(0).toUpperCase())}</div>'">`;
    }
    return `<div class="${cls}">${escapeHtml(username.charAt(0).toUpperCase())}</div>`;
}

// ──────────────────────────────────────────────
//  Login / Register
// ──────────────────────────────────────────────
function handleLogin(e) {
    e.preventDefault(); hideBanner("loginError"); hideBanner("loginSuccess");
    const username = document.getElementById("username").value.trim();
    const password = document.getElementById("password").value;
    if (!username) return showBanner("loginError", "Username is required.");
    if (!password) return showBanner("loginError", "Password is required.");
    const btn = e.target.querySelector("button[type='submit']");
    if (btn) { btn.disabled = true; btn.textContent = "Signing in…"; }
    fetch("/api/auth/login", { method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'include', body: JSON.stringify({ username, password }) })
    .then(r => {
        if (r.status === 429) return r.json().then(d => { throw Object.assign(new Error(d.error || "Too many requests from this network. Try later."), { status: 429 }); });
        if (r.status === 423) return r.json().then(d => { throw Object.assign(new Error(d.error || "Account locked. Reset your password."), { status: 423 }); });
        if (r.status === 403) return r.json().then(d => { throw Object.assign(new Error(d.error || "Access denied."), { status: 403, unverified: d.unverified, oauthOnly: d.oauthOnly, provider: d.provider }); });
        if (!r.ok) return r.json().then(d => { throw Object.assign(new Error(d.error || "Login failed"), { attemptsRemaining: d.attemptsRemaining }); });
        return r.json();
    })
    .then(data => {
        sessionStorage.setItem("username",    data.username);
        sessionStorage.setItem("roles",       JSON.stringify(data.roles || []));
        sessionStorage.setItem("displayName", data.displayName || data.username);
        sessionStorage.setItem("avatarUrl",   data.avatarUrl || "");
        window.location.href = (data.roles || []).includes("ROLE_ADMIN") ? "/api/v1/dashboard" : "/api/v1/chat";
    })
    .catch(err => {
        let msg = err.message;
        if (err.attemptsRemaining !== undefined) {
            msg += ` (${err.attemptsRemaining} attempt${err.attemptsRemaining !== 1 ? 's' : ''} remaining before lockout)`;
        }
        if (err.oauthOnly) {
            const provider = err.provider || "google";
            const errEl = document.getElementById("loginError");
            if (errEl) {
                errEl.textContent = "";
                const text = document.createTextNode(err.message + " ");
                const link = document.createElement("a");
                link.href = `/oauth2/authorization/${provider}`;
                link.textContent = `Sign in with ${provider.charAt(0).toUpperCase() + provider.slice(1)}`;
                link.style.cssText = "color:#4f46e5;text-decoration:underline;";
                errEl.appendChild(text);
                errEl.appendChild(link);
                errEl.style.display = "block";
            }
            if (btn) { btn.disabled = false; btn.textContent = "Sign In"; }
            return;
        }
        if (err.unverified) {
            // Build the error message safely using DOM — never use innerHTML with dynamic content
            const errEl = document.getElementById("loginError");
            if (errEl) {
                errEl.textContent = "";
                const text = document.createTextNode(msg + " ");
                const link = document.createElement("a");
                link.href = "#";
                link.textContent = "Resend verification email";
                link.style.cssText = "color:#4f46e5;text-decoration:underline;";
                link.addEventListener("click", e => { e.preventDefault(); showResend?.(); });
                errEl.appendChild(text);
                errEl.appendChild(link);
                errEl.style.display = "block";
            }
            if (btn) { btn.disabled = false; btn.textContent = "Sign In"; }
            return;
        }
        showBanner("loginError", msg);
        if (btn) { btn.disabled = false; btn.textContent = "Sign In"; }
    });
}

function handleRegister(e) {
    e.preventDefault(); hideBanner("registerError");
    const username    = document.getElementById("newUsername").value.trim();
    const displayName = document.getElementById("newDisplayName")?.value.trim() || "";
    const email       = document.getElementById("newEmail")?.value.trim() || "";
    const phone       = document.getElementById("newPhone")?.value.trim() || "";
    const password    = document.getElementById("newPassword").value;
    const confirm     = document.getElementById("confirmPassword")?.value;
    if (confirm !== undefined && password !== confirm) return showBanner("registerError", "Passwords do not match.");
    if (password.length < 8) return showBanner("registerError", "Password must be at least 8 characters.");
    if (!/[A-Z]/.test(password)) return showBanner("registerError", "Password must contain at least one uppercase letter.");
    if (!/[a-z]/.test(password)) return showBanner("registerError", "Password must contain at least one lowercase letter.");
    if (!/\d/.test(password)) return showBanner("registerError", "Password must contain at least one digit.");
    if (!/[!@#$%^&*()\-_=+\[\]{};':"\\|,.<>\/?`~]/.test(password)) return showBanner("registerError", "Password must contain at least one special character.");
    if (!email) return showBanner("registerError", "Email address is required.");
    fetch("/api/auth/register", { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, email, password, displayName, phone }) })
    .then(r => r.ok ? r.json() : r.json().then(d => { throw new Error(d.error || "Registration failed"); }))
    .then(data => {
        const el = document.getElementById("registerSuccess");
        if (el) { el.textContent = data.message || "Registered! Check your email to verify."; el.style.display = "block"; }
        setTimeout(() => window.location.href = "/api/v1/login", 2000);
    })
    .catch(err => showBanner("registerError", err.message));
}

// ──────────────────────────────────────────────
//  WebSocket state
// ──────────────────────────────────────────────
let socket          = null;   // room-scoped WebSocket (chat messages)
let sidebarSocket   = null;   // persistent global WebSocket (presence, unread, notifications)
let currentRoomName = null;
let currentRoomType = null;
let currentRoomDisplay = null;
let currentGroupRole   = null;
const unreadCounts = {};  // roomName → count

// Typing state
let typingTimer = null;
let isTypingSent = false;
const typingUsers = new Set();

// Reply state
let replyToMsg = null; // { id, sender, content }

// Context menu state
let ctxMsgEl  = null;  // the bubble element right-clicked
let ctxMsgId  = null;  // message DB id

// ──────────────────────────────────────────────
//  Role-aware nav
// ──────────────────────────────────────────────
function setupRoleUI() {
    const adminLink = document.getElementById("adminPanelLink");
    const userEl    = document.getElementById("loggedInUser");
    const navAv     = document.getElementById("navAvatar");
    const dispName  = getDisplayName();
    const avUrl     = getAvatarUrl();
    if (userEl) userEl.textContent = dispName;
    if (navAv) {
        if (avUrl) {
            navAv.innerHTML = `<img src="${escapeHtml(avUrl)}" class="nav-avatar-img" alt="${escapeHtml(dispName)}" onerror="this.outerHTML='<div class=nav-avatar-letter>${escapeHtml(dispName.charAt(0).toUpperCase())}</div>'">`;
        } else {
            navAv.textContent = dispName.charAt(0).toUpperCase();
            navAv.classList.add("nav-avatar-letter");
        }
    }
    if (adminLink) adminLink.style.display = hasRole("ROLE_ADMIN") ? "inline-block" : "none";
}

// ──────────────────────────────────────────────
//  Notifications
// ──────────────────────────────────────────────
async function loadUnreadNotifCount() {
    try {
        const r = await fetchWithAuth("/api/notifications/unread-count");
        const d = await r.json();
        updateNotifBadge(d.unreadCount || 0);
    } catch(e) {}
}
function updateNotifBadge(count) {
    const badge = document.getElementById("notifBadge");
    if (!badge) return;
    badge.textContent = count > 99 ? "99+" : count;
    badge.style.display = count > 0 ? "flex" : "none";
}
async function markNotifRead(id, itemEl) {
    if (!id) return;
    try {
        await fetchWithAuth(`/api/notifications/${id}/read`, { method: "PATCH" });
        if (itemEl) {
            itemEl.classList.remove("notif-unread");
            itemEl.querySelector(".notif-mark-read")?.remove();
        }
        // Re-fetch authoritative count instead of doing DOM arithmetic
        loadUnreadNotifCount();
    } catch(e) {}
}
async function markAllNotifsRead() {
    try {
        await fetchWithAuth("/api/notifications/read-all", { method: "PATCH" });
        document.querySelectorAll(".notif-item.notif-unread").forEach(el => {
            el.classList.remove("notif-unread");
            el.querySelector(".notif-mark-read")?.remove();
        });
        updateNotifBadge(0);
    } catch(e) {}
}
async function loadNotifications() {
    const list = document.getElementById("notifList");
    if (!list) return;
    list.innerHTML = '<div class="members-loading">Loading…</div>';
    try {
        const r = await fetchWithAuth("/api/notifications?limit=20");
        const d = await r.json();
        list.innerHTML = "";
        if (!d.notifications || d.notifications.length === 0) {
            list.innerHTML = '<div class="members-loading">No notifications yet.</div>'; return;
        }
        d.notifications.forEach(n => {
            const item = document.createElement("div");
            item.className = "notif-item" + (n.isRead ? "" : " notif-unread");
            item.dataset.id = n.id;

            const iconDiv = document.createElement("div");
            iconDiv.className = "notif-icon";
            iconDiv.textContent = getNotifIcon(n.type);

            const bodyDiv = document.createElement("div");
            bodyDiv.className = "notif-body";
            const contentDiv = document.createElement("div");
            contentDiv.className = "notif-content";
            contentDiv.textContent = n.content || "";
            const timeDiv = document.createElement("div");
            timeDiv.className = "notif-time";
            timeDiv.textContent = n.createdAt ? formatRelativeTime(n.createdAt) : "";
            bodyDiv.appendChild(contentDiv);
            bodyDiv.appendChild(timeDiv);

            item.appendChild(iconDiv);
            item.appendChild(bodyDiv);

            if (!n.isRead) {
                const markBtn = document.createElement("button");
                markBtn.className = "notif-mark-read";
                markBtn.title = "Mark read";
                markBtn.textContent = "✓";
                markBtn.addEventListener("click", e => { e.stopPropagation(); markNotifRead(n.id, item); });
                item.appendChild(markBtn);
            }

            list.appendChild(item);
        });
    } catch(e) { list.innerHTML = '<div class="members-loading">Failed to load notifications.</div>'; }
}
function getNotifIcon(type) {
    const map = { MESSAGE:"💬", MENTION:"@", FRIEND_REQUEST:"👤", FRIEND_ACCEPTED:"✅", ROOM_INVITE:"🏠", SYSTEM:"🔔" };
    return map[type] || "🔔";
}

// ── Typing indicator ──────────────────────────────────────────────────────────
let typingTimers = {};
function handleTypingEvent(sender, isTyping, roomName) {
    if (roomName !== currentRoomName || sender === getUsername()) return;
    if (isTyping) {
        typingUsers.add(sender);
        clearTimeout(typingTimers[sender]);
        typingTimers[sender] = setTimeout(() => { typingUsers.delete(sender); renderTypingIndicator(); }, 3000);
    } else {
        typingUsers.delete(sender);
        clearTimeout(typingTimers[sender]);
    }
    renderTypingIndicator();
}
function renderTypingIndicator() {
    const el = document.getElementById("typingIndicator");
    const txt = document.getElementById("typingText");
    if (!el || !txt) return;
    const users = [...typingUsers];
    if (users.length === 0) { el.style.display = "none"; return; }
    txt.textContent = users.length === 1 ? `${users[0]} is typing…`
        : users.length === 2 ? `${users[0]} and ${users[1]} are typing…`
        : `${users.length} people are typing…`;
    el.style.display = "flex";
}

// ──────────────────────────────────────────────
//  Sidebar tabs
// ──────────────────────────────────────────────
function initTabs() {
    document.querySelectorAll(".stab[data-tab]").forEach(btn => {
        btn.addEventListener("click", e => { if (e.target.classList.contains("stab-action")) return; switchTab(btn.dataset.tab); });
    });
}
function switchTab(tabName) {
    document.querySelectorAll(".stab[data-tab]").forEach(b => b.classList.toggle("active", b.dataset.tab === tabName));
    document.querySelectorAll(".sidebar-tab-content").forEach(c => c.classList.toggle("active", c.id === "tab-"+tabName));
}

// ──────────────────────────────────────────────
//  People list
// ──────────────────────────────────────────────
let allUsers = [];

function loadPeopleList() {
    fetchWithAuth("/api/chat/users").then(r => r.json()).then(users => {
        allUsers = users;
        renderPeopleList(users);
        // Load my rooms AFTER allUsers is populated so DM status dots are accurate
        loadMyRooms();
    }).catch(console.error);
}
function renderPeopleList(users) {
    const list = document.getElementById("peopleList"); if (!list) return;
    list.innerHTML = "";
    if (!users || users.length === 0) { list.innerHTML = '<span class="muted-hint">No other users yet.</span>'; return; }
    users.forEach(u => {
        const item = document.createElement("div");
        item.className = "room-item user-item";
        item.dataset.username = u.username;
        const statusCls = (u.status || 'OFFLINE').toLowerCase();
        const statusLabel = { online:"Online", away:"Away", dnd:"Do Not Disturb", offline:"Offline" }[statusCls] || statusCls;
        const avHtml = u.avatarUrl
            ? `<img src="${escapeHtml(u.avatarUrl)}" class="user-avatar" style="object-fit:cover;" alt="${escapeHtml(u.username)}" onerror="this.style.display='none';this.nextElementSibling.style.display='flex'">`
            : "";
        const avFallback = `<div class="user-avatar" ${u.avatarUrl ? 'style="display:none;"' : ""}>${escapeHtml(u.username.charAt(0).toUpperCase())}</div>`;
        item.innerHTML = `
            ${avHtml}${avFallback}
            <div class="room-item-body">
                <div class="room-item-top">
                    <span class="room-item-name">${escapeHtml(u.displayName || u.username)}</span>
                    <span class="status-dot ${statusCls}" title="${statusLabel}"></span>
                </div>
                <span class="room-item-preview" style="color:#94a3b8;font-size:.77em;">${statusLabel}</span>
            </div>`;
        item.addEventListener("click", () => openDm(u.username));
        list.appendChild(item);
    });
}
function filterPeople(query) {
    const q = query.toLowerCase();
    renderPeopleList(q ? allUsers.filter(u => u.username.toLowerCase().includes(q) || (u.displayName||"").toLowerCase().includes(q)) : allUsers);
}

// ──────────────────────────────────────────────
//  DM rooms sidebar
// ──────────────────────────────────────────────
function openDm(otherUsername) {
    switchTab("dms");
    fetchWithAuth(`/api/chat/rooms/dm/${encodeURIComponent(otherUsername)}`, { method: 'POST', headers: { 'Content-Type': 'application/json' } })
    .then(r => r.json()).then(data => { ensureRoomInList("dmList", data.roomName, otherUsername, "DM", null); switchRoom(data.roomName, "DM", otherUsername, null); })
    .catch(err => showBanner("roomError", "Could not open DM: " + err.message));
}

// ──────────────────────────────────────────────
//  Group rooms sidebar
// ──────────────────────────────────────────────
function loadMyRooms() {
    fetchWithAuth("/api/chat/my-rooms").then(r => r.json()).then(rooms => {
        rooms.forEach(room => {
            if (room.type === "DM") {
                const displayName = dmDisplayName(room.roomName);
                ensureRoomInList("dmList", room.roomName, displayName, "DM", null);
            } else {
                ensureRoomInList("groupList", room.roomName, room.roomName, "GROUP", room.groupRole || "MEMBER");
            }
            // Show last-message preview if the API returns one
            if (room.lastMessage) {
                const item = document.querySelector(`.room-item[data-room="${CSS.escape(room.roomName)}"]`);
                if (item) {
                    const preview = item.querySelector(".room-item-preview");
                    if (preview) {
                        const text = room.lastMessage.length > 45 ? room.lastMessage.substring(0, 45) + "…" : room.lastMessage;
                        preview.textContent = text;
                        preview.dataset.lastSnippet = text;
                    }
                }
            }
            // Restore any persisted unread counts (server-provided)
            if (room.unreadCount > 0) {
                unreadCounts[room.roomName] = room.unreadCount;
                updateBadge(room.roomName);
            }
        });
        ["dmList","groupList"].forEach(id => {
            const el = document.getElementById(id);
            if (el && !el.querySelector(".room-item")) el.innerHTML = `<span class="muted-hint">${id==="dmList"?"No DMs yet.":"No groups yet."}</span>`;
        });
        updateTabBadge();
    }).catch(console.error);
}
function dmDisplayName(roomName) {
    const parts = roomName.replace(/^dm__/, "").split("__");
    return parts.find(p => p !== getUsername()) || roomName;
}
function ensureRoomInList(listId, roomName, displayName, type, groupRole) {
    const list = document.getElementById(listId); if (!list) return;
    list.querySelector(".muted-hint")?.remove();
    if (list.querySelector(`[data-room="${CSS.escape(roomName)}"]`)) return;
    const item = document.createElement("div");
    item.className = "room-item"; item.dataset.room = roomName; item.dataset.groupRole = groupRole || "";
    if (type === "DM") item.dataset.username = displayName;

    // Read live status from allUsers (populated before loadMyRooms runs)
    let statusDotHtml = "";
    if (type === "DM") {
        const knownUser = allUsers.find(u => u.username === displayName);
        const statusCls = knownUser ? (knownUser.status || 'OFFLINE').toLowerCase() : 'offline';
        const statusLabel = { online:"Online", away:"Away", dnd:"Do Not Disturb", offline:"Offline" }[statusCls] || statusCls;
        statusDotHtml = `<span class="status-dot ${statusCls}" title="${statusLabel}"></span>`;
    }

    const avatarHtml = type === "GROUP"
        ? `<div class="user-avatar group-av">🏠</div>`
        : `<div class="user-avatar">${escapeHtml(displayName.charAt(0).toUpperCase())}</div>`;

    item.innerHTML = `
        ${avatarHtml}
        <div class="room-item-body">
            <div class="room-item-top">
                <span class="room-item-name">${escapeHtml(displayName)}</span>
                ${statusDotHtml}
                <span class="room-badge" style="display:none;"></span>
            </div>
            <span class="room-item-preview"></span>
        </div>`;
    item.addEventListener("click", () => switchRoom(roomName, type, displayName, item.dataset.groupRole || null));
    list.appendChild(item);
}

// ──────────────────────────────────────────────
//  Active room highlight & unread
// ──────────────────────────────────────────────
function setActiveItem(roomName) {
    document.querySelectorAll(".room-item").forEach(el => el.classList.toggle("active", el.dataset.room===roomName||el.dataset.username===roomName));
    unreadCounts[roomName] = 0;
    updateBadge(roomName);
    updateTabBadge();   // recalculate tab-level badge after clearing this room
}
function incrementUnread(roomName) {
    if (roomName === currentRoomName) return;
    unreadCounts[roomName] = (unreadCounts[roomName] || 0) + 1;
    updateBadge(roomName);
    updateTabBadge();
}
function updateBadge(key) {
    const item = document.querySelector(`.room-item[data-room="${CSS.escape(key)}"], .room-item[data-username="${CSS.escape(key)}"]`);
    if (!item) return;
    const badge = item.querySelector(".room-badge");
    if (!badge) return;
    const count = unreadCounts[key] || 0;
    badge.textContent = count > 99 ? "99+" : count;
    badge.style.display = count > 0 ? "inline-flex" : "none";
    item.classList.toggle("has-unread", count > 0);
}

/**
 * Recomputes the total unread count for each tab (DMs / Groups)
 * and renders a numeric badge directly on the tab button.
 */
function updateTabBadge() {
    // DMs tab — sum unread for all dm__ rooms
    let dmTotal = 0, groupTotal = 0;
    document.querySelectorAll("#dmList .room-item[data-room]").forEach(el => {
        dmTotal += unreadCounts[el.dataset.room] || 0;
    });
    document.querySelectorAll("#groupList .room-item[data-room]").forEach(el => {
        groupTotal += unreadCounts[el.dataset.room] || 0;
    });

    _setTabBadge("dms",    dmTotal);
    _setTabBadge("groups", groupTotal);
}
function _setTabBadge(tabName, count) {
    const stab = document.querySelector(`.stab[data-tab="${tabName}"]`);
    if (!stab) return;
    let badge = stab.querySelector(".stab-unread-badge");
    if (!badge) {
        badge = document.createElement("span");
        badge.className = "stab-unread-badge room-badge";
        badge.style.cssText = "margin-left:5px;font-size:.65em;padding:1px 5px;";
        stab.appendChild(badge);
    }
    if (count > 0) {
        badge.textContent = count > 99 ? "99+" : count;
        badge.style.display = "inline-flex";
    } else {
        badge.style.display = "none";
    }
}

// ──────────────────────────────────────────────
//  Open / switch a chat room
// ──────────────────────────────────────────────
function switchRoom(roomName, type, displayName, groupRole) {
    if (roomName === currentRoomName) return;
    if (socket) { socket.onclose = null; socket.close(); socket = null; }
    cancelReply();
    currentRoomName    = null; currentRoomType = type;
    currentRoomDisplay = displayName; currentGroupRole = groupRole || null;

    setActiveItem(roomName);
    document.getElementById("emptyState").style.display = "none";
    document.getElementById("chatWindow").style.display = "flex";
    document.getElementById("chatRoomTitle").textContent = displayName;

    // For DMs, show live online/offline status in sub-line
    const chatRoomSub = document.getElementById("chatRoomSub");
    if (type === "DM") {
        const knownUser = allUsers.find(u => u.username === displayName);
        const s = knownUser ? (knownUser.status || 'OFFLINE').toLowerCase() : 'offline';
        const statusLabels = { online: "🟢 Online", away: "🟡 Away", dnd: "🔴 Do Not Disturb", offline: "⚫ Offline" };
        chatRoomSub.textContent = statusLabels[s] || "⚫ Offline";
        chatRoomSub.className   = `chat-room-sub dm-${s}`;
    } else {
        chatRoomSub.textContent = "Group Chat";
        chatRoomSub.className   = "chat-room-sub";
    }

    document.getElementById("chatRoomIcon").textContent  = type === "DM" ? "👤" : "🏠";
    document.getElementById("messages").innerHTML = '<div class="loading-msgs">Loading…</div>';
    document.getElementById("onlineDot").className = "online-dot connecting";
    document.getElementById("leaveRoomButton").style.display = type === "GROUP" ? "inline-block" : "none";
    document.getElementById("membersBtn").style.display     = type === "GROUP" ? "inline-flex" : "none";
    document.getElementById("membersPanel").style.display   = "none";
    document.getElementById("pinnedPanel").style.display    = "none";
    document.getElementById("searchBar").style.display      = "none";

    const isMod = hasRole("ROLE_ADMIN") || hasRole("ROLE_MODERATOR");
    document.getElementById("modTools").style.display    = isMod ? "block" : "none";
    document.getElementById("modToolsBtn").style.display = isMod ? "inline-flex" : "none";

    if (type === "GROUP") {
        if (groupRole) { applyGroupRoleUI(groupRole); }
        else {
            fetchWithAuth(`/api/chat/rooms/${encodeURIComponent(roomName)}/my-role`).then(r=>r.json()).then(data=>{
                currentGroupRole = data.groupRole; applyGroupRoleUI(data.groupRole);
                const el = document.querySelector(`.room-item[data-room="${CSS.escape(roomName)}"]`);
                if (el) el.dataset.groupRole = data.groupRole;
            }).catch(() => applyGroupRoleUI("MEMBER"));
        }
    } else { document.getElementById("addMemberBtn").style.display = "none"; }

    connectWebSocket(roomName);
}

function applyGroupRoleUI(role) {
    document.getElementById("addMemberBtn").style.display = role === "ADMIN" ? "inline-flex" : "none";
}

// ──────────────────────────────────────────────
//  Sidebar (global) WebSocket — persistent, receives PRESENCE / UNREAD / NOTIF
// ──────────────────────────────────────────────
let _sidebarReconnectDelay = 1000;
let _sidebarReconnectTimer = null;

function connectSidebarSocket() {
    if (sidebarSocket && (sidebarSocket.readyState === WebSocket.OPEN || sidebarSocket.readyState === WebSocket.CONNECTING)) return;
    fetch("/api/auth/ws-ticket", { method: "POST", credentials: "include" })
        .then(r => { if (!r.ok) throw new Error("ticket"); return r.json(); })
        .then(data => {
            const proto = location.protocol === "https:" ? "wss" : "ws";
            sidebarSocket = new WebSocket(
                `${proto}://${location.host}/ws?ticket=${encodeURIComponent(data.ticket)}&roomName=__sidebar__`
            );
            sidebarSocket.onopen = () => {
                _sidebarReconnectDelay = 1000;
                clearTimeout(_sidebarReconnectTimer);
                const dot = document.getElementById("sidebarWsDot");
                if (dot) { dot.className = "sidebar-ws-dot"; dot.title = "Sidebar connection: live"; }
            };
            sidebarSocket.onmessage = ev => {
                try { handleSidebarMessage(JSON.parse(ev.data)); } catch(e) {}
            };
            sidebarSocket.onclose = () => {
                sidebarSocket = null;
                const dot = document.getElementById("sidebarWsDot");
                if (dot) { dot.className = "sidebar-ws-dot disconnected"; dot.title = "Sidebar connection: reconnecting…"; }
                _sidebarReconnectTimer = setTimeout(() => {
                    _sidebarReconnectDelay = Math.min(_sidebarReconnectDelay * 2, 30000);
                    connectSidebarSocket();
                }, _sidebarReconnectDelay);
            };
            sidebarSocket.onerror = () => { sidebarSocket?.close(); };
        })
        .catch(() => {
            _sidebarReconnectTimer = setTimeout(connectSidebarSocket, _sidebarReconnectDelay);
        });
}

/**
 * Handles events that arrive on the persistent sidebar socket.
 * These are GLOBAL events — not tied to a specific room.
 */
function handleSidebarMessage(msg) {
    const type = msg.eventType || "";
    if (type === "PRESENCE")     { handlePresenceEvent(msg.sender, msg.presenceStatus); return; }
    if (type === "UNREAD_COUNT") { handleUnreadCountEvent(msg); return; }
    if (type === "NOTIFICATION") { handleNotificationEvent(msg); return; }
    if (type === "GROUP_CREATED") { handleGroupCreatedEvent(msg); return; }
    // MESSAGE events also arrive here for non-active rooms — update preview + badge
    if (type === "MESSAGE" && msg.roomName && msg.roomName !== currentRoomName) {
        const snippet = msg.message || (msg.fileUrl ? `📎 ${msg.fileName || "File"}` : "");
        updateSidebarItem(msg.roomName, snippet, msg.sender, false);
    }
}

// ──────────────────────────────────────────────
//  Room WebSocket
// ──────────────────────────────────────────────
function connectWebSocket(roomName) {
    fetch("/api/auth/ws-ticket", { method: 'POST', credentials: 'include' })
    .then(r => {
        if (r.status === 401) { clearSession(); window.location.href="/api/v1/login"; throw new Error("Auth failed"); }
        if (!r.ok) throw new Error("WS ticket request failed: " + r.status);
        return r.json();
    })
    .then(data => {
        const proto = location.protocol==="https:"?"wss":"ws";
        socket = new WebSocket(`${proto}://${location.host}/ws?ticket=${encodeURIComponent(data.ticket)}&roomName=${encodeURIComponent(roomName)}`);
        socket.onopen = () => { currentRoomName = roomName; document.getElementById("onlineDot").className="online-dot connected"; loadChatHistory(roomName); };
        socket.onmessage = ev => handleWsMessage(JSON.parse(ev.data));
        socket.onclose   = () => { document.getElementById("onlineDot").className="online-dot disconnected"; };
        socket.onerror   = () => { showBanner("roomError", "WebSocket error."); document.getElementById("onlineDot").className="online-dot disconnected"; };
    }).catch(err => showBanner("roomError", "Could not connect: " + err.message));
}

function handleWsMessage(msg) {
    const type = msg.eventType || "MESSAGE";

    // ── Global events belong to the sidebar socket only ───────────────────────
    // The sidebar socket handles these for the entire session. Processing them
    // here too would double-count badges and notifications.
    if (type === "PRESENCE" || type === "NOTIFICATION" ||
        type === "UNREAD_COUNT" || type === "GROUP_CREATED") return;

    if (type === "TYPING") {
        handleTypingEvent(msg.sender, msg.isTyping, msg.roomName);
        // Show "typing…" as a transient preview in the sidebar DM item
        if (msg.roomName !== currentRoomName && msg.isTyping) {
            const item = document.querySelector(`.room-item[data-room="${CSS.escape(msg.roomName)}"]`);
            const preview = item?.querySelector(".room-item-preview");
            if (preview) {
                preview.textContent = `${msg.sender} is typing…`;
                preview.classList.add("preview-typing");
                clearTimeout(preview._typingTimer);
                preview._typingTimer = setTimeout(() => {
                    preview.classList.remove("preview-typing");
                    preview.textContent = preview.dataset.lastSnippet || "";
                }, 4000);
            }
        }
        return;
    }

    if (type === "MESSAGE_ID_ASSIGN") {
        const allBubbles = document.querySelectorAll(".bubble:not([data-id])");
        for (let i = allBubbles.length - 1; i >= 0; i--) {
            const b = allBubbles[i];
            const group = b.closest(".msg-group");
            const senderEl = group?.querySelector(".group-sender");
            if (!senderEl) continue;
            const senderName = senderEl.textContent.trim();
            if (senderName === msg.sender || senderName === (getDisplayName() || "You")) {
                b.dataset.id = msg.messageId; break;
            }
        }
        return;
    }
    if (type === "MESSAGE_EDIT") {
        const el = document.querySelector(`.bubble[data-id="${msg.messageId}"]`);
        if (el) {
            const tc = el.querySelector(".bubble-text-content");
            if (tc) tc.textContent = msg.content;
            if (!el.querySelector(".edited-tag")) {
                const s = document.createElement("span"); s.className = "edited-tag"; s.textContent = " (edited)"; el.appendChild(s);
            }
        }
        return;
    }
    if (type === "MESSAGE_DELETE") {
        const el = document.querySelector(`.bubble[data-id="${msg.messageId}"]`);
        if (el) { el.classList.add("deleted-bubble"); el.innerHTML = '<em class="deleted-text">Message deleted</em>'; }
        return;
    }
    if (type === "REACTION") { refreshReactions(msg.messageId); return; }
    if (type === "PIN") {
        const el = document.querySelector(`.bubble[data-id="${msg.messageId}"]`);
        if (el) el.classList.toggle("pinned-bubble", msg.isPinned !== undefined ? !!msg.isPinned : !el.classList.contains("pinned-bubble"));
        return;
    }

    // Default: regular MESSAGE
    if (msg.roomName === currentRoomName) {
        appendLiveMessage(msg);
    }
    // Update sidebar preview text for any room (active or not).
    // Badge increment is handled exclusively by the UNREAD_COUNT event
    // from the sidebar socket — do NOT call incrementUnread here.
    const snippet = msg.message || (msg.fileUrl ? `📎 ${msg.fileName || "File"}` : "");
    if (snippet) updateSidebarItem(msg.roomName, snippet, msg.sender, false);
}

/**
 * Updates the sidebar item for a room: sets the preview text, bumps the badge
 * (only if increment=true), stores the snippet for restoring after typing preview,
 * and floats the item to the top of its list.
 */
function updateSidebarItem(roomName, snippet, senderUsername, increment) {
    if (!roomName) return;
    const isDm = roomName.startsWith("dm__");
    // Auto-create sidebar entry if it doesn't exist yet (new DM from another user)
    if (isDm) {
        const otherUser = roomName.replace(/^dm__/, "").split("__").find(p => p !== getUsername()) || roomName;
        ensureRoomInList("dmList", roomName, otherUser, "DM", null);
    }
    const item = document.querySelector(`.room-item[data-room="${CSS.escape(roomName)}"]`);
    if (!item) return;

    // Update preview snippet
    const preview = item.querySelector(".room-item-preview");
    if (preview) {
        const text = snippet.length > 45 ? snippet.substring(0, 45) + "…" : snippet;
        preview.dataset.lastSnippet = text;
        if (!preview.classList.contains("preview-typing")) preview.textContent = text;
    }

    // Bump unread badge only for non-active rooms
    if (increment && roomName !== currentRoomName) {
        incrementUnread(roomName);
    }

    // Float item to top of its list (newest-activity first)
    const list = item.parentElement;
    if (list && list.firstChild !== item) list.prepend(item);

    // Flash the tab badge for DMs or Groups
    updateTabBadge();
}

// ── Presence ─────────────────────────────────────────────────────────────────
function handlePresenceEvent(sender, status) {
    const s = (status || "offline").toLowerCase();
    const cls = `status-dot ${s}`;
    const label = { online:"Online", away:"Away", dnd:"Do Not Disturb", offline:"Offline" }[s] || s;

    // ── 1. Keep allUsers[] in sync so every future read sees live status ──────
    const u = allUsers.find(u => u.username === sender);
    if (u) u.status = s.toUpperCase();

    // ── 2. Every sidebar/people-list element with data-username="sender" ──────
    document.querySelectorAll(`[data-username="${CSS.escape(sender)}"]`).forEach(el => {
        const dot = el.querySelector(".status-dot");
        if (dot) { dot.className = cls; dot.title = label; }
        // Update the sub-text preview line in the People tab (shows status label)
        const preview = el.querySelector(".room-item-preview");
        if (preview && el.classList.contains("user-item")) {
            preview.textContent = label;
        }
    });

    // ── 3. Members panel rows (stays live while panel is open) ────────────────
    document.querySelectorAll(`#membersPanelList .member-row[data-username="${CSS.escape(sender)}"]`).forEach(row => {
        const dot = row.querySelector(".status-dot");
        if (dot) { dot.className = cls; dot.title = label; }
    });

    // ── 4. DM chat header sub-line (only when that DM is open) ───────────────
    if (currentRoomType === "DM") {
        const otherUser = dmDisplayName(currentRoomName);
        if (otherUser === sender) {
            const sub = document.getElementById("chatRoomSub");
            if (sub) {
                const statusLabels = { online: "🟢 Online", away: "🟡 Away", dnd: "🔴 Do Not Disturb", offline: "⚫ Offline" };
                sub.textContent = statusLabels[s] || "⚫ Offline";
                sub.className   = `chat-room-sub dm-${s}`;
            }
        }
    }
}

// ── Real-time Notifications ───────────────────────────────────────────────────
function handleNotificationEvent(msg) {
    // ── 1. Bell badge — use authoritative DB count from server ────────────────
    if (msg.unreadCount !== undefined && msg.unreadCount !== null) {
        updateNotifBadge(msg.unreadCount);
    } else {
        const badge = document.getElementById("notifBadge");
        if (badge) {
            const current = parseInt(badge.textContent || "0", 10);
            updateNotifBadge(current + 1);
        }
    }

    // ── 2. Sidebar room badge — for DM MESSAGE notifications ─────────────────
    // The NOTIFICATION event is the only unread signal sent for DM rooms
    // (UNREAD_COUNT is only sent for groups). If this notification is a DM
    // message and carries the roomName, increment the sidebar badge now.
    if (msg.type === "MESSAGE" && msg.roomName && msg.roomName !== currentRoomName) {
        // Build preview: "sender: message" — extract sender from content "X sent you a message"
        const senderMatch = msg.content ? msg.content.match(/^(.+?) sent you/) : null;
        const senderName  = senderMatch ? senderMatch[1] : "";
        const snippet     = senderName ? `${senderName}: New message` : (msg.content || "");
        updateSidebarItem(msg.roomName, snippet, senderName, true);
    }

    // ── 3. Toast notification ─────────────────────────────────────────────────
    showNotifToast(msg.type, msg.content);

    // ── 4. If the notifications panel is open, prepend live ──────────────────
    const panel = document.getElementById("notifPanel");
    if (panel && panel.style.display !== "none") {
        prependNotifItem(msg);
    }
}

function handleUnreadCountEvent(msg) {
    if (!msg.roomName || msg.roomName === currentRoomName) return;

    // Build the preview snippet: "sender: message text" format like WhatsApp/Slack
    let snippet = msg.lastMessage || "";
    if (msg.sender && snippet) {
        const senderLabel = msg.sender === getUsername() ? "You" : msg.sender;
        snippet = `${senderLabel}: ${snippet}`;
    }

    // updateSidebarItem handles: auto-create sidebar entry, set preview text,
    // increment the room badge by 1, float item to top, update tab badge
    updateSidebarItem(msg.roomName, snippet, msg.sender, true);
}

/**
 * Called when the server pushes GROUP_CREATED to all group members.
 * Adds the group to the sidebar immediately without a page refresh.
 */
function handleGroupCreatedEvent(msg) {
    if (!msg.roomName) return;
    const role = msg.groupRole || "MEMBER";
    const isCreator = msg.createdBy === getUsername();

    // Add to the Groups tab sidebar
    ensureRoomInList("groupList", msg.roomName, msg.displayName || msg.roomName, "GROUP", role);

    // Update the preview line to show who created it
    const item = document.querySelector(`.room-item[data-room="${CSS.escape(msg.roomName)}"]`);
    if (item) {
        const preview = item.querySelector(".room-item-preview");
        if (preview) {
            preview.textContent = isCreator ? "You created this group" : `${msg.createdBy} added you`;
            preview.dataset.lastSnippet = preview.textContent;
        }
        // Highlight with a brief flash so the user notices the new entry
        item.style.transition = "background .4s";
        item.style.background = "rgba(99,102,241,.18)";
        setTimeout(() => { item.style.background = ""; }, 2000);
    }

    updateTabBadge();

    // For non-creators: show a toast notification and auto-switch to Groups tab
    if (!isCreator) {
        showNotifToast("ROOM_INVITE", `${msg.createdBy} added you to "${msg.displayName || msg.roomName}"`);
        // Gently switch to the Groups tab so the user sees the new entry
        const groupsTab = document.querySelector(".stab[data-tab='groups']");
        if (groupsTab && !groupsTab.classList.contains("active")) {
            switchTab("groups");
        }
    }
}

// ── Notification toast ────────────────────────────────────────────────────────
function showNotifToast(type, content) {
    const icons = {
        FRIEND_REQUEST: "👤", FRIEND_ACCEPTED: "🤝",
        MENTION: "💬", ROOM_INVITE: "🏠", MESSAGE: "💬"
    };
    const icon = icons[type] || "🔔";
    const toast = document.createElement("div");
    toast.className = "notif-toast";
    // Use DOM text node — never innerHTML with user content
    const iconSpan = document.createElement("span");
    iconSpan.className = "notif-toast-icon";
    iconSpan.textContent = icon;
    const textSpan = document.createElement("span");
    textSpan.className = "notif-toast-text";
    textSpan.textContent = content || "New notification";
    toast.appendChild(iconSpan);
    toast.appendChild(textSpan);
    document.body.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add("notif-toast-show"));
    setTimeout(() => {
        toast.classList.remove("notif-toast-show");
        setTimeout(() => toast.remove(), 400);
    }, 4000);
}

// Prepend a notification item into the open notifications panel
function prependNotifItem(msg) {
    const list = document.getElementById("notifList");
    if (!list) return;
    // Remove "no notifications" placeholder if present
    const placeholder = list.querySelector(".members-loading");
    if (placeholder && placeholder.textContent.includes("No notifications")) placeholder.remove();
    const item = document.createElement("div");
    item.className = "notif-item notif-unread";
    item.dataset.id = msg.notificationId || "";
    const iconDiv = document.createElement("div");
    iconDiv.className = "notif-icon";
    iconDiv.textContent = getNotifIcon(msg.type);
    const bodyDiv = document.createElement("div");
    bodyDiv.className = "notif-body";
    const contentDiv = document.createElement("div");
    contentDiv.className = "notif-content";
    contentDiv.textContent = msg.content || "";
    const timeDiv = document.createElement("div");
    timeDiv.className = "notif-time";
    timeDiv.textContent = "just now";
    bodyDiv.appendChild(contentDiv);
    bodyDiv.appendChild(timeDiv);
    const markBtn = document.createElement("button");
    markBtn.className = "notif-mark-read";
    markBtn.title = "Mark read";
    markBtn.textContent = "✓";
    markBtn.addEventListener("click", e => { e.stopPropagation(); markNotifRead(msg.notificationId, item); });
    item.appendChild(iconDiv);
    item.appendChild(bodyDiv);
    item.appendChild(markBtn);
    list.prepend(item);
}

// Typing: send event on input
function onMessageInputChange() {
    if (!socket || socket.readyState !== WebSocket.OPEN || !currentRoomName) return;
    if (!isTypingSent) {
        isTypingSent = true;
        socket.send(JSON.stringify({ eventType: "TYPING", room: currentRoomName, isTyping: true }));
    }
    clearTimeout(typingTimer);
    typingTimer = setTimeout(() => {
        isTypingSent = false;
        if (socket && socket.readyState === WebSocket.OPEN)
            socket.send(JSON.stringify({ eventType: "TYPING", room: currentRoomName, isTyping: false }));
    }, 2000);
}

// ──────────────────────────────────────────────
//  Send message
// ──────────────────────────────────────────────
function sendMessage() {
    const input = document.getElementById("messageInput");
    const text = input.value.trim();
    if (!text || !currentRoomName) return;
    if (!socket || socket.readyState !== WebSocket.OPEN) { showBanner("roomError","Not connected."); return; }
    // Do NOT include sender — the backend reads it from the authenticated WS session,
    // never from the client payload (prevents spoofing).
    const payload = { room: currentRoomName, message: text, eventType: "MESSAGE" };
    if (replyToMsg) payload.replyToMessageId = replyToMsg.id;
    socket.send(JSON.stringify(payload));
    input.value = ""; input.focus();
    cancelReply();
    // Stop typing indicator
    isTypingSent = false;
    clearTimeout(typingTimer);
    socket.send(JSON.stringify({ eventType: "TYPING", room: currentRoomName, isTyping: false }));
}

// ──────────────────────────────────────────────
//  Reply
// ──────────────────────────────────────────────
function setReply(msgId, sender, content) {
    replyToMsg = { id: msgId, sender, content };
    document.getElementById("replyBar").style.display = "flex";
    document.getElementById("replyToName").textContent = sender;
    document.getElementById("replyToContent").textContent = content?.substring(0, 80) || "…";
    document.getElementById("messageInput").focus();
}
function cancelReply() {
    replyToMsg = null;
    document.getElementById("replyBar").style.display = "none";
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
    fetchWithAuth(`/api/messages/room/${encodeURIComponent(roomName)}?limit=50`)
    .then(r => r.json()).then(msgs => {
        document.getElementById("messages").innerHTML = "";
        if (!msgs || msgs.length === 0) { document.getElementById("messages").innerHTML = '<div class="no-msgs-hint">No messages yet. Say hello 👋</div>'; return; }
        resetGroupState(); renderHistory(msgs); scrollToBottom();
    }).catch(() => { document.getElementById("messages").innerHTML = '<div class="no-msgs-hint">Could not load history.</div>'; });
}

// ──────────────────────────────────────────────
//  Message rendering / grouping
// ──────────────────────────────────────────────
let lastSender = null, lastDate = null, lastGroupEl = null;
function resetGroupState() { lastSender = null; lastDate = null; lastGroupEl = null; }

function renderHistory(msgs) {
    resetGroupState();
    msgs.forEach(msg => {
        const ts = msg.timestamp ? parseServerDate(msg.timestamp) : new Date();
        const dl = formatDateLabel(ts);
        if (dl !== lastDate) { appendDateSep(dl); lastDate = dl; lastSender = null; lastGroupEl = null; }
        if (msg.isDeleted) { appendDeletedBubble(msg); lastSender = null; lastGroupEl = null; return; }

        const type = (msg.messageType || (msg.fileUrl ? "FILE" : "TEXT")).toUpperCase();
        const isSelf = msg.sender === getUsername();

        if (type === "IMAGE" || type === "FILE") {
            appendFileBubble(msg.sender, msg.fileUrl, msg.fileName, msg.fileType, isSelf, ts, msg.id, type, msg.content);
            lastSender = null; lastGroupEl = null;
        } else if (type === "SYSTEM") {
            appendSystemMessage(msg.content, ts);
            lastSender = null; lastGroupEl = null;
        } else {
            appendTextBubble(msg.sender, msg.content, isSelf, ts, msg.id, msg.isEdited, msg.replyTo);
        }

        // Render inline reactions from history DTO if present
        if (msg.id && msg.reactions && Object.keys(msg.reactions).length > 0) {
            renderInlineReactions(msg.id, msg.reactions);
        }
    });
}


/**
 * Renders reaction pills directly on a bubble from a pre-loaded {emoji: count} map.
 * Used when loading history so reactions survive page refresh.
 */
function renderInlineReactions(msgId, reactionCounts) {
    const bubble = document.querySelector(`.bubble[data-id="${msgId}"]`);
    if (!bubble) return;
    let bar = bubble.querySelector(".reaction-bar");
    if (!bar) { bar = document.createElement("div"); bar.className = "reaction-bar"; bubble.appendChild(bar); }
    bar.innerHTML = "";
    Object.entries(reactionCounts).forEach(([emoji, count]) => {
        const pill = document.createElement("span");
        pill.className = "reaction-pill";
        pill.innerHTML = `${emoji} <span>${count}</span>`;
        pill.addEventListener("click", () => toggleReaction(msgId, emoji));
        bar.appendChild(pill);
    });
}

function appendLiveMessage(msg) {
    const isSelf = msg.sender === getUsername();
    const ts = msg.timestamp ? parseServerDate(msg.timestamp) : new Date();
    const dl = formatDateLabel(ts);
    document.getElementById("messages").querySelector(".no-msgs-hint")?.remove();
    if (dl !== lastDate) { appendDateSep(dl); lastDate = dl; lastSender = null; lastGroupEl = null; }

    // Live WS messages: use fileUrl presence + fileType MIME to determine type
    const hasFile = !!msg.fileUrl;
    const mimeType = msg.fileType || "";
    const isImage  = hasFile && mimeType.toLowerCase().startsWith("image/");
    const type     = hasFile ? (isImage ? "IMAGE" : "FILE") : "TEXT";

    if (type === "IMAGE" || type === "FILE") {
        appendFileBubble(msg.sender, msg.fileUrl, msg.fileName, msg.fileType, isSelf, ts, msg.id, type, msg.message || null);
        lastSender = null; lastGroupEl = null;
    } else {
        // Server broadcasts text as "message" field for live events
        appendTextBubble(msg.sender, msg.message, isSelf, ts, msg.id, false, msg.replyTo || null);
    }
    scrollToBottom();
}

function appendDateSep(label) {
    const el = document.createElement("div"); el.className="date-separator"; el.innerHTML=`<span>${escapeHtml(label)}</span>`;
    document.getElementById("messages").appendChild(el);
}

function appendDeletedBubble(msg) {
    const isSelf = msg.sender === getUsername();
    const g = makeGroup(msg.sender, isSelf, msg.timestamp ? parseServerDate(msg.timestamp) : new Date());
    const b = document.createElement("div"); b.className = "bubble deleted-bubble";
    if (msg.id) b.dataset.id = msg.id;
    b.innerHTML = '<em class="deleted-text">Message deleted</em>';
    g.querySelector(".group-bubbles").appendChild(b);
    document.getElementById("messages").appendChild(g);
}

function appendTextBubble(sender, text, isSelf, ts, msgId, isEdited, replyTo) {
    if (sender === lastSender && lastGroupEl) {
        const b = createTextBubble(text, isSelf, ts, msgId, isEdited, replyTo);
        lastGroupEl.querySelector(".group-bubbles").appendChild(b);
        const t = lastGroupEl.querySelector(".group-time"); if(t) t.textContent = formatTime(ts);
    } else {
        const g = makeGroup(sender, isSelf, ts);
        const b = createTextBubble(text, isSelf, ts, msgId, isEdited, replyTo);
        g.querySelector(".group-bubbles").appendChild(b);
        document.getElementById("messages").appendChild(g);
        lastGroupEl = g; lastSender = sender;
    }
}

function createTextBubble(text, isSelf, ts, msgId, isEdited, replyTo) {
    const b = document.createElement("div");
    b.className = "bubble";
    if (msgId) b.dataset.id = msgId;
    let html = "";
    if (replyTo) {
        html += `<div class="reply-preview"><span class="reply-preview-sender">${escapeHtml(replyTo.sender)}</span><span class="reply-preview-content">${escapeHtml((replyTo.content||"").substring(0,60))}</span></div>`;
    }
    html += `<span class="bubble-text-content">${escapeHtml(text||"")}</span>`;
    if (isEdited) html += ' <span class="edited-tag">(edited)</span>';
    b.innerHTML = html;
    // Read data-id at click time so MESSAGE_ID_ASSIGN patches take effect
    b.addEventListener("contextmenu", e => { e.preventDefault(); openContextMenu(e, b, b.dataset.id || null, isSelf, text); });
    b.addEventListener("click", e => { if (e.ctrlKey || e.metaKey) openContextMenu(e, b, b.dataset.id || null, isSelf, text); });
    return b;
}

/**
 * Renders an IMAGE or FILE bubble.
 * @param {string}      sender
 * @param {string}      fileUrl   - Cloudinary URL
 * @param {string}      fileName  - original filename
 * @param {string}      fileType  - MIME type (e.g. "image/png", "application/pdf")
 * @param {boolean}     isSelf
 * @param {Date}        ts
 * @param {number|null} msgId
 * @param {string}      type      - "IMAGE" | "FILE"
 * @param {string|null} caption   - optional text accompanying the file
 */
function appendFileBubble(sender, fileUrl, fileName, fileType, isSelf, ts, msgId, type, caption) {
    const g = makeGroup(sender, isSelf, ts);
    const b = document.createElement("div");
    b.className = "bubble bubble-file";
    if (msgId) b.dataset.id = msgId;
    b.dataset.msgType = type || "FILE";

    const name     = fileName || (fileUrl ? fileUrl.split("/").pop() : "file");
    const safeName = escapeHtml(name);
    const safeUrl  = escapeHtml(fileUrl || "");
    const mime     = (fileType || "").toLowerCase();

    let inner = "";

    if (type === "IMAGE" || mime.startsWith("image/")) {
        // ── Image preview ──────────────────────────────────────────────
        inner = `
            <div class="file-bubble-image">
                <img src="${safeUrl}" class="file-preview-img" alt="${safeName}"
                     loading="lazy"
                     onerror="this.closest('.file-bubble-image').innerHTML='<span class=file-err>⚠️ Image failed to load</span>'"/>
                <a href="${safeUrl}" target="_blank" rel="noopener" class="file-img-link" title="Open full image">⤢</a>
            </div>`;
    } else if (mime === "video/mp4" || mime === "video/webm" || mime === "video/ogg") {
        // ── Video ──────────────────────────────────────────────────────
        inner = `
            <div class="file-bubble-video">
                <video src="${safeUrl}" controls class="file-preview-video" preload="metadata"></video>
                <a href="${safeUrl}" target="_blank" rel="noopener" class="file-link file-link-dl">🎬 ${safeName}</a>
            </div>`;
    } else if (mime === "audio/mpeg" || mime === "audio/ogg" || mime === "audio/wav") {
        // ── Audio ──────────────────────────────────────────────────────
        inner = `
            <div class="file-bubble-audio">
                <audio src="${safeUrl}" controls class="file-preview-audio" preload="metadata"></audio>
                <a href="${safeUrl}" target="_blank" rel="noopener" class="file-link file-link-dl">🎵 ${safeName}</a>
            </div>`;
    } else {
        // ── Generic file attachment ─────────────────────────────────────
        const icon = getFileIcon(mime, name);
        const size = "";   // size not stored yet — future enhancement
        inner = `
            <div class="file-bubble-generic">
                <span class="file-icon">${icon}</span>
                <div class="file-meta">
                    <a href="${safeUrl}" target="_blank" rel="noopener" class="file-link">${safeName}</a>
                    <span class="file-type-label">${escapeHtml(mime || "file")}</span>
                </div>
                <a href="${safeUrl}" target="_blank" rel="noopener" download="${safeName}" class="file-dl-btn" title="Download">⬇</a>
            </div>`;
    }

    // Optional caption below the file
    if (caption && caption.trim()) {
        inner += `<div class="file-caption">${escapeHtml(caption.trim())}</div>`;
    }

    b.innerHTML = inner;
    b.addEventListener("contextmenu", e => { e.preventDefault(); openContextMenu(e, b, b.dataset.id || null, isSelf, caption || ""); });
    g.querySelector(".group-bubbles").appendChild(b);
    document.getElementById("messages").appendChild(g);
}

/** Returns a fitting emoji icon for a MIME type / filename */
function getFileIcon(mime, fileName) {
    if (!mime && !fileName) return "📎";
    const m = (mime || "").toLowerCase();
    const ext = (fileName || "").split(".").pop().toLowerCase();
    if (m.startsWith("image/"))                          return "🖼️";
    if (m.startsWith("video/"))                          return "🎬";
    if (m.startsWith("audio/"))                          return "🎵";
    if (m === "application/pdf" || ext === "pdf")        return "📄";
    if (m.includes("word") || ext === "doc" || ext === "docx") return "📝";
    if (m.includes("sheet") || ext === "xls" || ext === "xlsx") return "📊";
    if (m.includes("presentation") || ext === "ppt" || ext === "pptx") return "📋";
    if (m.includes("zip") || m.includes("rar") || ext === "zip" || ext === "rar") return "🗜️";
    if (m.includes("text") || ext === "txt" || ext === "md") return "📃";
    return "📎";
}

/** System/event message (e.g. "Alice joined the group") */
function appendSystemMessage(text, ts) {
    const el = document.createElement("div");
    el.className = "system-message";
    el.innerHTML = `<span>${escapeHtml(text || "")}</span>`;
    document.getElementById("messages").appendChild(el);
}

function makeGroup(sender, isSelf, ts) {
    const g = document.createElement("div"); g.className = `msg-group ${isSelf?"self":"other"}`;
    const disp = isSelf ? (getDisplayName()||"You") : escapeHtml(sender);
    // Resolve avatar: for self use sessionStorage, for others look up allUsers
    let avUrl = null;
    if (isSelf) {
        avUrl = getAvatarUrl();
    } else {
        const found = allUsers.find(u => u.username === sender);
        if (found) avUrl = found.avatarUrl;
    }
    const avHtml = avUrl
        ? `<img src="${escapeHtml(avUrl)}" class="group-avatar" style="object-fit:cover;" alt="${escapeHtml(disp)}" onerror="this.outerHTML='<div class=group-avatar>${escapeHtml((sender||'?').charAt(0).toUpperCase())}</div>'">`
        : `<div class="group-avatar">${escapeHtml((sender||"?").charAt(0).toUpperCase())}</div>`;
    g.innerHTML = `${avHtml}<div class="group-body"><div class="group-meta"><span class="group-sender">${disp}</span><span class="group-time">${formatTime(ts)}</span></div><div class="group-bubbles"></div></div>`;
    return g;
}

function scrollToBottom() { const el = document.getElementById("messages"); if(el) el.scrollTop = el.scrollHeight; }

// ──────────────────────────────────────────────
//  Reactions
// ──────────────────────────────────────────────
async function refreshReactions(msgId) {
    const bubble = document.querySelector(`.bubble[data-id="${msgId}"]`);
    if (!bubble) return;
    try {
        const r = await fetchWithAuth(`/api/messages/${msgId}/reactions`);
        const d = await r.json();
        let bar = bubble.querySelector(".reaction-bar");
        if (!bar) { bar = document.createElement("div"); bar.className = "reaction-bar"; bubble.appendChild(bar); }
        bar.innerHTML = "";
        const counts = d.counts || {};
        Object.entries(counts).forEach(([emoji, count]) => {
            const pill = document.createElement("span");
            pill.className = "reaction-pill"; pill.innerHTML = `${emoji} <span>${count}</span>`;
            pill.addEventListener("click", () => toggleReaction(msgId, emoji));
            bar.appendChild(pill);
        });
    } catch(e) {}
}

async function toggleReaction(msgId, emoji) {
    try {
        await fetchWithAuth(`/api/messages/${msgId}/react`, { method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify({ emoji }) });
        // The REST call triggers a WS broadcast back to this room (including sender),
        // so refreshReactions will be called by handleWsMessage for everyone.
        // Still refresh locally in case WS is slow or user is offline.
        refreshReactions(msgId);
    } catch(e) {}
}

// ──────────────────────────────────────────────
//  Context menu
// ──────────────────────────────────────────────
function openContextMenu(e, bubbleEl, msgId, isSelf, content) {
    e.preventDefault();
    const menu = document.getElementById("msgContextMenu");
    ctxMsgEl = bubbleEl;
    // Always read the latest id from the DOM (MESSAGE_ID_ASSIGN may have patched it)
    const liveId = bubbleEl.dataset.id || msgId;
    ctxMsgId = liveId ? Number(liveId) : null;
    // Show/hide edit+delete based on ownership
    document.getElementById("ctxEdit").style.display   = isSelf && ctxMsgId ? "block" : "none";
    document.getElementById("ctxDelete").style.display = (isSelf || hasRole("ROLE_ADMIN") || hasRole("ROLE_MODERATOR")) && ctxMsgId ? "block" : "none";
    menu.style.display  = "block";
    menu.style.left     = Math.min(e.clientX, window.innerWidth - 180) + "px";
    menu.style.top      = Math.min(e.clientY, window.innerHeight - 200) + "px";
    document.addEventListener("click", closeContextMenu, { once: true });
}
function closeContextMenu() {
    document.getElementById("msgContextMenu").style.display = "none";
    document.getElementById("reactionPicker").style.display = "none";
}

// ──────────────────────────────────────────────
//  Pinned messages panel
// ──────────────────────────────────────────────
async function openPinnedPanel() {
    const panel = document.getElementById("pinnedPanel");
    const list  = document.getElementById("pinnedPanelList");
    panel.style.display = "flex"; list.innerHTML = '<div class="members-loading">Loading…</div>';
    try {
        const r = await fetchWithAuth(`/api/messages/room/${encodeURIComponent(currentRoomName)}/pinned`);
        const msgs = await r.json();
        list.innerHTML = "";
        if (!msgs || msgs.length === 0) { list.innerHTML = '<div class="members-loading">No pinned messages.</div>'; return; }
        msgs.forEach(m => {
            const row = document.createElement("div"); row.className = "member-row";
            row.innerHTML = `<div style="flex:1;"><span style="font-size:.8em;color:var(--muted)">${escapeHtml(m.sender)} · ${formatTime(m.timestamp)}</span><br><span>${escapeHtml((m.content||"").substring(0,100))}</span></div>`;
            list.appendChild(row);
        });
    } catch(e) { list.innerHTML = '<div class="members-loading">Failed to load.</div>'; }
}

// ──────────────────────────────────────────────
//  Message search
// ──────────────────────────────────────────────
let searchTimeout = null;
function onSearchInput(query) {
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(async () => {
        if (!query.trim() || !currentRoomName) return;
        try {
            const r = await fetchWithAuth(`/api/messages/room/${encodeURIComponent(currentRoomName)}/search?q=${encodeURIComponent(query)}&limit=20`);
            const msgs = await r.json();
            const area = document.getElementById("messages");
            area.innerHTML = "";
            if (!msgs.length) { area.innerHTML = '<div class="no-msgs-hint">No results found.</div>'; return; }
            resetGroupState();
            renderHistory(msgs);
        } catch(e) {}
    }, 400);
}

// ──────────────────────────────────────────────
//  File upload
// ──────────────────────────────────────────────
function handleFileUpload(e) {
    const file = e.target.files[0]; if(!file) return;
    const fd = new FormData(); fd.append("file", file);
    fetchWithAuth("/api/files/upload", { method:"POST", body:fd })
    .then(r=>r.json()).then(data => {
        if(socket?.readyState===WebSocket.OPEN) {
            // Do NOT include sender — backend reads it from the authenticated WS session.
            socket.send(JSON.stringify({
                eventType: "MESSAGE",
                room:      currentRoomName,
                fileUrl:   data.fileUrl,
                fileName:  data.fileName,
                fileType:  data.fileType   // was data.type — wrong field name
            }));
        }
    })
    .catch(err=>showBanner("roomError","Upload failed: "+err.message));
    e.target.value="";
}

// ──────────────────────────────────────────────
//  Emoji picker
// ──────────────────────────────────────────────
const COMMON_EMOJIS = ["😀","😂","😍","🥰","😎","🤔","😮","😢","😡","👍","👎","❤️","🔥","🎉","✅","🙏","💪","🚀","🌟","💯","😊","🤗","😴","🥳","🤣","😇","🙌","👏","💬","📎"];
function toggleEmojiPicker() {
    const picker = document.getElementById("emojiPicker");
    if (picker.style.display === "none" || !picker.style.display) {
        const grid = document.getElementById("emojiGrid"); grid.innerHTML = "";
        COMMON_EMOJIS.forEach(emoji => {
            const span = document.createElement("span"); span.textContent = emoji; span.className = "emoji-item";
            span.addEventListener("click", () => { const inp = document.getElementById("messageInput"); inp.value += emoji; inp.focus(); picker.style.display = "none"; });
            grid.appendChild(span);
        });
        picker.style.display = "block";
    } else { picker.style.display = "none"; }
}

// ──────────────────────────────────────────────
//  New Group Modal
// ──────────────────────────────────────────────
let selectedMembers = new Set();

function openGroupModal() {
    selectedMembers = new Set();
    document.getElementById("groupNameInput").value  = "";
    document.getElementById("groupDescInput").value  = "";
    document.getElementById("memberSearchInput").value = "";
    document.getElementById("groupModalError").style.display = "none";
    renderMemberPickList(allUsers); renderChips();
    document.getElementById("groupModal").style.display = "flex";
    document.getElementById("groupNameInput").focus();
}
function closeGroupModal() { document.getElementById("groupModal").style.display = "none"; }

function renderMemberPickList(users) {
    const list = document.getElementById("memberPickList"); list.innerHTML = "";
    users.forEach(u => {
        const item = document.createElement("div"); item.className = "pick-item" + (selectedMembers.has(u.username)?" selected":""); item.dataset.username = u.username;
        item.innerHTML = `<div class="user-avatar sm">${escapeHtml(u.username.charAt(0).toUpperCase())}</div><span>${escapeHtml(u.displayName||u.username)}</span><span class="pick-check">${selectedMembers.has(u.username)?"✓":""}</span>`;
        item.addEventListener("click", () => toggleMember(u.username));
        list.appendChild(item);
    });
}
function toggleMember(username) {
    if (selectedMembers.has(username)) selectedMembers.delete(username); else selectedMembers.add(username);
    renderChips();
    const q = document.getElementById("memberSearchInput").value.toLowerCase();
    renderMemberPickList(q ? allUsers.filter(u=>u.username.toLowerCase().includes(q)) : allUsers);
}
function renderChips() {
    const row = document.getElementById("selectedMemberChips"); row.innerHTML="";
    selectedMembers.forEach(name => { const chip = document.createElement("span"); chip.className="chip"; chip.innerHTML=`${escapeHtml(name)} <button onclick="toggleMember('${escapeHtml(name)}')" class="chip-remove">✕</button>`; row.appendChild(chip); });
}
function filterMemberList(query) {
    const q = query.toLowerCase();
    renderMemberPickList(q ? allUsers.filter(u=>u.username.toLowerCase().includes(q)||((u.displayName||"").toLowerCase().includes(q))) : allUsers);
}
function submitCreateGroup() {
    const groupName = document.getElementById("groupNameInput").value.trim();
    const description = document.getElementById("groupDescInput").value.trim();
    const errEl = document.getElementById("groupModalError"); errEl.style.display="none";
    const createBtn = document.getElementById("createGroupBtn");
    if (!groupName) { errEl.textContent="Group name is required."; errEl.style.display="block"; return; }
    if (selectedMembers.size === 0) { errEl.textContent="Select at least one member."; errEl.style.display="block"; return; }

    // Disable button to prevent double-submit
    if (createBtn) { createBtn.disabled = true; createBtn.textContent = "Creating…"; }

    fetchWithAuth("/api/chat/rooms/group", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({ groupName, description, members: [...selectedMembers] })
    })
    .then(r => {
        if (!r.ok) return r.json().then(d => { throw new Error(d.error || "Failed to create group"); });
        return r.json();
    })
    .then(data => {
        closeGroupModal();
        switchTab("groups");
        // The GROUP_CREATED WS push will add the room to the sidebar for all members.
        // For the creator, also ensure the entry exists immediately (WS may arrive slightly later)
        // and open the room. Use a short delay so the DB transaction is visible server-side.
        setTimeout(() => {
            ensureRoomInList("groupList", data.roomName, data.roomName, "GROUP", data.groupRole || "ADMIN");
            switchRoom(data.roomName, "GROUP", data.roomName, data.groupRole || "ADMIN");
        }, 150);
    })
    .catch(err => {
        errEl.textContent = err.message || "Failed to create group.";
        errEl.style.display = "block";
    })
    .finally(() => {
        if (createBtn) { createBtn.disabled = false; createBtn.textContent = "Create Group"; }
    });
}

// ──────────────────────────────────────────────
//  Add Member Modal
// ──────────────────────────────────────────────
let addMemberSelected = null;

function openAddMemberModal() {
    addMemberSelected = null;
    document.getElementById("addMemberSearchInput").value = "";
    document.getElementById("addMemberError").style.display = "none";
    document.getElementById("confirmAddMemberBtn").disabled = true;
    document.getElementById("addMemberGroupName").textContent = currentRoomDisplay || currentRoomName;
    fetchWithAuth(`/api/chat/rooms/${encodeURIComponent(currentRoomName)}/members`)
    .then(r=>r.json()).then(memberObjs => {
        const existing = new Set(memberObjs.map(m=>m.username));
        renderAddMemberPickList(allUsers.filter(u => !existing.has(u.username)));
    }).catch(() => renderAddMemberPickList(allUsers));
    document.getElementById("addMemberModal").style.display = "flex";
}
function closeAddMemberModal() { document.getElementById("addMemberModal").style.display = "none"; addMemberSelected = null; }

function renderAddMemberPickList(users) {
    const list = document.getElementById("addMemberPickList"); list.innerHTML = "";
    if (!users || users.length===0) { list.innerHTML = '<div style="padding:12px;color:#94a3b8;font-size:.85em;">All users are already in this group.</div>'; return; }
    list._candidates = users;
    users.forEach(u => {
        const item = document.createElement("div"); item.className = "pick-item"+(addMemberSelected===u.username?" selected":""); item.dataset.username=u.username;
        item.innerHTML = `<div class="user-avatar sm">${escapeHtml(u.username.charAt(0).toUpperCase())}</div><span>${escapeHtml(u.displayName||u.username)}</span><span class="pick-check">${addMemberSelected===u.username?"✓":""}</span>`;
        item.addEventListener("click", () => { addMemberSelected = addMemberSelected===u.username?null:u.username; document.getElementById("confirmAddMemberBtn").disabled=!addMemberSelected; const q=document.getElementById("addMemberSearchInput").value.toLowerCase(); renderAddMemberPickList(q?users.filter(x=>x.username.toLowerCase().includes(q)):users); });
        list.appendChild(item);
    });
}
function filterAddMemberList(query) {
    const candidates = document.getElementById("addMemberPickList")._candidates || allUsers;
    const q = query.toLowerCase();
    renderAddMemberPickList(q ? candidates.filter(u=>u.username.toLowerCase().includes(q)) : candidates);
}
function confirmAddMember() {
    if (!addMemberSelected || !currentRoomName) return;
    const usernameToAdd = addMemberSelected;
    const errEl = document.getElementById("addMemberError"); errEl.style.display = "none";
    const btn = document.getElementById("confirmAddMemberBtn"); btn.disabled = true; btn.textContent = "Adding…";
    fetchWithAuth(`/api/chat/rooms/${encodeURIComponent(currentRoomName)}/add-member/${encodeURIComponent(usernameToAdd)}`, { method:"POST", headers:{"Content-Type":"application/json"} })
    .then(r=>r.json()).then(() => { closeAddMemberModal(); showBanner("roomError", `✅ ${usernameToAdd} was added to the group.`, "success"); })
    .catch(err => { errEl.textContent=err.message||"Failed."; errEl.style.display="block"; btn.disabled=false; btn.textContent="Add Selected"; });
}

// ──────────────────────────────────────────────
//  Members Panel
// ──────────────────────────────────────────────
function openMembersPanel() {
    const panel = document.getElementById("membersPanel"), list = document.getElementById("membersPanelList");
    list.innerHTML = '<div class="members-loading">Loading…</div>'; panel.style.display = "flex";
    fetchWithAuth(`/api/chat/rooms/${encodeURIComponent(currentRoomName)}/members`)
    .then(r=>r.json()).then(members => {
        list.innerHTML = "";
        members.forEach(m => {
            const isMe=m.username===getUsername(), isAdmin=m.groupAdmin, callerAdmin=currentGroupRole==="ADMIN";
            const statusCls = (m.status||"offline").toLowerCase();
            const row = document.createElement("div");
            row.className = "member-row";
            row.dataset.username = m.username;
            row.innerHTML = `
                <div class="user-avatar sm">${escapeHtml(m.username.charAt(0).toUpperCase())}</div>
                <span class="member-name">${escapeHtml(m.displayName||m.username)}${isMe?" <span class='you-tag'>(you)</span>":""}</span>
                ${isAdmin?"<span class='admin-crown' title='Admin'>👑</span>":""}
                <span class="status-dot ${statusCls}" title="${statusCls}"></span>
                <div class="member-actions">
                    ${callerAdmin&&!isAdmin&&!isMe?`<button class="btn-remove-member" onclick="removeMember('${escapeHtml(m.username)}')" title="Remove">✕</button>`:""}
                    ${isMe&&!isAdmin?`<button class="btn-leave-member" onclick="leaveRoom()" title="Leave">Leave</button>`:""}
                </div>`;
            list.appendChild(row);
        });
    }).catch(() => { list.innerHTML = '<div class="members-loading">Could not load.</div>'; });
}
function closeMembersPanel() { document.getElementById("membersPanel").style.display = "none"; }

function removeMember(username) {
    if (!confirm(`Remove ${username} from the group?`)) return;
    fetchWithAuth(`/api/chat/rooms/${encodeURIComponent(currentRoomName)}/remove-member/${encodeURIComponent(username)}`, { method:"DELETE" })
    .then(r=>r.json()).then(() => { openMembersPanel(); showBanner("roomError", `✅ ${username} was removed.`, "success"); })
    .catch(err => showBanner("roomError", err.message || "Failed."));
}

// ──────────────────────────────────────────────
//  DOMContentLoaded bootstrap
// ──────────────────────────────────────────────
document.addEventListener("DOMContentLoaded", () => {

    // Auth pages
    document.getElementById("loginForm")?.addEventListener("submit", handleLogin);
    document.getElementById("registerForm")?.addEventListener("submit", handleRegister);

    // Chat page
    if (!document.getElementById("peopleList")) return;

    // Refresh UI when returning from another page (e.g. profile) via bfcache
    window.addEventListener("pageshow", e => {
        if (e.persisted) {
            requireAuth().then(ok => {
                if (!ok) return;
                setupRoleUI();
                connectSidebarSocket();   // re-establish sidebar WS after bfcache restore
                loadPeopleList();
                if (currentRoomName) loadChatHistory(currentRoomName);
            });
        }
    });
    // Also refresh on window focus (covers tab switches and navigation)
    window.addEventListener("focus", () => {
        const storedAv = getAvatarUrl();
        const navAv = document.getElementById("navAvatar");
        if (navAv) {
            const currentImg = navAv.querySelector("img");
            if (storedAv && (!currentImg || currentImg.src !== storedAv)) {
                setupRoleUI();
            }
        }
    });

    // Logout
    document.getElementById("logoutButton")?.addEventListener("click", () => {
        clearTimeout(_sidebarReconnectTimer);
        if (socket) { socket.onclose = null; socket.close(); }
        if (sidebarSocket) { sidebarSocket.onclose = null; sidebarSocket.close(); }
        fetch("/api/auth/logout", { method:'POST', credentials:'include' }).finally(() => { clearSession(); window.location.href="/api/v1/login"; });
    });

    // People search
    document.getElementById("peopleSearch")?.addEventListener("input", e => filterPeople(e.target.value));

    // Send message
    document.getElementById("sendMessageButton")?.addEventListener("click", sendMessage);
    document.getElementById("messageInput")?.addEventListener("keydown", e => { if(e.key==="Enter"&&!e.shiftKey){e.preventDefault();sendMessage();} });
    document.getElementById("messageInput")?.addEventListener("input", onMessageInputChange);

    // Reply
    document.getElementById("cancelReply")?.addEventListener("click", cancelReply);

    // Leave
    document.getElementById("leaveRoomButton")?.addEventListener("click", leaveRoom);

    // File attach
    document.getElementById("attachFileButton")?.addEventListener("click", () => document.getElementById("fileInput")?.click());
    document.getElementById("fileInput")?.addEventListener("change", handleFileUpload);

    // Emoji picker
    document.getElementById("emojiPickerBtn")?.addEventListener("click", e => { e.stopPropagation(); toggleEmojiPicker(); });
    document.addEventListener("click", e => { const picker = document.getElementById("emojiPicker"); if (picker && !picker.contains(e.target) && e.target.id !== "emojiPickerBtn") picker.style.display="none"; });

    // Mod tools
    document.getElementById("modToolsBtn")?.addEventListener("click", () => { const bar=document.getElementById("modTools"); if(bar) bar.style.display=bar.style.display==="none"?"block":"none"; });

    // Search messages
    document.getElementById("searchMsgBtn")?.addEventListener("click", () => {
        const bar = document.getElementById("searchBar");
        bar.style.display = bar.style.display === "none" || !bar.style.display ? "flex" : "none";
        if (bar.style.display === "flex") document.getElementById("searchInput").focus();
        else loadChatHistory(currentRoomName);
    });
    document.getElementById("searchInput")?.addEventListener("input", e => onSearchInput(e.target.value));
    document.getElementById("searchClearBtn")?.addEventListener("click", () => { document.getElementById("searchInput").value=""; document.getElementById("searchBar").style.display="none"; if(currentRoomName) loadChatHistory(currentRoomName); });

    // Pinned messages
    document.getElementById("pinnedMsgBtn")?.addEventListener("click", () => {
        const panel = document.getElementById("pinnedPanel");
        if (panel.style.display === "none" || !panel.style.display) { if(currentRoomName) openPinnedPanel(); }
        else panel.style.display = "none";
    });
    document.getElementById("closePinnedPanel")?.addEventListener("click", () => document.getElementById("pinnedPanel").style.display="none");

    // New group
    document.getElementById("newGroupBtn")?.addEventListener("click", e => { e.stopPropagation(); openGroupModal(); });
    document.getElementById("closeGroupModal")?.addEventListener("click", closeGroupModal);
    document.getElementById("cancelGroupBtn")?.addEventListener("click", closeGroupModal);
    document.getElementById("createGroupBtn")?.addEventListener("click", submitCreateGroup);
    document.getElementById("groupNameInput")?.addEventListener("keydown", e => { if(e.key==="Enter") submitCreateGroup(); });
    document.getElementById("memberSearchInput")?.addEventListener("input", e => filterMemberList(e.target.value));
    document.getElementById("groupModal")?.addEventListener("click", e => { if(e.target===e.currentTarget) closeGroupModal(); });

    // Members panel
    document.getElementById("membersBtn")?.addEventListener("click", openMembersPanel);
    document.getElementById("closeMembersPanel")?.addEventListener("click", closeMembersPanel);

    // Add Member modal
    document.getElementById("addMemberBtn")?.addEventListener("click", openAddMemberModal);
    document.getElementById("closeAddMemberModal")?.addEventListener("click", closeAddMemberModal);
    document.getElementById("cancelAddMemberBtn")?.addEventListener("click", closeAddMemberModal);
    document.getElementById("confirmAddMemberBtn")?.addEventListener("click", confirmAddMember);
    document.getElementById("addMemberSearchInput")?.addEventListener("input", e => filterAddMemberList(e.target.value));
    document.getElementById("addMemberModal")?.addEventListener("click", e => { if(e.target===e.currentTarget) closeAddMemberModal(); });

    // Context menu actions
    document.getElementById("ctxReply")?.addEventListener("click", () => {
        if (!ctxMsgEl || !ctxMsgId) return;
        const textEl = ctxMsgEl.querySelector(".bubble-text-content");
        const sender = ctxMsgEl.closest(".msg-group")?.querySelector(".group-sender")?.textContent || "";
        setReply(ctxMsgId, sender, textEl?.textContent || "");
        closeContextMenu();
    });
    document.getElementById("ctxReact")?.addEventListener("click", e => {
        e.stopPropagation();
        const menu = document.getElementById("msgContextMenu");
        const picker = document.getElementById("reactionPicker");
        picker.style.display = "block";
        picker.style.left = menu.style.left;
        picker.style.top  = (parseInt(menu.style.top) + menu.offsetHeight) + "px";
        picker.querySelectorAll("[data-emoji]").forEach(span => {
            span.onclick = () => { if(ctxMsgId) { toggleReaction(ctxMsgId, span.dataset.emoji); } closeContextMenu(); };
        });
    });
    document.getElementById("ctxEdit")?.addEventListener("click", async () => {
        if (!ctxMsgId || !ctxMsgEl) return;
        const textEl = ctxMsgEl.querySelector(".bubble-text-content");
        const newText = prompt("Edit message:", textEl?.textContent || "");
        if (!newText || newText.trim() === textEl?.textContent) return;
        try {
            await fetchWithAuth(`/api/messages/${ctxMsgId}`, { method:"PATCH", headers:{"Content-Type":"application/json"}, body:JSON.stringify({ content: newText.trim() }) });
        } catch(err) { showBanner("roomError", err.message); }
        closeContextMenu();
    });
    document.getElementById("ctxPin")?.addEventListener("click", async () => {
        if (!ctxMsgId) return;
        try {
            await fetchWithAuth(`/api/messages/${ctxMsgId}/pin`, { method:"POST" });
        } catch(err) { showBanner("roomError", err.message); }
        closeContextMenu();
    });
    document.getElementById("ctxDelete")?.addEventListener("click", async () => {
        if (!ctxMsgId || !confirm("Delete this message?")) return;
        try {
            await fetchWithAuth(`/api/messages/${ctxMsgId}`, { method:"DELETE" });
        } catch(err) { showBanner("roomError", err.message); }
        closeContextMenu();
    });

    // Notifications panel
    document.getElementById("notifBtn")?.addEventListener("click", e => {
        e.stopPropagation();
        const panel = document.getElementById("notifPanel");
        if (panel.style.display === "none" || !panel.style.display) { panel.style.display = "flex"; loadNotifications(); }
        else panel.style.display = "none";
    });
    document.getElementById("closeNotifPanel")?.addEventListener("click", () => document.getElementById("notifPanel").style.display="none");
    document.getElementById("markAllReadBtn")?.addEventListener("click", async () => {
        await fetchWithAuth("/api/notifications/read-all", { method:"PATCH" });
        loadNotifications(); updateNotifBadge(0);
    });
    document.addEventListener("click", e => {
        const panel = document.getElementById("notifPanel");
        const btn   = document.getElementById("notifBtn");
        if (panel && panel.style.display !== "none" && !panel.contains(e.target) && e.target !== btn && !btn?.contains(e.target)) panel.style.display = "none";
    });
});
