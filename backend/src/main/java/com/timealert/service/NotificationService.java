package com.timealert.service;

import com.timealert.model.Event;
import com.timealert.model.User;
import com.timealert.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Transactional
    @Scheduled(cron = "0 * * * * *")
    public void checkAndSendNotifications() {
        ZoneId colombo = ZoneId.of("Asia/Colombo");
        ZonedDateTime nowColombo = ZonedDateTime.now(colombo);
        LocalDate today = nowColombo.toLocalDate();
        LocalTime now = nowColombo.toLocalTime();

        List<Event> events = eventRepository.findByEventDate(today);

        for (Event event : events) {
            User user = event.getUser();
            if (user == null || user.getEmail() == null || user.getEmail().trim().isEmpty()) {
                continue;
            }

            LocalTime startTime = event.getStartTime();
            long minutesDiff = java.time.Duration.between(now, startTime).toMinutes();

            boolean needsSave = false;

            // 30-minute reminder
            if (minutesDiff >= 29 && minutesDiff <= 31 && !event.isNotified30()) {
                String subject = "⏰ Reminder: " + event.getTitle() + " starts in 30 minutes";
                String body = buildEmailBody(user, event, "30 minutes");
                sendEmail(user.getEmail(), subject, body);
                event.setNotified30(true);
                needsSave = true;
            }

            // 20-minute reminder
            if (minutesDiff >= 19 && minutesDiff <= 21 && !event.isNotified20()) {
                String subject = "⚠️ Reminder: " + event.getTitle() + " starts in 20 minutes";
                String body = buildEmailBody(user, event, "20 minutes");
                sendEmail(user.getEmail(), subject, body);
                event.setNotified20(true);
                needsSave = true;
            }

            // 10-minute reminder
            if (minutesDiff >= 9 && minutesDiff <= 11 && !event.isNotified10()) {
                String subject = "🚨 Reminder: " + event.getTitle() + " starts in 10 minutes";
                String body = buildEmailBody(user, event, "10 minutes");
                sendEmail(user.getEmail(), subject, body);
                event.setNotified10(true);
                needsSave = true;
            }

            // Persist all notification-flag changes in a single save per event
            if (needsSave) {
                eventRepository.save(event);
            }
        }
    }

    private String buildEmailBody(User user, Event event, String timeUntil) {
        return String.format(
            "Hello %s,%n%nThis is a reminder that the event \"%s\" is starting in %s!%n%n" +
            "📍 Location: %s%nTime: %s - %s%nDescription: %s%n%nBest regards,%nTimeAlert Team",
            user.getName(), event.getTitle(), timeUntil,
            event.getLocation(), event.getStartTime(), event.getEndTime(), event.getDescription()
        );
    }

    private void sendEmail(String to, String subject, String body) {
        if (fromEmail == null || fromEmail.trim().isEmpty()) {
            // SMTP not configured — log the notification details so they aren't silently lost
            logger.info("SMTP not configured. Notification skipped (would have sent to {}):\n  Subject: {}", to, subject);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            logger.info("Email notification sent successfully to: {}", to);
        } catch (Exception e) {
            // Log failure clearly; email is not retried automatically.
            // If retry is needed, integrate Spring Retry or a persistent job queue.
            logger.error("Failed to send email notification to {}: {}", to, e.getMessage(), e);
        }
    }
}