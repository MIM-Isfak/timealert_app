package com.timealert.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalTime;

@Data
@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String description;
    private LocalDate eventDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String location;

    // 3 notification flags
    private boolean notified30;  // 30 min before
    private boolean notified20;  // 20 min before
    private boolean notified10;  // 10 min before

    // Keep old one for compatibility
    private boolean notified;
}