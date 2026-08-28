package com.timealert.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.Optional;

@Component
public class JwtUtil {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtil.class);

    private static final String KNOWN_WEAK_FALLBACK = "timealert-secret-key-must-be-at-least-32-chars";
    private static final int MIN_SECRET_LENGTH = 32;

    @Value("${jwt.secret:timealert-secret-key-must-be-at-least-32-chars}")
    private String secret;

    private static final long EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000L; // 7 days

    /**
     * Validates the JWT secret on startup.
     * SECURITY: throws IllegalStateException if the known-weak default is still in use,
     * preventing the application from serving requests with a breakable signing key.
     */
    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                "SECURITY: jwt.secret is too short (minimum " + MIN_SECRET_LENGTH + " characters). " +
                "Set a strong JWT_SECRET environment variable before starting the application.");
        }
        if (secret.equals(KNOWN_WEAK_FALLBACK) || secret.startsWith("timealert-secret-key")) {
            throw new IllegalStateException(
                "SECURITY CRITICAL: jwt.secret is the known default fallback value. " +
                "This is INSECURE. Generate a strong secret with: openssl rand -hex 32 " +
                "and set it as JWT_SECRET in your environment before starting.");
        }
        logger.info("JWT secret loaded successfully ({} characters).", secret.length());
    }

    private Key getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(String email) {
        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Parses the token once and returns the email subject if valid,
     * or empty if the token is expired, malformed, or otherwise invalid.
     * This replaces the previous two-parse pattern (validateToken + extractEmail)
     * with a single HMAC verification per request.
     */
    public Optional<String> extractEmailIfValid(String token) {
        try {
            String email = Jwts.parserBuilder()
                    .setSigningKey(getKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
                    .getSubject();
            return Optional.ofNullable(email);
        } catch (ExpiredJwtException e) {
            logger.warn("JWT token expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            logger.warn("JWT token unsupported: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            logger.warn("JWT token malformed: {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("JWT token validation failed: {}", e.getMessage());
        }
        return Optional.empty();
    }
}