package com.timealert.controller;

import com.timealert.model.*;
import com.timealert.repository.UserRepository;
import com.timealert.service.BatchService;
import com.timealert.service.OwnerBootstrapService;
import com.timealert.service.BatchReminderSyncService;
import com.timealert.service.BatchTimetableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class BatchController {

    @Autowired
    private BatchService batchService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OwnerBootstrapService ownerBootstrapService;
    @Autowired private BatchReminderSyncService reminderSyncService;
    @Autowired private BatchTimetableService timetableService;

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

    private Map<String, Object> mapBatchToResponse(Batch b) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", b.getId());
        map.put("name", b.getName());
        map.put("academicYear", b.getAcademicYear() != null ? b.getAcademicYear() : "");
        map.put("semester", b.getSemester() != null ? b.getSemester() : "");
        map.put("timezone", b.getTimezone() != null ? b.getTimezone() : "Asia/Colombo");
        map.put("active", b.isActive());
        map.put("invitationCode", b.getInvitationCode());
        map.put("createdAt", b.getCreatedAt() != null ? b.getCreatedAt().toString() : "");
        map.put("repName", b.getRep() != null && b.getRep().getName() != null ? b.getRep().getName() : "");
        map.put("repEmail", b.getRep() != null ? b.getRep().getEmail() : "");
        map.put("approvedCount", batchService.getApprovedMemberCount(b.getId()));
        map.put("pendingCount", batchService.getPendingRequestCount(b.getId()));
        return map;
    }

    private Map<String, Object> mapMembershipToResponse(BatchMembership m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("batchId", m.getBatch().getId());
        map.put("batchName", m.getBatch().getName());
        map.put("studentId", m.getStudent().getId());
        map.put("studentName", m.getStudent().getName() != null ? m.getStudent().getName() : "");
        map.put("studentEmail", m.getStudent().getEmail());
        map.put("status", m.getStatus().name());
        map.put("requestedAt", m.getRequestedAt() != null ? m.getRequestedAt().toString() : "");
        map.put("reviewedAt", m.getReviewedAt() != null ? m.getReviewedAt().toString() : "");
        map.put("notificationSetupPending",m.isNotificationSetupPending());
        return map;
    }

    @PostMapping("/batches")
    public ResponseEntity<?> createBatch(@RequestBody Map<String, String> body, Authentication auth) {
        User user = getAuthenticatedUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        if (user.getRole() != Role.REP) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Representative role required."));
        }

        String name = body != null ? body.get("name") : null;
        String academicYear = body != null ? body.get("academicYear") : null;
        String semester = body != null ? body.get("semester") : null;

        if (name == null || name.trim().length() < 2) {
            return ResponseEntity.badRequest().body(Map.of("message", "Batch name must be at least 2 characters."));
        }

        Batch batch = batchService.createBatch(user, name, academicYear, semester);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapBatchToResponse(batch));
    }

    @GetMapping("/rep/batches")
    public ResponseEntity<?> getRepBatches(Authentication auth) {
        User user = getAuthenticatedUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        if (user.getRole() != Role.REP) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Representative role required."));
        }

        List<Batch> batches = batchService.getBatchesForRep(user);
        List<Map<String, Object>> response = batches.stream()
                .map(this::mapBatchToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/batches/{id}")
    public ResponseEntity<?> getBatch(@PathVariable Long id, Authentication auth) {
        User user = getAuthenticatedUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }

        try {
            Batch batch = batchService.getBatchForUser(user, id);
            return ResponseEntity.ok(mapBatchToResponse(batch));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/batches/{id}")
    public ResponseEntity<?> updateBatch(@PathVariable Long id,
                                         @RequestBody Map<String, Object> body,
                                         Authentication auth) {
        User user = getAuthenticatedUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        if (user.getRole() != Role.REP) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Representative role required."));
        }

        String name = body != null && body.containsKey("name") ? String.valueOf(body.get("name")) : null;
        String academicYear = body != null && body.containsKey("academicYear") ? String.valueOf(body.get("academicYear")) : null;
        String semester = body != null && body.containsKey("semester") ? String.valueOf(body.get("semester")) : null;
        Boolean active = body != null && body.containsKey("active") ? (Boolean) body.get("active") : null;
        String timezone = body != null && body.containsKey("timezone") ? String.valueOf(body.get("timezone")) : null;

        try {
            Batch updated = batchService.updateBatch(user, id, name, academicYear, semester, active, timezone);
            Map<String,Object> response=mapBatchToResponse(updated);
            if(timezone!=null) response.put("notificationSetupPending",!reminderSyncService.resyncBatch(id));
            return ResponseEntity.ok(response);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/batches/{id}/timetable/notifications/resync")
    public ResponseEntity<?> resyncTimetableNotifications(@PathVariable Long id, Authentication auth) {
        User user=getAuthenticatedUser(auth);
        if(user==null)return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message","Unauthorized"));
        if(user.getRole()!=Role.REP)return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message","Representative role required."));
        try { timetableService.assertOwner(user,id); boolean ready=reminderSyncService.resyncBatch(id); return ResponseEntity.ok(Map.of("notificationSetupPending",!ready,"notificationSyncStatus",ready?"READY":"PENDING")); }
        catch(NoSuchElementException e){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message",e.getMessage()));}
        catch(SecurityException e){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message",e.getMessage()));}
    }

    @GetMapping("/batches/{id}/timetable/notifications/status")
    public ResponseEntity<?> timetableNotificationStatus(@PathVariable Long id,Authentication auth) {
        User user=getAuthenticatedUser(auth);
        if(user==null)return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message","Unauthorized"));
        if(user.getRole()!=Role.REP)return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message","Representative role required."));
        try { timetableService.assertOwner(user,id); return ResponseEntity.ok(reminderSyncService.summary(id)); }
        catch(NoSuchElementException e){return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message",e.getMessage()));}
        catch(SecurityException e){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message",e.getMessage()));}
    }

    @GetMapping("/batches/{id}/requests")
    public ResponseEntity<?> getBatchRequests(@PathVariable Long id, Authentication auth) {
        User user = getAuthenticatedUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        if (user.getRole() != Role.REP) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Representative role required."));
        }

        try {
            List<BatchMembership> requests = batchService.getBatchRequests(user, id);
            List<Map<String, Object>> response = requests.stream()
                    .map(this::mapMembershipToResponse)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(response);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/batches/{batchId}/requests/{requestId}/approve")
    public ResponseEntity<?> approveRequest(@PathVariable Long batchId,
                                            @PathVariable Long requestId,
                                            Authentication auth) {
        User user = getAuthenticatedUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        if (user.getRole() != Role.REP) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Representative role required."));
        }

        try {
            BatchMembership approved = batchService.approveRequest(user, batchId, requestId);
            return ResponseEntity.ok(mapMembershipToResponse(approved));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/batches/{batchId}/requests/{requestId}/reject")
    public ResponseEntity<?> rejectRequest(@PathVariable Long batchId,
                                           @PathVariable Long requestId,
                                           Authentication auth) {
        User user = getAuthenticatedUser(auth);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        if (user.getRole() != Role.REP) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Representative role required."));
        }

        try {
            BatchMembership rejected = batchService.rejectRequest(user, batchId, requestId);
            return ResponseEntity.ok(mapMembershipToResponse(rejected));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
