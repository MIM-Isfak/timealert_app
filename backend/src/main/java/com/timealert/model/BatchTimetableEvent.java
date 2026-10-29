package com.timealert.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "batch_timetable_events", uniqueConstraints = @UniqueConstraint(
        name = "uk_batch_event_occurrence", columnNames = {"batch_id", "event_date", "start_time", "title"}))
public class BatchTimetableEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;
    @Column(nullable = false, length = 180) private String title;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "event_date", nullable = false) private LocalDate eventDate;
    @Column(name = "start_time", nullable = false) private LocalTime startTime;
    @Column(name = "end_time", nullable = false) private LocalTime endTime;
    private String location;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private BatchEventType type = BatchEventType.OTHER;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private BatchEventStatus status = BatchEventStatus.DRAFT;
    @Column(nullable = false) private int version = 1;
    @Column(name = "series_id", nullable = false) private UUID seriesId = UUID.randomUUID();
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;
    @Column(columnDefinition = "text") private String changeReason;
    @Column(nullable = false, columnDefinition = "boolean not null default false") private boolean notificationSetupPending;
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
}
