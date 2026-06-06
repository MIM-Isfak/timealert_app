package com.timealert.service;

import com.timealert.model.Event;
import com.timealert.model.PushSubscription;
import com.timealert.repository.EventRepository;
import com.timealert.repository.PushSubscriptionRepository;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Service
public class NotificationService {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PushSubscriptionRepository pushSubscriptionRepository;

    @Value("${vapid.public.key}")
    private String publicKey;

    @Value("${vapid.private.key}")
    private String privateKey;

    @Value("${vapid.subject}")
    private String subject;

    @Scheduled(cron = "0 * * * * *")
    public void checkAndSendNotifications() {
        ZoneId colombo = ZoneId.of("Asia/Colombo");
        ZonedDateTime nowColombo = ZonedDateTime.now(colombo);
        LocalDate today = nowColombo.toLocalDate();
        LocalTime now = nowColombo.toLocalTime();

        List<Event> events = eventRepository.findAll();
        List<PushSubscription> subscriptions = pushSubscriptionRepository.findAll();

        for (Event event : events) {
            if (!event.getEventDate().equals(today)) continue;

            LocalTime startTime = event.getStartTime();
            long minutesDiff = java.time.Duration.between(now, startTime).toMinutes();

            // 30 min reminder
            if (minutesDiff >= 29 && minutesDiff <= 31 && !event.isNotified30()) {
                String msg = "⏰ " + event.getTitle() +
                    " starts in 30 minutes! 📍 " + event.getLocation();
                sendToAllSubscribers(subscriptions, msg);
                event.setNotified30(true);
                eventRepository.save(event);
            }

            // 20 min reminder
            if (minutesDiff >= 19 && minutesDiff <= 21 && !event.isNotified20()) {
                String msg = "⚠️ " + event.getTitle() +
                    " starts in 20 minutes! 📍 " + event.getLocation();
                sendToAllSubscribers(subscriptions, msg);
                event.setNotified20(true);
                eventRepository.save(event);
            }

            // 10 min reminder
            if (minutesDiff >= 9 && minutesDiff <= 11 && !event.isNotified10()) {
                String msg = "🚨 " + event.getTitle() +
                    " starts in 10 minutes! 📍 " + event.getLocation();
                sendToAllSubscribers(subscriptions, msg);
                event.setNotified10(true);
                eventRepository.save(event);
            }
        }
    }

    private void sendToAllSubscribers(List<PushSubscription> subscriptions, String message) {
        PushService pushService;
        try {
            pushService = new PushService(publicKey, privateKey, subject);
        } catch (Exception e) {
            System.out.println("PushService init error: " + e.getMessage());
            return;
        }

        for (PushSubscription sub : subscriptions) {
            try {
                Subscription subscription = new Subscription(
                    sub.getEndpoint(),
                    new Subscription.Keys(sub.getP256dh(), sub.getAuth())
                );
                pushService.send(new Notification(subscription, message));
            } catch (Exception e) {
                System.out.println("Notification error for " + sub.getEndpoint() + ": " + e.getMessage());
            }
        }
    }
}