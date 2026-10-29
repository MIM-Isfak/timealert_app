package com.timealert.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timealert.model.*;
import com.timealert.repository.EventRepository;
import com.timealert.repository.UserRepository;
import com.timealert.security.*;
import com.timealert.service.BatchService;
import com.timealert.service.OwnerBootstrapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = { AuthController.class, OwnerController.class, BatchController.class, StudentBatchController.class },
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
        properties = { "debug=false", "logging.level.root=WARN", "logging.level.org.springframework=WARN" })
@Import({ SecurityConfig.class, JwtFilter.class, JwtUtil.class, RateLimitFilter.class, PushRequestLimiter.class, GlobalExceptionHandler.class })
class BatchSecurityControllerTests {

    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired ObjectMapper mapper;

    @MockitoBean UserRepository userRepository;
    @MockitoBean EventRepository eventRepository;
    @MockitoBean PasswordEncoder passwordEncoder;
    @MockitoBean OwnerBootstrapService ownerBootstrapService;
    @MockitoBean BatchService batchService;

    User owner;
    User rep;
    User student;
    String ownerAuth;
    String repAuth;
    String studentAuth;

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry properties) {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String secret = HexFormat.of().formatHex(bytes);
        properties.add("jwt.secret", () -> secret);
        properties.add("spring.dotenv.enabled", () -> "false");
    }

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setId(1L);
        owner.setEmail("owner@example.com");
        owner.setName("App Owner");
        owner.setRole(Role.OWNER);

        rep = new User();
        rep.setId(2L);
        rep.setEmail("rep@example.com");
        rep.setName("Course Rep");
        rep.setRole(Role.REP);

        student = new User();
        student.setId(3L);
        student.setEmail("student@example.com");
        student.setName("Student");
        student.setRole(Role.STUDENT);

        ownerAuth = "Bearer " + jwt.generateToken(owner.getEmail());
        repAuth = "Bearer " + jwt.generateToken(rep.getEmail());
        studentAuth = "Bearer " + jwt.generateToken(student.getEmail());

        when(userRepository.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(userRepository.findByEmail(rep.getEmail())).thenReturn(Optional.of(rep));
        when(userRepository.findByEmail(student.getEmail())).thenReturn(Optional.of(student));
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(passwordEncoder.encode(any())).thenReturn("encoded-password");
    }

    @Test
    void registrationIgnoresClientSuppliedRoleAndForcesStudent() throws Exception {
        when(userRepository.existsByEmail("newstudent@example.com")).thenReturn(false);
        when(ownerBootstrapService.isConfiguredOwner("newstudent@example.com")).thenReturn(false);

        Map<String, String> body = Map.of(
                "email", "newstudent@example.com",
                "password", "secret123",
                "name", "Attacker Trying Owner",
                "role", "OWNER"
        );

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STUDENT"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals(Role.STUDENT, userCaptor.getValue().getRole());
    }

    @Test
    void registrationPromotesToOwnerIfConfiguredInEnvironment() throws Exception {
        when(userRepository.existsByEmail("envowner@example.com")).thenReturn(false);
        when(ownerBootstrapService.isConfiguredOwner("envowner@example.com")).thenReturn(true);

        Map<String, String> body = Map.of(
                "email", "envowner@example.com",
                "password", "secret123",
                "name", "Bootstrap Owner"
        );

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertEquals(Role.OWNER, userCaptor.getValue().getRole());
    }

    @Test
    void unauthenticatedCallsToProtectedEndpointsReturn401() throws Exception {
        mvc.perform(get("/api/owner/users")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/owner/users/3/role").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"REP\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Test\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/student/join").contentType(MediaType.APPLICATION_JSON).content("{\"invitationCode\":\"TEST\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void studentAndRepCannotAccessOwnerEndpoints() throws Exception {
        mvc.perform(get("/api/owner/users").header("Authorization", studentAuth))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/owner/users/3/role").header("Authorization", repAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"REP\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCanPromoteStudentToRepAndRevoke() throws Exception {
        when(userRepository.findById(3L)).thenReturn(Optional.of(student));

        // Promote to REP
        mvc.perform(put("/api/owner/users/3/role").header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"REP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("REP"));
        assertEquals(Role.REP, student.getRole());

        // Revoke back to STUDENT
        mvc.perform(put("/api/owner/users/3/role").header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STUDENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STUDENT"));
        assertEquals(Role.STUDENT, student.getRole());
    }

    @Test
    void ownerCannotModifyOwnRoleOrAnotherOwner() throws Exception {
        // Own role modification
        mvc.perform(put("/api/owner/users/1/role").header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STUDENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot modify your own role."));

        // Another owner modification
        User secondOwner = new User();
        secondOwner.setId(99L);
        secondOwner.setEmail("secondowner@example.com");
        secondOwner.setRole(Role.OWNER);
        when(userRepository.findById(99L)).thenReturn(Optional.of(secondOwner));

        mvc.perform(put("/api/owner/users/99/role").header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STUDENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot modify an OWNER account."));
    }

    @Test
    void repCanCreateBatchAndStudentCannot() throws Exception {
        Batch b = new Batch();
        b.setId(201L);
        b.setName("SE 2026");
        b.setActive(true);
        b.setInvitationCode("SEC12345");
        b.setRep(rep);
        b.setCreatedAt(LocalDateTime.now());

        when(batchService.createBatch(eq(rep), eq("SE 2026"), any(), any())).thenReturn(b);

        // Rep creates batch -> 201 Created
        mvc.perform(post("/api/batches").header("Authorization", repAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SE 2026\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invitationCode").value("SEC12345"));

        // Student tries to create batch -> 403 Forbidden
        mvc.perform(post("/api/batches").header("Authorization", studentAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SE 2026\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void studentCanSubmitJoinRequestAndRepCannot() throws Exception {
        Batch b = new Batch();
        b.setId(201L);
        b.setName("SE 2026");
        b.setRep(rep);

        BatchMembership m = new BatchMembership();
        m.setId(301L);
        m.setBatch(b);
        m.setStudent(student);
        m.setStatus(MembershipStatus.PENDING);
        m.setRequestedAt(LocalDateTime.now());

        when(batchService.submitJoinRequest(eq(student), eq("SEC12345"))).thenReturn(m);

        // Student submits join request -> 201 Created
        mvc.perform(post("/api/student/join").header("Authorization", studentAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invitationCode\":\"SEC12345\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.batchName").value("SE 2026"));

        // Rep tries to join as student -> 403 Forbidden
        mvc.perform(post("/api/student/join").header("Authorization", repAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invitationCode\":\"SEC12345\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void repCanApproveAndRejectJoinRequest() throws Exception {
        Batch b = new Batch();
        b.setId(201L);
        b.setName("SE 2026");
        b.setRep(rep);

        BatchMembership approved = new BatchMembership();
        approved.setId(301L);
        approved.setBatch(b);
        approved.setStudent(student);
        approved.setStatus(MembershipStatus.APPROVED);
        approved.setReviewedAt(LocalDateTime.now());

        when(batchService.approveRequest(eq(rep), eq(201L), eq(301L))).thenReturn(approved);

        mvc.perform(post("/api/batches/201/requests/301/approve").header("Authorization", repAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        BatchMembership rejected = new BatchMembership();
        rejected.setId(301L);
        rejected.setBatch(b);
        rejected.setStudent(student);
        rejected.setStatus(MembershipStatus.REJECTED);
        rejected.setReviewedAt(LocalDateTime.now());

        when(batchService.rejectRequest(eq(rep), eq(201L), eq(301L))).thenReturn(rejected);

        mvc.perform(post("/api/batches/201/requests/301/reject").header("Authorization", repAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void studentCanViewTheirMemberships() throws Exception {
        Batch b = new Batch();
        b.setId(201L);
        b.setName("SE 2026");
        b.setAcademicYear("Year 3");
        b.setSemester("Sem 1");
        b.setRep(rep);

        BatchMembership m = new BatchMembership();
        m.setId(301L);
        m.setBatch(b);
        m.setStudent(student);
        m.setStatus(MembershipStatus.APPROVED);

        when(batchService.getStudentMemberships(eq(student))).thenReturn(List.of(m));

        mvc.perform(get("/api/student/memberships").header("Authorization", studentAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batchName").value("SE 2026"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"));
    }
}
