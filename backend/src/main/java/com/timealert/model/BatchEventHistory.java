package com.timealert.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor
@Entity @Table(name = "batch_event_history")
public class BatchEventHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "event_id", nullable = false) private BatchTimetableEvent event;
    @Column(nullable = false) private int version;
    @Column(nullable = false) private String action;
    @Column(columnDefinition = "text", nullable = false) private String beforeState;
    @Column(columnDefinition = "text", nullable = false) private String afterState;
    @Column(columnDefinition = "text") private String reason;
    @Column(nullable = false) private LocalDateTime createdAt = LocalDateTime.now();
}
