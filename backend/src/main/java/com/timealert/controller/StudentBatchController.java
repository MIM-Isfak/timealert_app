package com.timealert.controller;

import com.timealert.model.*;
import com.timealert.repository.UserRepository;
import com.timealert.service.BatchService;
import com.timealert.service.OwnerBootstrapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/student")
public class StudentBatchController {

    @Autowired
    private BatchService batchService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OwnerBootstrapService ownerBootstrapService;

    private User getAuthenticatedUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            return null;
        }
        Optional<User> userOpt = userRepository.findByEmail(auth.getName());
        if (userOpt.isEmpty()) {
            return null;
        }
        User user = userOpt.get();
        ownerBootstrapService.syncOwnerRoleIfConfigured(user);
        return user;
    }

    private Map<String, Object> mapMembershipToResponse(BatchMembership m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("batchId", m.getBatch().getId());
        map.put("batchName", m.getBatch().getName());
        map.put("timezone", m.getBatch().getTimezone());
        map.put("academicYear", m.getBatch().getAcademicYear() != null ? m.getBatch().getAcademicYear() : "");
        map.put("semester", m.getBatch().getSemester() != null ? m.getBatch().getSemester() : "");
        map.put("repName", m.getBatch().getRep() != null && m.getBatch().getRep().getName() != null
                ? m.getBatch().getRep().getName() : "");
        map.put("repEmail", m.getBatch().getRep() != null ? m.getBatch().getRep().getEmail() : "");
        map.put("status", m.getStatus().name());
        map.put("requestedAt", m.getRequestedAt() != null ? m.getRequestedAt().toString() : "");
        map.put("reviewedAt", m.getReviewedAt() != null ? m.getReviewedAt().toString() : "");
        return map;
    }

    @PostMapping("/join")
    public ResponseEntity<?> joinBatch(@RequestBody Map<String, String> body, Authentication auth) {
        User user = getAuthenticatedUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        if (user.getRole() != Role.STUDENT) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only students can join batches."));
        }

        String invitationCode = body != null ? body.get("invitationCode") : null;
        if (invitationCode == null || invitationCode.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invitation code is required."));
        }

        try {
            BatchMembership membership = batchService.submitJoinRequest(user, invitationCode);
            Map<String, Object> resp = mapMembershipToResponse(membership);
            resp.put("message", "Join request submitted successfully. Awaiting representative approval.");
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/memberships")
    public ResponseEntity<?> getMemberships(Authentication auth) {
        User user = getAuthenticatedUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        if (user.getRole() != Role.STUDENT) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only students can view memberships."));
        }

        List<BatchMembership> memberships = batchService.getStudentMemberships(user);
        List<Map<String, Object>> response = memberships.stream()
                .map(this::mapMembershipToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }
}
