package com.timealert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TimeAlert application entry point.
 *
 * Timezone note: the JVM default timezone is intentionally NOT overridden here.
 * Per-connection DB timezone is handled by spring.jpa.properties.hibernate.jdbc.time_zone=Asia/Colombo
 * in application.properties, and NotificationService reads ZonedDateTime with ZoneId.of("Asia/Colombo")
 * explicitly. Overriding TimeZone.setDefault() here would mask deployment-environment TZ
 * settings and could break UTC-hosted deployments silently.
 */
@SpringBootApplication
@EnableScheduling
public class TimealertApplication {

    public static void main(String[] args) {
        SpringApplication.run(TimealertApplication.class, args);
    }
}