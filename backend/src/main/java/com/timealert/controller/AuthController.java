package com.timealert.controller;

import com.timealert.model.Event;
import com.timealert.model.User;
import com.timealert.repository.EventRepository;
import com.timealert.repository.UserRepository;
import com.timealert.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/auth")
// @CrossOrigin REMOVED — CORS is handled centrally in SecurityConfig.corsConfigurationSource()
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$");

    private static final String DEMO_EMAIL = "demo@timealert.app";

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String email = body != null ? body.get("email") : null;
        String password = body != null ? body.get("password") : null;
        String name = body != null ? body.get("name") : null;

        if (email == null || email.trim().isEmpty() ||
            password == null || password.trim().isEmpty() ||
            name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Name, email and password required!"));
        }

        email = email.trim().toLowerCase();
        name = name.trim();

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid email format!"));
        }

        if (password.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("message", "Password must be at least 6 characters!"));
        }

        if (name.length() < 2) {
            return ResponseEntity.badRequest().body(Map.of("message", "Name must be at least 2 characters!"));
        }

        if (userRepository.existsByEmail(email)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Email already registered!"));
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setName(name);
        userRepository.save(user);

        String token = jwtUtil.generateToken(email);
        return ResponseEntity.ok(Map.of(
            "token", token,
            "email", email,
            "name", name
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String email = body != null ? body.get("email") : null;
        String password = body != null ? body.get("password") : null;

        if (email == null || email.trim().isEmpty() ||
            password == null || password.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Email and password required!"));
        }

        email = email.trim().toLowerCase();

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid email format!"));
        }

        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid email or password!"));
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(password, user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid email or password!"));
        }

        String token = jwtUtil.generateToken(email);
        return ResponseEntity.ok(Map.of(
            "token", token,
            "email", email,
            "name", user.getName()
        ));
    }

    /**
     * Guest demo login for public/LinkedIn visitors.
     * Reuses a single shared demo account and reseeds it with fresh sample
     * events on every call, so the timetable always looks current. Marked
     * isDemo=true so NotificationService never sends real emails for it —
     * visitors can explore the full UI without triggering real SMTP sends
     * or needing to give up a real email address.
     */
    @Transactional
    @PostMapping("/demo")
    public ResponseEntity<?> demoLogin() {
        User demoUser = userRepository.findByEmail(DEMO_EMAIL).orElseGet(() -> {
            User u = new User();
            u.setEmail(DEMO_EMAIL);
            u.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            u.setName("Guest");
            u.setDemo(true);
            return userRepository.save(u);
        });

        // Safety check: ensure the found user is genuinely a demo account.
        // Prevents event deletion if a non-demo user somehow has the demo email address.
        if (!demoUser.isDemo()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Demo account configuration error. Please contact the administrator."));
        }

        // Wipe and reseed sample events so every visitor sees a clean, realistic timetable.
        eventRepository.deleteByUser(demoUser);

        LocalDate today = LocalDate.now();
        eventRepository.save(buildDemoEvent(demoUser, "Database Systems Lecture",
            "Room 204, Faculty of Applied Science", today, LocalTime.of(9, 0), LocalTime.of(10, 30),
            "Weekly lecture covering normalization and indexing."));
        eventRepository.save(buildDemoEvent(demoUser, "AI/ML Study Group",
            "Library Discussion Room 3", today, LocalTime.of(14, 0), LocalTime.of(15, 30),
            "Group review session on supervised learning algorithms."));
        eventRepository.save(buildDemoEvent(demoUser, "Project Submission Deadline",
            "Online Portal", today.plusDays(1), LocalTime.of(23, 55), LocalTime.of(23, 59),
            "Final submission for the Edge AI coursework."));
        eventRepository.save(buildDemoEvent(demoUser, "Career Mentorship Call",
            "Google Meet", today.plusDays(2), LocalTime.of(18, 0), LocalTime.of(18, 45),
            "1:1 mentorship session on ML career roadmap."));

        String token = jwtUtil.generateToken(DEMO_EMAIL);
        return ResponseEntity.ok(Map.of(
            "token", token,
            "email", DEMO_EMAIL,
            "name", "Guest",
            "isDemo", true
        ));
    }

    private Event buildDemoEvent(User user, String title, String location, LocalDate date,
                                  LocalTime start, LocalTime end, String description) {
        Event event = new Event();
        event.setUser(user);
        event.setTitle(title);
        event.setLocation(location);
        event.setEventDate(date);
        event.setStartTime(start);
        event.setEndTime(end);
        event.setDescription(description);
        return event;
    }
}