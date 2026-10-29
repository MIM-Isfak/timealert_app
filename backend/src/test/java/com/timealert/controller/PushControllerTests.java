package com.timealert.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.timealert.model.User;
import com.timealert.repository.UserRepository;
import com.timealert.security.*;
import com.timealert.service.PushBridgeService;
import com.timealert.service.BatchReminderSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HexFormat;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// MVC slice: no datasource, SMTP sender, scheduled jobs, or real push service.
@WebMvcTest(controllers = PushController.class, excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
        properties = { "debug=false", "logging.level.root=WARN", "logging.level.org.springframework=WARN" })
@Import({ SecurityConfig.class, JwtFilter.class, JwtUtil.class, RateLimitFilter.class, PushRequestLimiter.class })
class PushControllerTests {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @MockitoBean UserRepository users;
    @MockitoBean PushBridgeService bridge;
    @MockitoBean BatchReminderSyncService reminderSync;
    static final AtomicLong ids = new AtomicLong(100);
    static final String DEVICE = "11111111-1111-4111-8111-111111111111";
    User user;
    String auth;

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry properties) {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String secret = HexFormat.of().formatHex(bytes);
        properties.add("jwt.secret", () -> secret);
        properties.add("spring.dotenv.enabled", () -> "false");
    }
    @BeforeEach void account() {
        user = new User(); user.setId(ids.incrementAndGet()); user.setEmail("push-test@example.com");
        auth = "Bearer " + jwt.generateToken(user.getEmail());
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }
    @Test void publicKeyAndMutationsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/push/public-key")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/push/subscriptions").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/push/subscriptions/" + DEVICE + "/test").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(bridge);
    }
    @Test void registrationDerivesStableDatabaseIdFromJwtAccount() throws Exception {
        when(reminderSync.syncForUser(user.getId())).thenReturn(true);
        when(bridge.register(eq(user.getId()), any(JsonNode.class)))
                .thenReturn(new PushBridgeService.Result(200, Map.of("status", "registered", "deviceId", DEVICE)));
        mvc.perform(post("/api/push/subscriptions").header("Authorization", auth)
                .contentType("application/json").content("{\"endpoint\":\"https://fcm.googleapis.com/wp/fake\",\"keys\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deviceId").value(DEVICE));
        verify(bridge).register(eq(user.getId()), any(JsonNode.class));
    }
    @Test void clientCannotSupplyAnOwner() throws Exception {
        mvc.perform(post("/api/push/subscriptions").header("Authorization", auth)
                .contentType("application/json").content("{\"userId\":999,\"endpoint\":\"fake\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(bridge);
    }
    @Test void selectedDeviceUsesAuthenticatedIdAndAcceptanceDoesNotClaimDelivery() throws Exception {
        when(bridge.test(user.getId(), DEVICE)).thenReturn(new PushBridgeService.Result(202,
                Map.of("status", "accepted", "delivery", "unconfirmed")));
        mvc.perform(post("/api/push/subscriptions/" + DEVICE + "/test").header("Authorization", auth))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.delivery").value("unconfirmed"));
        verify(bridge).test(user.getId(), DEVICE);
    }
    @Test void demoAndUnknownAccountsCannotUsePush() throws Exception {
        user.setDemo(true);
        mvc.perform(get("/api/push/public-key").header("Authorization", auth)).andExpect(status().isForbidden());
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.empty());
        mvc.perform(delete("/api/push/subscriptions/" + DEVICE).header("Authorization", auth)).andExpect(status().isForbidden());
        verifyNoInteractions(bridge);
    }
    @Test void unavailableServiceReturnsRetryableResponse() throws Exception {
        when(bridge.publicKey()).thenReturn(PushBridgeService.failure(503, "setup_pending"));
        mvc.perform(get("/api/push/public-key").header("Authorization", auth))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "60"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("setup_pending"));
    }
    @Test void removalAndLookupPreserveOwnershipAndUnknownDeviceStatus() throws Exception {
        when(bridge.device(user.getId(), DEVICE, false)).thenReturn(PushBridgeService.failure(404, "not_registered"));
        when(bridge.device(user.getId(), DEVICE, true)).thenReturn(new PushBridgeService.Result(200, Map.of("status", "removed")));
        mvc.perform(get("/api/push/subscriptions/" + DEVICE).header("Authorization", auth)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/push/subscriptions/" + DEVICE).header("Authorization", auth)).andExpect(status().isOk());
        verify(bridge).device(user.getId(), DEVICE, true);
    }
    @Test void testRateLimitUsesUserIdentity() throws Exception {
        when(bridge.test(user.getId(), DEVICE)).thenReturn(new PushBridgeService.Result(202, Map.of("status", "accepted")));
        for (int i = 0; i < 3; i++) mvc.perform(post("/api/push/subscriptions/" + DEVICE + "/test").header("Authorization", auth))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/push/subscriptions/" + DEVICE + "/test").header("Authorization", auth)
                .header("X-Forwarded-For", "different"))
                .andExpect(status().isTooManyRequests());
        verify(bridge, times(3)).test(user.getId(), DEVICE);
    }
}
