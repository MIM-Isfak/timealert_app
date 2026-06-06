package com.timealert.controller;

import com.timealert.model.PushSubscription;
import com.timealert.repository.PushSubscriptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(origins = "${allowed.origins:http://localhost:3000}")
public class NotificationController {

    @Autowired
    private PushSubscriptionRepository pushSubscriptionRepository;

    @Value("${vapid.public.key}")
    private String publicKey;

    @GetMapping("/public-key")
    public ResponseEntity<?> getPublicKey() {
        return ResponseEntity.ok(Map.of("publicKey", publicKey));
    }

    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(@RequestBody Map<String, String> body) {
        String endpoint = body.get("endpoint");

        // Duplicate check — already இருந்தா skip பண்ணு
        Optional<PushSubscription> existing = 
            pushSubscriptionRepository.findByEndpoint(endpoint);
        
        if (existing.isPresent()) {
            return ResponseEntity.ok("Already subscribed!");
        }

        PushSubscription subscription = new PushSubscription();
        subscription.setEndpoint(endpoint);
        subscription.setP256dh(body.get("p256dh"));
        subscription.setAuth(body.get("auth"));
        pushSubscriptionRepository.save(subscription);
        return ResponseEntity.ok("Subscribed successfully!");
    }
}