package com.timealert.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.timealert.model.User;
import com.timealert.repository.UserRepository;
import com.timealert.security.PushRequestLimiter;
import com.timealert.service.PushBridgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/push")
public class PushController {
    private final UserRepository users;
    private final PushBridgeService bridge;
    private final PushRequestLimiter limiter;

    public PushController(UserRepository users, PushBridgeService bridge, PushRequestLimiter limiter) {
        this.users = users; this.bridge = bridge; this.limiter = limiter;
    }

    private User user(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) return null;
        return users.findByEmail(auth.getName()).filter(u -> !u.isDemo()).orElse(null);
    }
    private ResponseEntity<?> result(PushBridgeService.Result value) {
        var response = ResponseEntity.status(value.status()).header("Cache-Control", "no-store");
        if (value.status() == 429 || value.status() == 503) response.header("Retry-After", "60");
        return response.body(value.body());
    }
    private ResponseEntity<?> forbidden() { return result(PushBridgeService.failure(403, "account_required")); }
    private boolean validId(String id) { return id.matches("[0-9a-f-]{36}"); }

    @GetMapping("/public-key")
    public ResponseEntity<?> publicKey(Authentication auth) {
        User user = user(auth);
        if (user == null) return forbidden();
        if (!limiter.allow(user.getId(), false)) return result(PushBridgeService.failure(429, "rate_limited"));
        return result(bridge.publicKey());
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<?> register(Authentication auth, @RequestBody JsonNode subscription) {
        User user = user(auth);
        if (user == null) return forbidden();
        if (!subscription.isObject() || subscription.toString().length() > 4096 || subscription.has("userId") || subscription.has("ownerId")) {
            return result(PushBridgeService.failure(400, "invalid_subscription"));
        }
        if (!limiter.allow(user.getId(), false)) return result(PushBridgeService.failure(429, "rate_limited"));
        return result(bridge.register(user.getId(), subscription));
    }

    @GetMapping("/subscriptions/{id}")
    public ResponseEntity<?> device(Authentication auth, @PathVariable String id) { return deviceOperation(auth, id, false, false); }
    @DeleteMapping("/subscriptions/{id}")
    public ResponseEntity<?> remove(Authentication auth, @PathVariable String id) { return deviceOperation(auth, id, true, false); }
    @PostMapping("/subscriptions/{id}/test")
    public ResponseEntity<?> test(Authentication auth, @PathVariable String id) { return deviceOperation(auth, id, false, true); }

    private ResponseEntity<?> deviceOperation(Authentication auth, String id, boolean remove, boolean test) {
        User user = user(auth);
        if (user == null) return forbidden();
        if (!validId(id)) return result(PushBridgeService.failure(400, "invalid_subscription"));
        if (!limiter.allow(user.getId(), test)) return result(PushBridgeService.failure(429, "rate_limited"));
        return result(test ? bridge.test(user.getId(), id) : bridge.device(user.getId(), id, remove));
    }
}
