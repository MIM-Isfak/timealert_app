package com.timealert.controller;

import com.timealert.model.*;
import com.timealert.repository.UserRepository;
import com.timealert.security.*;
import com.timealert.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers=BatchTimetableController.class,excludeAutoConfiguration=UserDetailsServiceAutoConfiguration.class,
    properties={"debug=false","logging.level.root=WARN","allowed.origins=https://timealert-pilot.pages.dev"})
@Import({SecurityConfig.class,JwtFilter.class,JwtUtil.class,RateLimitFilter.class,PushRequestLimiter.class,GlobalExceptionHandler.class})
class CalendarControllerTests {
    @Autowired MockMvc mvc; @Autowired JwtUtil jwt;
    @MockitoBean UserRepository users; @MockitoBean BatchTimetableService timetable;
    @MockitoBean FileParserService parser; @MockitoBean BatchCalendarService calendar;
    User student; String auth;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("jwt.secret",()->UUID.randomUUID().toString()+UUID.randomUUID());registry.add("spring.dotenv.enabled",()->"false");}
    @BeforeEach void setup(){student=new User();student.setId(3L);student.setRole(Role.STUDENT);student.setEmail("calendar@example.test");when(users.findByEmail(student.getEmail())).thenReturn(Optional.of(student));auth="Bearer "+jwt.generateToken(student.getEmail());}
    @Test void anonymousDownloadIsRejected() throws Exception {mvc.perform(get("/api/student/batches/10/timetable.ics")).andExpect(status().isUnauthorized());verifyNoInteractions(calendar);}
    @Test void authorizedDownloadIsPrivateAttachment() throws Exception {
        byte[] content="BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);when(calendar.export(student,10L)).thenReturn(content);
        mvc.perform(get("/api/student/batches/10/timetable.ics").header("Authorization",auth)).andExpect(status().isOk())
            .andExpect(header().string("Content-Type","text/calendar; charset=UTF-8")).andExpect(header().string("Cache-Control","private, no-store"))
            .andExpect(header().string("Content-Disposition","attachment; filename=\"timealert-batch-10.ics\"")).andExpect(content().bytes(content));
    }
    @Test void unapprovedDownloadIsForbidden() throws Exception {when(calendar.export(student,10L)).thenThrow(new SecurityException("Approved batch membership required."));mvc.perform(get("/api/student/batches/10/timetable.ics").header("Authorization",auth)).andExpect(status().isForbidden());}
    @Test void productionCorsAcceptsOnlyFinalOrigin() throws Exception {
        mvc.perform(options("/api/student/batches/10/timetable.ics").header("Origin","https://timealert-pilot.pages.dev").header("Access-Control-Request-Method","GET").header("Access-Control-Request-Headers","Authorization"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","https://timealert-pilot.pages.dev"));
        for(String origin:List.of("http://localhost:3000","https://preview.timealert-pilot.pages.dev","https://other.pages.dev"))
            mvc.perform(options("/api/student/batches/10/timetable.ics").header("Origin",origin).header("Access-Control-Request-Method","GET")).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
