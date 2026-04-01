package com.example.websocket.service;

import com.example.websocket.model.*;
import com.example.websocket.repo.FriendshipRepository;
import com.example.websocket.repo.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@Service
public class FriendshipService {

    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public FriendshipService(FriendshipRepository friendshipRepository,
                              UserRepository userRepository,
                              NotificationService notificationService) {
        this.friendshipRepository = friendshipRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public Friendship sendFriendRequest(String fromUsername, String toUsername) {
        if (fromUsername.equals(toUsername)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot send friend request to yourself.");
        User from = getUserOrThrow(fromUsername);
        User to = getUserOrThrow(toUsername);

        // Check existing
        Optional<Friendship> existing = friendshipRepository.findByRequesterAndAddressee(from, to);
        if (existing.isEmpty()) existing = friendshipRepository.findByRequesterAndAddressee(to, from);
        if (existing.isPresent()) {
            Friendship f = existing.get();
            if (f.getStatus() == FriendshipStatus.ACCEPTED) throw new ResponseStatusException(HttpStatus.CONFLICT, "Already friends.");
            if (f.getStatus() == FriendshipStatus.PENDING) throw new ResponseStatusException(HttpStatus.CONFLICT, "Request already pending.");
            if (f.getStatus() == FriendshipStatus.BLOCKED) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot send friend request.");
        }

        Friendship f = new Friendship();
        f.setRequester(from);
        f.setAddressee(to);
        f.setStatus(FriendshipStatus.PENDING);
        Friendship saved = friendshipRepository.save(f);
        notificationService.createNotification(to, NotificationType.FRIEND_REQUEST, saved.getId(), fromUsername + " sent you a friend request.");
        return saved;
    }

    @Transactional
    public Friendship acceptRequest(Long requestId, String acceptorUsername) {
        Friendship f = getFriendshipOrThrow(requestId);
        if (!f.getAddressee().getUsername().equals(acceptorUsername))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your friend request.");
        if (f.getStatus() != FriendshipStatus.PENDING)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request is not pending.");
        f.setStatus(FriendshipStatus.ACCEPTED);
        Friendship saved = friendshipRepository.save(f);
        notificationService.createNotification(f.getRequester(), NotificationType.FRIEND_ACCEPTED, saved.getId(), acceptorUsername + " accepted your friend request.");
        return saved;
    }

    @Transactional
    public void declineRequest(Long requestId, String declinerUsername) {
        Friendship f = getFriendshipOrThrow(requestId);
        if (!f.getAddressee().getUsername().equals(declinerUsername) && !f.getRequester().getUsername().equals(declinerUsername))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your friend request.");
        friendshipRepository.delete(f);
    }

    @Transactional
    public void blockUser(String blockerUsername, String targetUsername) {
        User blocker = getUserOrThrow(blockerUsername);
        User target = getUserOrThrow(targetUsername);
        // Remove any existing friendship
        friendshipRepository.findByRequesterAndAddressee(blocker, target).ifPresent(friendshipRepository::delete);
        friendshipRepository.findByRequesterAndAddressee(target, blocker).ifPresent(friendshipRepository::delete);
        Friendship f = new Friendship();
        f.setRequester(blocker);
        f.setAddressee(target);
        f.setStatus(FriendshipStatus.BLOCKED);
        friendshipRepository.save(f);
    }

    public List<Friendship> getFriends(String username) {
        User user = getUserOrThrow(username);
        return friendshipRepository.findAllByUserAndStatus(user, FriendshipStatus.ACCEPTED);
    }

    public List<Friendship> getPendingRequests(String username) {
        User user = getUserOrThrow(username);
        return friendshipRepository.findAllByAddresseeAndStatus(user, FriendshipStatus.PENDING);
    }

    private User getUserOrThrow(String username) {
        User u = userRepository.findByUsername(username);
        if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + username);
        return u;
    }

    private Friendship getFriendshipOrThrow(Long id) {
        return friendshipRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Friend request not found."));
    }
}
