package com.example.websocket.repo;

import com.example.websocket.model.Friendship;
import com.example.websocket.model.FriendshipStatus;
import com.example.websocket.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    Optional<Friendship> findByRequesterAndAddressee(User requester, User addressee);

    List<Friendship> findAllByRequesterOrAddressee(User requester, User addressee);

    List<Friendship> findAllByAddresseeAndStatus(User addressee, FriendshipStatus status);

    @Query("SELECT f FROM Friendship f WHERE (f.requester = :user OR f.addressee = :user) AND f.status = :status")
    List<Friendship> findAllByUserAndStatus(@Param("user") User user, @Param("status") FriendshipStatus status);

    @Query("SELECT CASE WHEN COUNT(f) > 0 THEN true ELSE false END FROM Friendship f " +
           "WHERE ((f.requester = :u1 AND f.addressee = :u2) OR (f.requester = :u2 AND f.addressee = :u1)) " +
           "AND f.status = 'BLOCKED'")
    boolean isBlocked(@Param("u1") User u1, @Param("u2") User u2);
}
