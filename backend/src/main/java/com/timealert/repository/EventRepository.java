package com.timealert.repository;

import com.timealert.model.Event;
import com.timealert.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * Fetches all events for a given date across all users, JOIN FETCHing the
     * user in the same query to avoid N+1 selects. Used only by NotificationService
     * to check all today's events for scheduling — the service then filters by
     * user.isDemo() and user.getEmail() per event.
     */
    @Query("SELECT e FROM Event e JOIN FETCH e.user WHERE e.eventDate = :date")
    List<Event> findByEventDate(@Param("date") LocalDate date);

    /**
     * Fetches all events for a specific user. JOIN FETCH prevents N+1 queries
     * when accessing event.getUser() in the same transaction.
     */
    @Query("SELECT e FROM Event e JOIN FETCH e.user u WHERE u = :user")
    List<Event> findByUser(@Param("user") User user);

    void deleteByUser(User user);
}