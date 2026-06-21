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

@Component
public class JwtUtil {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtil.class);

    private static final String KNOWN_WEAK_FALLBACK = "timealert-secret-key-must-be-at-least-32-chars";
    private static final int MIN_SECRET_LENGTH = 32;

    @Value("${jwt.secret:timealert-secret-key-must-be-at-least-32-chars}")
    private String secret;

    private static final long EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000L; // 7 days

    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            logger.error("SECURITY: jwt.secret is too short (minimum {} characters). " +
                         "Set a strong JWT_SECRET environment variable before deploying to production.", MIN_SECRET_LENGTH);
        } else if (secret.equals(KNOWN_WEAK_FALLBACK) || secret.startsWith("timealert-secret-key")) {
            logger.error("SECURITY CRITICAL: jwt.secret is using the known default fallback value. " +
                         "This is INSECURE for production. Set a strong random JWT_SECRET in your environment variables.");
        } else {
            logger.info("JWT secret loaded successfully ({} characters).", secret.length());
        }
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

    public String extractEmail(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getKey())
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(getKey()).build().parseClaimsJws(token);
            return true;
        } catch (ExpiredJwtException e) {
            logger.warn("JWT token expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            logger.warn("JWT token unsupported: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            logger.warn("JWT token malformed: {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("JWT token validation failed: {}", e.getMessage());
        }
        return false;
    }
}