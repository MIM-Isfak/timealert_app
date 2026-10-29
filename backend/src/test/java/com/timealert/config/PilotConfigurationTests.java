package com.timealert.config;

import com.timealert.repository.EventRepository;
import com.timealert.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.*;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mail.javamail.JavaMailSender;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PilotConfigurationTests {
    private static final String ORIGIN="https://timealert-pilot.pages.dev";
    private static final String JDBC="jdbc:postgresql://example.neon.tech/pilot?sslmode=require";
    @Test void renderPortSslHealthAndSafeErrorPropertiesResolveFromEnvironment() throws Exception {
        StandardEnvironment env=new StandardEnvironment();
        env.getPropertySources().addFirst(new ResourcePropertySource("classpath:application.properties"));
        env.getPropertySources().addFirst(new ResourcePropertySource("classpath:application-pilot.properties"));
        env.getPropertySources().addFirst(new MapPropertySource("pilot-test",Map.of(
                "PORT","12345","DB_URL",JDBC,"DB_USERNAME","test-user","DB_PASSWORD","test-only",
                "JWT_SECRET",UUID.randomUUID().toString(),"ALLOWED_ORIGINS",ORIGIN)));
        assertEquals("12345",env.getProperty("server.port"));assertEquals("0.0.0.0",env.getProperty("server.address"));
        assertEquals(JDBC,env.getProperty("spring.datasource.url"));assertEquals("health",env.getProperty("management.endpoints.web.exposure.include"));
        assertEquals("never",env.getProperty("management.endpoint.health.show-details"));assertEquals("never",env.getProperty("server.error.include-stacktrace"));
        assertEquals("false",env.getProperty("legacy.email-reminders.enabled"));assertEquals("false",env.getProperty("management.health.mail.enabled"));
        assertEquals("3",env.getProperty("spring.datasource.hikari.maximum-pool-size"));
        assertDoesNotThrow(()->new PilotConfiguration(env.getProperty("allowed.origins"),env.getProperty("spring.datasource.url")));
    }
    @Test void pilotRejectsMissingWildcardPreviewListsAndNonOriginCorsValues() {
        for(String origin:List.of("","*","https://*.pages.dev","http://localhost:3000",ORIGIN+",https://preview.pages.dev",ORIGIN+"/",ORIGIN+"/path",ORIGIN+"?x=1","https://user:pass@timealert.pages.dev"))
            assertThrows(IllegalArgumentException.class,()->new PilotConfiguration(origin,JDBC));
        assertDoesNotThrow(()->new PilotConfiguration(ORIGIN,JDBC));
    }
    @Test void pilotRejectsPlaintextOrCredentialBearingDatabaseUrlsWithoutEchoingThem() {
        for(String value:List.of("postgres://private:password@example.neon.tech/db", "jdbc:postgresql://example.neon.tech/db",JDBC+"&password=private")) {
            var error=assertThrows(IllegalArgumentException.class,()->new PilotConfiguration(ORIGIN,value));
            assertFalse(error.getMessage().contains("private"));assertFalse(error.getMessage().contains("example.neon"));
        }
    }
    @Test void disabledLegacySchedulingNeedsNeitherSmtpNorRepositoryAndRegistersNoService() {
        new ApplicationContextRunner().withUserConfiguration(NotificationService.class)
                .withPropertyValues("legacy.email-reminders.enabled=false")
                .run(context->{assertNull(context.getStartupFailure());assertEquals(0,context.getBeansOfType(NotificationService.class).size());assertEquals(0,context.getBeansOfType(JavaMailSender.class).size());});
    }
    @Test void explicitLegacyOptInStillRegistersPersonalEmailService() {
        new ApplicationContextRunner().withUserConfiguration(NotificationService.class)
                .withBean(EventRepository.class,()->mock(EventRepository.class))
                .withBean(JavaMailSender.class,()->mock(JavaMailSender.class))
                .withPropertyValues("legacy.email-reminders.enabled=true")
                .run(context->assertEquals(1,context.getBeansOfType(NotificationService.class).size()));
    }
    @Test void renderBlueprintSelectsFreeDockerWithoutDatabaseOrAutomaticDeployment() throws Exception {
        Map<?,?> blueprint=new Yaml().load(Files.readString(Path.of("..","render.yaml")));
        assertFalse(blueprint.containsKey("databases"));
        Map<?,?> service=(Map<?,?>)((List<?>)blueprint.get("services")).get(0);
        assertEquals("free",service.get("plan"));assertEquals("docker",service.get("runtime"));assertEquals("off",service.get("autoDeployTrigger"));
        assertEquals("feature/timealert-student-alerts",service.get("branch"));assertEquals("/actuator/health",service.get("healthCheckPath"));
        List<?> vars=(List<?>)service.get("envVars");
        for(String key:List.of("DB_URL","DB_USERNAME","DB_PASSWORD","JWT_SECRET","ALLOWED_ORIGINS","PUSH_SERVICE_SECRET")) {
            Map<?,?> entry=(Map<?,?>)vars.stream().filter(v->key.equals(((Map<?,?>)v).get("key"))).findFirst().orElseThrow();
            assertEquals(false,entry.get("sync"));assertFalse(entry.containsKey("value"));
        }
        assertEquals("**",Files.readAllLines(Path.of(".dockerignore")).get(0));
    }
}
