package com.timealert.controller;

import com.timealert.model.Event;
import com.timealert.model.User;
import com.timealert.repository.EventRepository;
import com.timealert.repository.UserRepository;
import com.timealert.service.FileParserService;
import com.timealert.service.FileParserService.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@RestController
@RequestMapping("/api")
// @CrossOrigin REMOVED — CORS is handled centrally in SecurityConfig.corsConfigurationSource()
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "text/csv",
            "application/vnd.ms-excel",                                          // .xls
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
            "application/pdf",
            "text/plain"   // some systems report CSV as text/plain
    );

    @Autowired
    private FileParserService fileParserService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private UserRepository userRepository;

    private User getUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new NoSuchElementException("Authenticated user not found in database."));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file,
                                        Authentication auth) {
        // Validate file is not empty
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No file provided or file is empty."));
        }

        // Validate file size explicitly (belt-and-suspenders over multipart limit)
        if (file.getSize() > 10 * 1024 * 1024) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("message", "File is too large. Maximum allowed size is 10 MB."));
        }

        // Validate MIME type
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(java.util.Locale.ROOT))) {
            logger.warn("Upload rejected: unsupported content type '{}' for file '{}'",
                        contentType, file.getOriginalFilename());
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(Map.of("message", "Unsupported file type. Please upload a CSV, Excel (.xlsx/.xls), or PDF file."));
        }

        try {
            User user = getUser(auth);
            ParseResult result = fileParserService.parseFile(file, user);

            StringBuilder msg = new StringBuilder();
            msg.append("✅ ").append(result.getSavedCount()).append(" event(s) saved successfully!");
            if (!result.getSkippedReasons().isEmpty()) {
                msg.append(" ⚠️ ").append(result.getSkippedReasons().size())
                   .append(" row(s) were skipped due to invalid data.");
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", msg.toString());
            response.put("savedCount", result.getSavedCount());
            response.put("skippedCount", result.getSkippedReasons().size());
            if (!result.getSkippedReasons().isEmpty()) {
                response.put("skippedReasons", result.getSkippedReasons());
            }
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            logger.error("File upload failed for user '{}': {}", auth.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Upload failed. Please try again or contact support."));
        }
    }

    @GetMapping("/events")
    public ResponseEntity<?> getAllEvents(Authentication auth) {
        try {
            User user = getUser(auth);
            List<Event> events = eventRepository.findByUser(user);
            return ResponseEntity.ok(events);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "User not found."));
        } catch (Exception e) {
            logger.error("Failed to fetch events for user '{}': {}", auth.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Could not retrieve events. Please try again later."));
        }
    }

    @DeleteMapping("/events/clear")
    @Transactional
    public ResponseEntity<?> clearAllEvents(Authentication auth) {
        try {
            User user = getUser(auth);
            eventRepository.deleteByUser(user);
            return ResponseEntity.ok(Map.of("message", "All events cleared successfully!"));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "User not found."));
        } catch (Exception e) {
            logger.error("Failed to clear events for user '{}': {}", auth.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Could not clear events. Please try again later."));
        }
    }

    @DeleteMapping("/events/{id}")
    public ResponseEntity<?> deleteEvent(@PathVariable Long id, Authentication auth) {
        try {
            User user = getUser(auth);
            Optional<Event> eventOpt = eventRepository.findById(id);

            if (eventOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "Event not found."));
            }

            Event event = eventOpt.get();
            if (event.getUser() == null || !event.getUser().getId().equals(user.getId())) {
                logger.warn("User '{}' attempted to delete event {} owned by a different user.", auth.getName(), id);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "You do not have permission to delete this event."));
            }

            eventRepository.delete(event);
            return ResponseEntity.ok(Map.of("message", "Event deleted successfully!"));

        } catch (Exception e) {
            logger.error("Failed to delete event {} for user '{}': {}", id, auth.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Could not delete event. Please try again later."));
        }
    }
}