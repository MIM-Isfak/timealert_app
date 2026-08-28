package com.timealert.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Represents a scheduled event owned by a user.
 *
 * Note on Lombok: @Data is intentionally NOT used here because it generates
 * equals/hashCode including the lazy @ManyToOne User proxy, which causes
 * LazyInitializationException outside a transaction. We use explicit
 * @EqualsAndHashCode(onlyExplicitlyIncluded=true) on the id field instead.
 */
@Getter
@Setter
@ToString(exclude = "user")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@NoArgsConstructor
@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    private String title;
    private String description;
    private LocalDate eventDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String location;

    // Notification-flag fields are internal scheduler state — excluded from the
    // public API surface so clients don't receive noisy boolean fields they don't use.
    @JsonIgnore
    private boolean notified30;
    @JsonIgnore
    private boolean notified20;
    @JsonIgnore
    private boolean notified10;
    // Legacy single-notification flag (kept for DB compatibility; unused by scheduler).
    @JsonIgnore
    private boolean notified;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JsonIgnore
    private User user;
}