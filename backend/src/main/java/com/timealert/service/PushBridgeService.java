package com.timealert.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Service
public class PushBridgeService {
    public record Result(int status, Map<String, Object> body) {}
    private final String baseUrl;
    private final String secret;
    private final RestClient client;
    private final ObjectMapper mapper;

    public PushBridgeService(@Value("${push.service-url:}") String baseUrl,
                             @Value("${push.service-secret:}") String secret, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.secret = secret;
        this.mapper = mapper;
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(7));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    private boolean configured() {
        if (secret.length() < 32 || secret.startsWith("REPLACE_") || baseUrl.isBlank()) return false;
        try {
            URI uri = URI.create(baseUrl);
            return uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null &&
                    ("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) &&
                            Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())));
        } catch (IllegalArgumentException e) { return false; }
    }

    public Result publicKey() { return exchange("/internal/public-key", null, false); }
    public Result register(Long userId, JsonNode subscription) {
        return exchange("/internal/subscriptions", Map.of("userId", userId.toString(), "subscription", subscription), false);
    }
    public Result device(Long userId, String deviceId, boolean remove) {
        return exchange("/internal/device", Map.of("userId", userId.toString(), "deviceId", deviceId), remove);
    }
    public Result test(Long userId, String deviceId) {
        return exchange("/internal/test", Map.of("userId", userId.toString(), "deviceId", deviceId), false);
    }
    public Result reminderReplace(Long batchId, Long eventId, int version, Map<String,Object> event, java.util.List<Map<String,Object>> recipients) {
        return exchange("/internal/reminders/replace", Map.of("batchId",batchId.toString(),"eventId",eventId.toString(),"version",version,"event",event,"recipients",recipients), false);
    }
    public Result reminderCancel(Long batchId, Long eventId, int version, Map<String,Object> event, java.util.List<String> recipients, String reason) {
        return exchange("/internal/reminders/cancel", Map.of("batchId",batchId.toString(),"eventId",eventId.toString(),"version",version,"event",event,"recipients",recipients,"reason",reason), false);
    }
    public Result reminderSync(Long userId, Long batchId, java.util.List<Map<String,Object>> jobs) {
        return reminderSync(userId,batchId,jobs,false);
    }
    public Result reminderSync(Long userId, Long batchId, java.util.List<Map<String,Object>> jobs, boolean revoke) {
        return exchange("/internal/reminders/sync", Map.of("userId",userId.toString(),"batchId",batchId.toString(),"jobs",jobs,"revoke",revoke), false);
    }
    public Result reminderSummary(Long batchId) { return exchange("/internal/reminders/summary?batchId="+batchId,null,false); }

    private Result exchange(String path, Object body, boolean delete) {
        if (!configured()) return failure(503, "setup_pending");
        try {
            var spec = client.method(body == null ? org.springframework.http.HttpMethod.GET :
                    delete ? org.springframework.http.HttpMethod.DELETE : org.springframework.http.HttpMethod.POST)
                    .uri(baseUrl + path).header("Authorization", "Bearer " + secret)
                    .header("Content-Type", "application/json");
            if (body != null) spec.body(body);
            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 503) {
                    byte[] bytes = response.getBody().readNBytes(8193);
                    if (bytes.length <= 8192 && "setup_pending".equals(mapper.readTree(bytes).path("status").asText())) {
                        return failure(503, "setup_pending");
                    }
                    return failure(503, "unavailable");
                }
                if (status >= 200 && status < 300) {
                    byte[] bytes = response.getBody().readNBytes(8193);
                    if (bytes.length > 8192) return failure(503, "unavailable");
                    JsonNode data = mapper.readTree(bytes);
                    String state = data.path("status").asText();
                    if (path.startsWith("/internal/reminders/") && Set.of("scheduled","replaced","cancelled","synced","ready").contains(state)) {
                        Map<String,Object> safe = new java.util.LinkedHashMap<>(); safe.put("status",state);
                        if(data.path("count").canConvertToInt()) safe.put("count",data.path("count").asInt());
                        if(data.path("counts").isObject()) safe.put("counts",mapper.convertValue(data.path("counts"),Map.class));
                        if(data.path("nextAlarmAt").canConvertToLong()) safe.put("nextAlarmAt",data.path("nextAlarmAt").asLong());
                        return new Result(status, safe);
                    }
                    if (path.endsWith("public-key") && state.equals("ready") &&
                            data.path("publicKey").asText().matches("B[A-Za-z0-9_-]{86}")) {
                        return new Result(200, Map.of("status", "ready", "publicKey", data.get("publicKey").asText()));
                    }
                    if (state.equals("registered") && data.path("deviceId").asText().matches("[0-9a-f-]{36}")) {
                        return new Result(200, Map.of("status", state, "deviceId", data.get("deviceId").asText()));
                    }
                    if (state.equals("removed")) return new Result(200, Map.of("status", state));
                    if (state.equals("accepted") && status == 202) return new Result(202, Map.of(
                            "status", state, "delivery", "unconfirmed", "message",
                            "Accepted by the push service. Device delivery is not confirmed."));
                    return failure(503, "unavailable");
                }
                return switch (status) {
                    case 400, 413, 415 -> failure(400, "invalid_subscription");
                    case 404 -> failure(404, "not_registered");
                    case 409 -> failure(409, "device_conflict_or_limit");
                    case 410 -> failure(410, "expired");
                    case 429 -> failure(429, "rate_limited");
                    case 502 -> failure(502, "push_service_error");
                    default -> failure(503, "unavailable");
                };
            });
        } catch (Exception e) {
            // Do not log URLs, endpoints, credentials, or provider response bodies.
            return failure(503, "unavailable");
        }
    }

    public static Result failure(int status, String code) {
        return new Result(status, Map.of("status", code, "message", switch (code) {
            case "setup_pending" -> "Notifications are awaiting server setup.";
            case "account_required" -> "Sign in with a personal account to use device notifications.";
            case "not_registered", "expired" -> "This device is not registered. Enable notifications again.";
            case "rate_limited" -> "Too many notification requests. Try again in a minute.";
            case "device_conflict_or_limit" -> "Device belongs to another account or the device limit was reached.";
            case "invalid_subscription" -> "Unsupported or invalid push subscription.";
            case "push_service_error" -> "The push service rejected the test notification.";
            default -> "Notification service unavailable. A test may have been accepted; delivery is unconfirmed.";
        }));
    }
}
