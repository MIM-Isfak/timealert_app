package com.timealert.repository;

import com.timealert.model.Event;
import com.timealert.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Query("SELECT e FROM Event e JOIN FETCH e.user WHERE e.eventDate = :date")
    List<Event> findByEventDate(@Param("date") LocalDate date);

    List<Event> findByUser(User user);

    void deleteByUser(User user);
}