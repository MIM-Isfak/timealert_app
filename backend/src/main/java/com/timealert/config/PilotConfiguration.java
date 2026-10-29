package com.timealert.config;

import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Fail closed if the pilot is missing its exact frontend origin or SSL database URL. */
@Configuration
@Profile("pilot")
public class PilotConfiguration {
    public PilotConfiguration(@Value("${allowed.origins:}") String origin,
                              @Value("${spring.datasource.url:}") String databaseUrl) {
        try {
            URI uri = URI.create(origin);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null ||
                    uri.getUserInfo() != null || uri.getPort() != -1 ||
                    !uri.getRawPath().isEmpty() || uri.getQuery() != null || uri.getFragment() != null ||
                    origin.contains(",") || origin.contains("*") ||
                    uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1")) {
                throw new IllegalArgumentException();
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Pilot ALLOWED_ORIGINS must be the single final HTTPS Pages origin, without a trailing slash.");
        }
        try {
            if (!databaseUrl.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            URI uri = URI.create(databaseUrl.substring(5));
            var query = Arrays.asList((uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&"));
            if (uri.getHost() == null || uri.getUserInfo() != null ||
                    query.stream().noneMatch(s -> s.matches("sslmode=(require|verify-ca|verify-full)")) ||
                    query.stream().anyMatch(s -> s.toLowerCase(java.util.Locale.ROOT).matches("(user|password)=.*"))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Pilot DB_URL must be a PostgreSQL JDBC URL with SSL enabled; supply credentials separately.");
        }
    }
}
