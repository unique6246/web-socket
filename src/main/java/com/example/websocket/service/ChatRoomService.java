package com.example.websocket.service;
import com.example.websocket.model.*;
import com.example.websocket.repo.*;
import jakarta.transaction.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;
@Service
public class ChatRoomService {
    private final ChatRoomUserRepository chatRoomUserRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    public ChatRoomService(ChatRoomRepository chatRoomRepository, UserRepository userRepository,
                           ChatRoomUserRepository chatRoomUserRepository, MessageRepository messageRepository) {
        this.chatRoomRepository = chatRoomRepository;
        this.userRepository = userRepository;
        this.chatRoomUserRepository = chatRoomUserRepository;
        this.messageRepository = messageRepository;
    }
    public List<ChatRoom> searchRoomsByName(String query) {
        return chatRoomRepository.findByRoomNameContainingIgnoreCase(query);
    }
    @Cacheable(value = "chatRoomsByName", key = "#username")
    public List<String> getRoomsByUserName(String username) {
        return chatRoomUserRepository.findRoomNamesByUsername(username);
    }
    public List<Map<String, String>> getRoomsWithTypeByUserName(String username) {
        List<String> roomNames = chatRoomUserRepository.findRoomNamesByUsername(username);
        List<Map<String, String>> result = new ArrayList<>();
        for (String name : roomNames) {
            chatRoomRepository.findByRoomName(name).ifPresent(room -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("roomName", room.getRoomName());
                m.put("type", room.getType() != null ? room.getType() : "GROUP");
                // include groupRole so sidebar knows admin status immediately
                if ("GROUP".equals(room.getType())) {
                    boolean isAdmin = room.getChatRoomUsers().stream()
                            .anyMatch(cru -> cru.getUser().getUsername().equals(username) && cru.isGroupAdmin());
                    m.put("groupRole", isAdmin ? "ADMIN" : "MEMBER");
                }
                result.add(m);
            });
        }
        return result;
    }
    @Cacheable(value = "chatMessagesByRoom", key = "#roomName")
    public List<Message> getMessagesByRoomName(String roomName) {
        ChatRoom chatRoom = chatRoomRepository.findByRoomName(roomName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        return messageRepository.findMessagesByChatRoom(chatRoom);
    }
    @Transactional
    @CacheEvict(value = "chatMessagesByRoom", key = "#roomName")
    public void saveMessage(String roomName, String sender, String msgContent, String fileUrl, String fileType, String fileName) {
        ChatRoom chatRoom = chatRoomRepository.findByRoomName(roomName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        Message msg = new Message();
        msg.setContent(msgContent);
        msg.setSender(sender);
        msg.setFileUrl(fileUrl);
        msg.setFileType(fileType);
        msg.setFileName(fileName);
        msg.setChatRoom(chatRoom);
        msg.setTimestamp(LocalDateTime.now());
        messageRepository.save(msg);
    }
    @Transactional
    @CacheEvict(value = "chatRoomsByName", key = "#username")
    public ChatRoom createOrUpdateChatRoom(String roomName, String username) {
        Long userId = userRepository.findByUsername(username).getId();
        Optional<ChatRoom> chatRoomOptional = chatRoomRepository.findByRoomName(roomName);
        ChatRoom room;
        if (chatRoomOptional.isPresent()) {
            room = chatRoomOptional.get();
            boolean userAlreadyInRoom = room.getChatRoomUsers().stream()
                    .anyMatch(chatRoomUser -> chatRoomUser.getUser().getId().equals(userId));
            if (!userAlreadyInRoom) {
                userRepository.findById(userId).ifPresent(user -> {
                    ChatRoomUser chatRoomUser = new ChatRoomUser();
                    chatRoomUser.setChatRoom(room);
                    chatRoomUser.setUser(user);
                    room.getChatRoomUsers().add(chatRoomUser);
                });
            }
        } else {
            room = new ChatRoom();
            room.setRoomName(roomName);
            userRepository.findById(userId).ifPresent(user -> {
                ChatRoomUser chatRoomUser = new ChatRoomUser();
                chatRoomUser.setChatRoom(room);
                chatRoomUser.setUser(user);
                room.getChatRoomUsers().add(chatRoomUser);
            });
            chatRoomRepository.save(room);
        }
        return room;
    }
    @Transactional
    @CacheEvict(value = "chatRoomsByName", allEntries = true)
    public ChatRoom createOrGetDmRoom(String currentUser, String otherUser) {
        Optional<ChatRoom> existing = chatRoomUserRepository.findDmRoom(currentUser, otherUser);
        if (existing.isPresent()) return existing.get();
        String[] sorted = {currentUser, otherUser};
        Arrays.sort(sorted);
        String roomName = "dm__" + sorted[0] + "__" + sorted[1];
        Optional<ChatRoom> byName = chatRoomRepository.findByRoomName(roomName);
        if (byName.isPresent()) return byName.get();
        ChatRoom room = new ChatRoom();
        room.setRoomName(roomName);
        room.setType("DM");
        for (String uname : new String[]{currentUser, otherUser}) {
            User u = userRepository.findByUsername(uname);
            if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + uname);
            ChatRoomUser cru = new ChatRoomUser();
            cru.setChatRoom(room);
            cru.setUser(u);
            room.getChatRoomUsers().add(cru);
        }
        chatRoomRepository.save(room);
        return room;
    }
    @Transactional
    @CacheEvict(value = "chatRoomsByName", allEntries = true)
    public ChatRoom createGroupRoom(String groupName, List<String> memberUsernames, String creatorUsername) {
        if (chatRoomRepository.findByRoomName(groupName).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Group name already taken");
        }
        ChatRoom room = new ChatRoom();
        room.setRoomName(groupName);
        room.setType("GROUP");
        for (String uname : memberUsernames) {
            User u = userRepository.findByUsername(uname);
            if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + uname);
            ChatRoomUser cru = new ChatRoomUser();
            cru.setChatRoom(room);
            cru.setUser(u);
            cru.setGroupAdmin(uname.equals(creatorUsername)); // creator is group admin
            room.getChatRoomUsers().add(cru);
        }
        chatRoomRepository.save(room);
        return room;
    }

    /** Returns "ADMIN" if the user is group admin of the room, else "MEMBER" */
    public String getMyRoleInRoom(String roomName, String username) {
        ChatRoom room = chatRoomRepository.findByRoomName(roomName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        return room.getChatRoomUsers().stream()
                .filter(cru -> cru.getUser().getUsername().equals(username) && cru.isGroupAdmin())
                .findFirst()
                .map(cru -> "ADMIN")
                .orElse("MEMBER");
    }

    /** Remove a member from a group — caller must be the group admin */
    @Transactional
    @CacheEvict(value = "chatRoomsByName", allEntries = true)
    public void removeMemberByAdmin(String roomName, String callerUsername, String targetUsername) {
        ChatRoom room = chatRoomRepository.findByRoomName(roomName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        // Verify caller is group admin
        boolean isAdmin = room.getChatRoomUsers().stream()
                .anyMatch(cru -> cru.getUser().getUsername().equals(callerUsername) && cru.isGroupAdmin());
        if (!isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the group admin can remove members");
        // Cannot remove yourself via this endpoint — use leave instead
        if (callerUsername.equals(targetUsername))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Group admin cannot remove themselves — use Leave instead");
        room.getChatRoomUsers().removeIf(cru -> cru.getUser().getUsername().equals(targetUsername));
        chatRoomRepository.save(room);
    }
    @Transactional
    @CacheEvict(value = "chatMessagesByRoom", key = "#roomName")
    public void deleteMessage(Long messageId, String roomName) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
        messageRepository.delete(message);
    }
    @Transactional
    @CacheEvict(value = "chatRoomsByName", key = "#username")
    public void removeUserFromRoom(String username, String roomName) {
        ChatRoom chatRoom = chatRoomRepository.findByRoomName(roomName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        User user = userRepository.findByUsername(username);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        chatRoom.getChatRoomUsers().removeIf(cru -> cru.getUser().getId().equals(user.getId()));
        chatRoomRepository.save(chatRoom);
    }
    @Transactional
    @CacheEvict(value = {"chatRoomsByName", "chatMessagesByRoom"}, allEntries = true)
    public void deleteRoom(Long roomId) {
        chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        chatRoomRepository.deleteById(roomId);
    }
    public ChatRoom getRoomDetails(String roomName) {
        return chatRoomRepository.findByRoomName(roomName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
    }
    public List<ChatRoom> getAllRooms() {
        return chatRoomRepository.findAll();
    }
}
