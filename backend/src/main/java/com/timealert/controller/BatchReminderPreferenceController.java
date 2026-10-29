package com.timealert.controller;

import com.timealert.model.User;
import com.timealert.repository.UserRepository;
import com.timealert.service.BatchReminderPreferenceService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/student/reminder-preferences")
public class BatchReminderPreferenceController {
    private final UserRepository users; private final BatchReminderPreferenceService preferences;
    public BatchReminderPreferenceController(UserRepository users,BatchReminderPreferenceService preferences){this.users=users;this.preferences=preferences;}
    private User current(Authentication auth){return auth==null?null:users.findByEmail(auth.getName()).orElse(null);}
    @GetMapping public ResponseEntity<?> get(Authentication auth){User user=current(auth);if(user==null)return ResponseEntity.status(401).body(Map.of("message","Unauthorized"));try{return ResponseEntity.ok(preferences.get(user));}catch(SecurityException e){return ResponseEntity.status(403).body(Map.of("message",e.getMessage()));}}
    @PutMapping public ResponseEntity<?> update(@RequestBody Map<String,Object> body,Authentication auth){User user=current(auth);if(user==null)return ResponseEntity.status(401).body(Map.of("message","Unauthorized"));try{return ResponseEntity.ok(preferences.update(user,body));}catch(SecurityException e){return ResponseEntity.status(403).body(Map.of("message",e.getMessage()));}catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}}
}
