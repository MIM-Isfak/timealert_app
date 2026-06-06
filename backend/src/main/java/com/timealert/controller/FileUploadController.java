package com.timealert.controller;

import com.timealert.model.Event;
import com.timealert.repository.EventRepository;
import com.timealert.service.FileParserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "${allowed.origins:http://localhost:3000}")
public class FileUploadController {

    @Autowired
    private FileParserService fileParserService;

    @Autowired
    private EventRepository eventRepository;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            List<Event> events = fileParserService.parseFile(file);
            return ResponseEntity.ok("Success! " + events.size() + " events saved!");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/events")
    public ResponseEntity<?> getAllEvents() {
        return ResponseEntity.ok(eventRepository.findAll());
    }

    @DeleteMapping("/events/clear")
    public ResponseEntity<?> clearAllEvents() {
        eventRepository.deleteAll();
        return ResponseEntity.ok("All events cleared!");
    }

    @DeleteMapping("/events/{id}")
    public ResponseEntity<?> deleteEvent(@PathVariable Long id) {
        eventRepository.deleteById(id);
        return ResponseEntity.ok("Event deleted!");
    }
}