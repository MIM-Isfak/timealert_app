package com.timealert.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timealert.model.*;
import com.timealert.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class BatchTimetableService {
    @Autowired private BatchRepository batches;
    @Autowired private BatchMembershipRepository memberships;
    @Autowired private BatchTimetableEventRepository events;
    @Autowired private BatchEventHistoryRepository history;
    @Autowired private BatchEventAcknowledgementRepository acknowledgements;
    @Autowired private BatchReminderSyncService reminderSyncService;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private Batch batch(Long id) { return batches.findById(id).orElseThrow(() -> new NoSuchElementException("Batch not found.")); }
    private void owner(User rep, Batch batch) {
        if (rep == null || rep.getRole() != Role.REP || !batch.getRep().getId().equals(rep.getId())) throw new SecurityException("Only the owning representative can manage this timetable.");
    }
    @Transactional(readOnly=true) public void assertOwner(User rep, Long batchId) { owner(rep,batch(batchId)); }
    private void student(User student, Batch batch) {
        if (student == null || student.getRole() != Role.STUDENT || !memberships.existsByBatchIdAndStudentIdAndStatus(batch.getId(), student.getId(), MembershipStatus.APPROVED)) throw new SecurityException("Approved batch membership required.");
    }
    private BatchTimetableEvent event(Long batchId, Long eventId) {
        BatchTimetableEvent event = events.findById(eventId).orElseThrow(() -> new NoSuchElementException("Event not found."));
        if (!event.getBatch().getId().equals(batchId)) throw new SecurityException("Event does not belong to this batch.");
        return event;
    }
    private BatchEventType type(Object value) { try { return BatchEventType.valueOf(value == null ? "OTHER" : value.toString().toUpperCase(Locale.ROOT)); } catch (Exception ex) { throw new IllegalArgumentException("Type must be CLASS, EXAM, DEADLINE, or OTHER."); } }
    private void validate(String title, LocalDate date, LocalTime start, LocalTime end) {
        if (title == null || title.isBlank() || title.length() > 180) throw new IllegalArgumentException("Subject/title is required (maximum 180 characters).");
        if (date == null || start == null || end == null || !end.isAfter(start)) throw new IllegalArgumentException("Provide a date and valid time range; end time must be after start time.");
    }
    private Map<String,Object> state(BatchTimetableEvent e) {
        Map<String,Object> m = new LinkedHashMap<>(); m.put("title", e.getTitle()); m.put("description", e.getDescription()); m.put("eventDate", e.getEventDate()); m.put("startTime", e.getStartTime()); m.put("endTime", e.getEndTime()); m.put("location", e.getLocation()); m.put("type", e.getType()); m.put("status", e.getStatus()); m.put("version", e.getVersion()); m.put("changeReason", e.getChangeReason()); return m;
    }
    private String json(BatchTimetableEvent e) { try { return mapper.writeValueAsString(state(e)); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private void record(BatchTimetableEvent e, String action, String before, String reason) {
        BatchEventHistory h = new BatchEventHistory(); h.setEvent(e); h.setVersion(e.getVersion()); h.setAction(action); h.setBeforeState(before); h.setAfterState(json(e)); h.setReason(reason); history.save(h);
    }
    private BatchTimetableEvent create(Batch batch, String title, String description, LocalDate date, LocalTime start, LocalTime end, String location, BatchEventType type, UUID series) {
        validate(title,date,start,end);
        if (events.existsByBatchIdAndEventDateAndStartTimeAndTitle(batch.getId(), date, start, title)) throw new IllegalArgumentException("Duplicate timetable occurrence.");
        BatchTimetableEvent e = new BatchTimetableEvent(); e.setBatch(batch); e.setTitle(title.trim()); e.setDescription(description); e.setEventDate(date); e.setStartTime(start); e.setEndTime(end); e.setLocation(location); e.setType(type); e.setSeriesId(series); return events.save(e);
    }
    @Transactional public List<BatchTimetableEvent> create(User rep, Long batchId, Map<String,Object> body) {
        Batch b=batch(batchId); owner(rep,b);
        String title=(String)body.get("title"); LocalDate date=LocalDate.parse(String.valueOf(body.get("eventDate"))); LocalTime start=LocalTime.parse(String.valueOf(body.get("startTime"))); LocalTime end=LocalTime.parse(String.valueOf(body.get("endTime")));
        String repeatStart=(String)body.get("repeatStart"), repeatEnd=(String)body.get("repeatEnd"); BatchEventType type=type(body.get("type"));
        UUID series=UUID.randomUUID(); List<BatchTimetableEvent> made=new ArrayList<>();
        if (repeatStart == null || repeatStart.isBlank()) return List.of(create(b,title,(String)body.get("description"),date,start,end,(String)body.get("location"),type,series));
        LocalDate first=LocalDate.parse(repeatStart), last=LocalDate.parse(repeatEnd);
        if (last.isBefore(first) || java.time.temporal.ChronoUnit.DAYS.between(first,last) > 366) throw new IllegalArgumentException("Weekly repeat end date must be on or after start and within one year.");
        for (LocalDate d=first; !d.isAfter(last); d=d.plusWeeks(1)) made.add(create(b,title,(String)body.get("description"),d,start,end,(String)body.get("location"),type,series));
        return made;
    }
    @Transactional public List<BatchTimetableEvent> preview(User rep, Long batchId, List<com.timealert.model.Event> parsed) {
        Batch b=batch(batchId); owner(rep,b); UUID series=UUID.randomUUID(); List<BatchTimetableEvent> out=new ArrayList<>();
        for (Event p:parsed) try { out.add(create(b,p.getTitle(),p.getDescription(),p.getEventDate(),p.getStartTime(),p.getEndTime(),p.getLocation(),BatchEventType.OTHER,series)); } catch (IllegalArgumentException ignored) { }
        return out;
    }
    @Transactional public List<BatchTimetableEvent> publish(User rep, Long batchId) {
        Batch b=batch(batchId); owner(rep,b); List<BatchTimetableEvent> out=events.findByBatchIdOrderByEventDateAscStartTimeAsc(batchId);
        out.stream().filter(e -> e.getStatus()==BatchEventStatus.DRAFT).forEach(e -> { e.setStatus(BatchEventStatus.PUBLISHED); if(reminderSyncService!=null)reminderSyncService.syncPublishedEvent(e); }); return out;
    }
    @Transactional public BatchTimetableEvent edit(User rep, Long batchId, Long id, Map<String,Object> body) {
        Batch b=batch(batchId); owner(rep,b); BatchTimetableEvent e=event(batchId,id);
        if(e.getStatus()==BatchEventStatus.CANCELLED) throw new IllegalStateException("Cancelled events cannot be edited.");
        String before=json(e); String title=(String)body.getOrDefault("title",e.getTitle()); LocalDate date=LocalDate.parse(String.valueOf(body.getOrDefault("eventDate",e.getEventDate()))); LocalTime start=LocalTime.parse(String.valueOf(body.getOrDefault("startTime",e.getStartTime()))); LocalTime end=LocalTime.parse(String.valueOf(body.getOrDefault("endTime",e.getEndTime()))); validate(title,date,start,end);
        if(events.existsByBatchIdAndEventDateAndStartTimeAndTitle(batchId,date,start,title) && !(date.equals(e.getEventDate())&&start.equals(e.getStartTime())&&title.equals(e.getTitle()))) throw new IllegalArgumentException("Duplicate timetable occurrence.");
        e.setTitle(title); e.setDescription((String)body.getOrDefault("description",e.getDescription())); e.setEventDate(date); e.setStartTime(start); e.setEndTime(end); e.setLocation((String)body.getOrDefault("location",e.getLocation())); if(body.containsKey("type"))e.setType(type(body.get("type"))); e.setVersion(e.getVersion()+1); e.setChangeReason((String)body.get("reason"));
        BatchTimetableEvent saved=events.save(e); if(saved.getStatus()==BatchEventStatus.PUBLISHED){record(saved,"UPDATED",before,saved.getChangeReason()); if(reminderSyncService!=null)reminderSyncService.syncPublishedEvent(saved);} return saved;
    }
    @Transactional public BatchTimetableEvent cancel(User rep, Long batchId, Long id, String reason) {
        Batch b=batch(batchId); owner(rep,b); if(reason==null||reason.isBlank()) throw new IllegalArgumentException("A cancellation reason is required."); if(reason.length()>1000) throw new IllegalArgumentException("Cancellation reason is too long."); BatchTimetableEvent e=event(batchId,id); if(e.getStatus()==BatchEventStatus.CANCELLED) return e;
        boolean wasPublished=e.getStatus()==BatchEventStatus.PUBLISHED; String before=json(e); e.setVersion(e.getVersion()+1); e.setStatus(BatchEventStatus.CANCELLED); e.setChangeReason(reason); BatchTimetableEvent saved=events.save(e); if(wasPublished){record(saved,"CANCELLED",before,reason); if(reminderSyncService!=null)reminderSyncService.syncCancelledEvent(saved);} return saved;
    }
    @Transactional public boolean acknowledge(User student, Long batchId, Long id) {
        Batch b=batch(batchId); student(student,b); BatchTimetableEvent e=event(batchId,id); if(e.getStatus()!=BatchEventStatus.PUBLISHED) throw new IllegalStateException("Only published events can be acknowledged.");
        BatchEventAcknowledgement a=acknowledgements.findByEventIdAndStudentId(id,student.getId()).orElseGet(BatchEventAcknowledgement::new); a.setEvent(e); a.setStudent(student); a.setVersion(e.getVersion()); a.setAcknowledgedAt(LocalDateTime.now()); acknowledgements.save(a); return true;
    }
    @Transactional(readOnly=true) public List<BatchTimetableEvent> forRep(User rep, Long batchId) { Batch b=batch(batchId); owner(rep,b); return events.findByBatchIdOrderByEventDateAscStartTimeAsc(batchId); }
    @Transactional(readOnly=true) public List<BatchTimetableEvent> forStudent(User student, Long batchId) { return forStudent(student,batchId,false); }
    @Transactional(readOnly=true) public List<BatchTimetableEvent> forStudent(User student, Long batchId, boolean week) {
        Batch b=batch(batchId); student(student,b);
        LocalDate today=LocalDate.now(ZoneId.of(b.getTimezone()));
        LocalDate from=week ? today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : today;
        return events.findByBatchIdOrderByEventDateAscStartTimeAsc(batchId).stream().filter(e -> !e.getEventDate().isBefore(from) && e.getStatus()!=BatchEventStatus.DRAFT).toList();
    }
    @Transactional(readOnly=true) public List<BatchEventHistory> history(User rep,Long batchId,Long id) { Batch b=batch(batchId); owner(rep,b); return history.findByEventIdOrderByVersionDesc(event(batchId,id).getId()); }
    @Transactional(readOnly=true) public List<BatchEventHistory> historyForStudent(User student,Long batchId,Long id) { Batch b=batch(batchId); student(student,b); return history.findByEventIdOrderByVersionDesc(event(batchId,id).getId()); }
    @Transactional(readOnly=true) public long ackCount(User rep,Long batchId,Long id) { Batch b=batch(batchId); owner(rep,b); BatchTimetableEvent e=event(batchId,id); return acknowledgements.countByEventIdAndVersion(id,e.getVersion()); }
    @Transactional(readOnly=true) public boolean hasAck(User student,Long batchId,Long id) { Batch b=batch(batchId); student(student,b); BatchTimetableEvent e=event(batchId,id); return acknowledgements.existsByEventIdAndStudentIdAndVersion(id,student.getId(),e.getVersion()); }
}
