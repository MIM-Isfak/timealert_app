package com.timealert.controller;

import com.timealert.model.*;
import com.timealert.repository.UserRepository;
import com.timealert.service.BatchTimetableService;
import com.timealert.service.FileParserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api")
public class BatchTimetableController {
    @Autowired private BatchTimetableService service;
    @Autowired private FileParserService parser;
    @Autowired private UserRepository users;
    private User user(Authentication auth) { return auth == null ? null : users.findByEmail(auth.getName()).orElse(null); }
    private Map<String,Object> dto(BatchTimetableEvent e, boolean rep, User viewer) {
        Map<String,Object> m=new LinkedHashMap<>(); m.put("id",e.getId()); m.put("batchId",e.getBatch().getId()); m.put("title",e.getTitle()); m.put("description",e.getDescription()); m.put("eventDate",e.getEventDate()); m.put("startTime",e.getStartTime()); m.put("endTime",e.getEndTime()); m.put("location",e.getLocation()); m.put("type",e.getType()); m.put("status",e.getStatus()); m.put("version",e.getVersion()); m.put("seriesId",e.getSeriesId()); m.put("createdAt",e.getCreatedAt()); m.put("updatedAt",e.getUpdatedAt()); m.put("changeReason",e.getChangeReason());
        if(rep) { m.put("acknowledgementCount",service.ackCount(viewer,e.getBatch().getId(),e.getId())); m.put("history",service.history(viewer,e.getBatch().getId(),e.getId()).stream().map(this::historyDto).toList()); }
        else { m.put("acknowledged",e.getStatus()==BatchEventStatus.PUBLISHED && service.hasAck(viewer,e.getBatch().getId(),e.getId())); m.put("history",service.historyForStudent(viewer,e.getBatch().getId(),e.getId()).stream().map(this::historyDto).toList()); }
        return m;
    }
    private Map<String,Object> historyDto(BatchEventHistory h) { return Map.of("version",h.getVersion(),"action",h.getAction(),"before",h.getBeforeState(),"after",h.getAfterState(),"reason",h.getReason()==null?"":h.getReason(),"createdAt",h.getCreatedAt().toString()); }
    @ExceptionHandler({SecurityException.class}) public ResponseEntity<?> forbidden(Exception e) { return ResponseEntity.status(403).body(Map.of("message",e.getMessage())); }
    @ExceptionHandler({IllegalArgumentException.class, java.time.DateTimeException.class}) public ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage())); }
    @ExceptionHandler({NoSuchElementException.class}) public ResponseEntity<?> missing(Exception e) { return ResponseEntity.status(404).body(Map.of("message",e.getMessage())); }
    @ExceptionHandler({IllegalStateException.class}) public ResponseEntity<?> conflict(Exception e) { return ResponseEntity.badRequest().body(Map.of("message",e.getMessage())); }

    @GetMapping("/batches/{batchId}/timetable")
    public List<Map<String,Object>> repList(@PathVariable Long batchId, Authentication auth) { User u=user(auth); return service.forRep(u,batchId).stream().map(e->dto(e,true,u)).toList(); }
    @PostMapping("/batches/{batchId}/timetable")
    public ResponseEntity<?> create(@PathVariable Long batchId,@RequestBody Map<String,Object> body,Authentication auth) { User u=user(auth); List<BatchTimetableEvent> result=service.create(u,batchId,body); return ResponseEntity.status(201).body(result.stream().map(e->dto(e,true,u)).toList()); }
    @PostMapping(value="/batches/{batchId}/timetable/import-preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> preview(@PathVariable Long batchId,@RequestParam("file") MultipartFile file,Authentication auth) throws Exception {
        User u=user(auth); service.assertOwner(u,batchId); if(file==null||file.isEmpty()) throw new IllegalArgumentException("Choose a non-empty timetable file.");
        FileParserService.ParseResult parsed=parser.parseForBatch(file); List<BatchTimetableEvent> saved=service.preview(u,batchId,parsed.getEvents());
        List<String> skipped=new ArrayList<>(parsed.getSkippedReasons()); int duplicates=parsed.getEvents().size()-saved.size(); for(int i=0;i<duplicates;i++) skipped.add("Duplicate occurrence: subject, date, and start time already exist in this batch.");
        Map<String,Object> result=new LinkedHashMap<>(); result.put("preview",saved.stream().map(e->dto(e,true,u)).toList()); result.put("skippedReasons",skipped); result.put("duplicateCount",duplicates); return ResponseEntity.ok(result);
    }
    @PostMapping("/batches/{batchId}/timetable/publish")
    public List<Map<String,Object>> publish(@PathVariable Long batchId,Authentication auth) { User u=user(auth); return service.publish(u,batchId).stream().map(e->dto(e,true,u)).toList(); }
    @PutMapping("/batches/{batchId}/timetable/{eventId}")
    public Map<String,Object> edit(@PathVariable Long batchId,@PathVariable Long eventId,@RequestBody Map<String,Object> body,Authentication auth) { User u=user(auth); return dto(service.edit(u,batchId,eventId,body),true,u); }
    @PostMapping("/batches/{batchId}/timetable/{eventId}/cancel")
    public Map<String,Object> cancel(@PathVariable Long batchId,@PathVariable Long eventId,@RequestBody Map<String,String> body,Authentication auth) { User u=user(auth); return dto(service.cancel(u,batchId,eventId,body==null?null:body.get("reason")),true,u); }
    @GetMapping("/student/batches/{batchId}/timetable")
    public List<Map<String,Object>> studentList(@PathVariable Long batchId,Authentication auth) { User u=user(auth); return service.forStudent(u,batchId).stream().map(e->dto(e,false,u)).toList(); }
    @PostMapping("/student/batches/{batchId}/timetable/{eventId}/acknowledge")
    public Map<String,Object> acknowledge(@PathVariable Long batchId,@PathVariable Long eventId,Authentication auth) { User u=user(auth); service.acknowledge(u,batchId,eventId); return Map.of("acknowledged",true); }
}
