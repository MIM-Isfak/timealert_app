package com.timealert.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor
@Entity @Table(name = "batch_event_acknowledgements", uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "student_id"}))
public class BatchEventAcknowledgement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "event_id", nullable = false) private BatchTimetableEvent event;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "student_id", nullable = false) private User student;
    @Column(nullable = false) private int version;
    @Column(nullable = false) private LocalDateTime acknowledgedAt = LocalDateTime.now();
}
