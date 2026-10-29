package com.timealert.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor
@Entity @Table(name = "batch_reminder_preferences")
public class BatchReminderPreference {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false, unique = true) private User user;
    @Column(name = "five_minutes", nullable = false) private boolean fiveMinutes = true;
    @Column(name = "ten_minutes", nullable = false) private boolean tenMinutes = true;
    @Column(name = "thirty_minutes", nullable = false) private boolean thirtyMinutes = true;
    @Column(name = "exam_day_before", nullable = false) private boolean examDayBefore = true;
    @Column(name = "notification_setup_pending", nullable = false) private boolean notificationSetupPending;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt = LocalDateTime.now();
}
