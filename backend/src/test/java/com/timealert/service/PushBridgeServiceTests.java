package com.timealert.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class PushBridgeServiceTests {
    HttpServer server;
    String base;
    final String secret = UUID.randomUUID().toString() + UUID.randomUUID();
    final ObjectMapper mapper = new ObjectMapper();
    final AtomicReference<String> requestBody = new AtomicReference<>();
    final AtomicReference<String> authorization = new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }
    void reply(int status, String body) {
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }
    @Test void unconfiguredBridgeStaysPendingWithoutNetwork() {
        assertEquals("setup_pending", new PushBridgeService("", "", mapper).publicKey().body().get("status"));
        assertEquals(503, new PushBridgeService("http://example.com", secret, mapper).publicKey().status());
    }
    @Test void forwardsOnlyServerIdentityAndKeepsCredentialsOutOfResponse() throws Exception {
        reply(202, "{\"status\":\"accepted\",\"debug\":\"must-not-escape\"}");
        var response = new PushBridgeService(base, secret, mapper).test(42L, "11111111-1111-4111-8111-111111111111");
        assertEquals(202, response.status());
        assertEquals("unconfirmed", response.body().get("delivery"));
        assertTrue(("Bearer " + secret).equals(authorization.get()));
        assertEquals("42", mapper.readTree(requestBody.get()).get("userId").asText());
        assertFalse(response.body().containsKey("debug"));
    }
    @Test void upstreamAuthenticationFailureDoesNotLogOutBrowserOrLeakBody() {
        reply(401, "private server error");
        var response = new PushBridgeService(base, secret, mapper).publicKey();
        assertEquals(503, response.status());
        assertFalse(response.body().toString().contains("private server error"));
    }
    @Test void expiredDeviceIsClearlyReported() {
        reply(410, "expired");
        assertEquals(410, new PushBridgeService(base, secret, mapper).test(42L, UUID.randomUUID().toString()).status());
    }
    @Test void workerSetupPendingIsDistinctFromServiceFailure() {
        reply(503, "{\"status\":\"setup_pending\"}");
        var response = new PushBridgeService(base, secret, mapper).publicKey();
        assertEquals(503, response.status());
        assertEquals("setup_pending", response.body().get("status"));
    }
    @Test void unresponsiveWorkerHasABoundedTimeout() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                assertEquals(503, new PushBridgeService(base, secret, mapper).publicKey().status());
            });
        } finally { release.countDown(); }
    }
    @Test void invalidJsonResponseAndUnavailableServiceAreSafeFailures() {
        reply(200, "not-json");
        assertEquals(503, new PushBridgeService(base, secret, mapper).publicKey().status());
        server.stop(0);
        assertEquals(503, new PushBridgeService(base, secret, mapper).publicKey().status());
    }
}
