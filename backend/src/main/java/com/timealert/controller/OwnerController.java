package com.timealert.controller;

import com.timealert.model.Role;
import com.timealert.model.User;
import com.timealert.repository.UserRepository;
import com.timealert.service.OwnerBootstrapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/owner")
public class OwnerController {

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

    @GetMapping("/users")
    public ResponseEntity<?> listUsers(Authentication auth) {
        User currentUser = getAuthenticatedUser(auth);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }
        if (currentUser.getRole() != Role.OWNER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Owner role required."));
        }

        List<Map<String, Object>> users = userRepository.findAll().stream()
                .filter(u -> !u.isDemo())
                .map(u -> Map.<String, Object>of(
                        "id", u.getId(),
                        "email", u.getEmail(),
                        "name", u.getName() != null ? u.getName() : "",
                        "role", u.getRole().name()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(users);
    }

    @PutMapping("/users/{userId}/role")
    public ResponseEntity<?> updateUserRole(@PathVariable Long userId,
                                            @RequestBody Map<String, String> body,
                                            Authentication auth) {
        User currentUser = getAuthenticatedUser(auth);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized"));
        }
        if (currentUser.getRole() != Role.OWNER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Owner role required."));
        }

        String targetRoleStr = body != null ? body.get("role") : null;
        if (targetRoleStr == null || targetRoleStr.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Role is required."));
        }

        Role targetRole;
        try {
            targetRole = Role.valueOf(targetRoleStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid role specified."));
        }

        if (targetRole != Role.REP && targetRole != Role.STUDENT) {
            return ResponseEntity.badRequest().body(Map.of("message", "Owner can only assign REP or STUDENT role."));
        }

        if (userId.equals(currentUser.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Cannot modify your own role."));
        }

        Optional<User> targetUserOpt = userRepository.findById(userId);
        if (targetUserOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found."));
        }

        User targetUser = targetUserOpt.get();

        if (targetUser.getRole() == Role.OWNER) {
            return ResponseEntity.badRequest().body(Map.of("message", "Cannot modify an OWNER account."));
        }

        targetUser.setRole(targetRole);
        userRepository.save(targetUser);

        return ResponseEntity.ok(Map.of(
                "id", targetUser.getId(),
                "email", targetUser.getEmail(),
                "name", targetUser.getName() != null ? targetUser.getName() : "",
                "role", targetUser.getRole().name(),
                "message", targetRole == Role.REP ? "User promoted to REP." : "User REP access revoked."
        ));
    }
}
