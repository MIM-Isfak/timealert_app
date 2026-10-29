package com.timealert.service;

import com.timealert.model.*;
import com.timealert.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class BatchReminderSyncService {
    private final PushBridgeService bridge;
    private final BatchMembershipRepository memberships;
    private final BatchReminderPreferenceRepository preferences;
    private final BatchTimetableEventRepository events;
    private final BatchRepository batches;

    public BatchReminderSyncService(PushBridgeService bridge, BatchMembershipRepository memberships,
            BatchReminderPreferenceRepository preferences, BatchTimetableEventRepository events, BatchRepository batches) {
        this.bridge=bridge; this.memberships=memberships; this.preferences=preferences; this.events=events; this.batches=batches;
    }
    private List<Integer> offsets(BatchReminderPreference p, BatchEventType type) {
        List<Integer> result=new ArrayList<>(); if(p.isFiveMinutes())result.add(5); if(p.isTenMinutes())result.add(10); if(p.isThirtyMinutes())result.add(30); if(type==BatchEventType.EXAM&&p.isExamDayBefore())result.add(1440); return result;
    }
    private Map<String,Object> eventData(BatchTimetableEvent e) {
        ZoneId zone=ZoneId.of(e.getBatch().getTimezone()==null?"Asia/Colombo":e.getBatch().getTimezone());
        long starts=LocalDateTime.of(e.getEventDate(),e.getStartTime()).atZone(zone).toInstant().toEpochMilli();
        Map<String,Object> data=new LinkedHashMap<>(); data.put("title",e.getTitle()); data.put("type",e.getType().name()); data.put("startAt",starts);
        data.put("timeLabel",e.getEventDate()+" "+e.getStartTime()+" ("+zone.getId()+")"); data.put("location",e.getLocation()==null?"":e.getLocation());
        data.put("url","/?batchId="+e.getBatch().getId()+"&eventId="+e.getId()); return data;
    }
    private Map<String,Object> recipient(User u,BatchReminderPreference p,BatchEventType type) { return Map.of("userId",u.getId().toString(),"reminderMinutes",offsets(p,type)); }
    private PushBridgeService.Result syncOne(BatchTimetableEvent e, List<BatchMembership> approved) {
        List<Map<String,Object>> targets=approved.stream().filter(m->m.getStatus()==MembershipStatus.APPROVED)
                .map(m->recipient(m.getStudent(),preferences.findByUserId(m.getStudent().getId()).orElseGet(()->defaultPreference(m.getStudent())),e.getType())).toList();
        return bridge.reminderReplace(e.getBatch().getId(),e.getId(),e.getVersion(),eventData(e),targets);
    }
    private BatchReminderPreference defaultPreference(User user) { BatchReminderPreference p=new BatchReminderPreference(); p.setUser(user); return p; }
    @Transactional public boolean syncPublishedEvent(BatchTimetableEvent event) {
        try { var result=syncOne(event,memberships.findByBatchIdAndStatus(event.getBatch().getId(),MembershipStatus.APPROVED)); event.setNotificationSetupPending(result.status()<200||result.status()>=300); }
        catch(Exception ignored){event.setNotificationSetupPending(true);} return !event.isNotificationSetupPending();
    }
    @Transactional public boolean syncCancelledEvent(BatchTimetableEvent event) {
        try { List<String> ids=memberships.findByBatchIdAndStatus(event.getBatch().getId(),MembershipStatus.APPROVED).stream().map(m->m.getStudent().getId().toString()).toList();
            var result=bridge.reminderCancel(event.getBatch().getId(),event.getId(),event.getVersion(),eventData(event),ids,event.getChangeReason()); event.setNotificationSetupPending(result.status()<200||result.status()>=300); }
        catch(Exception ignored){event.setNotificationSetupPending(true);} return !event.isNotificationSetupPending();
    }
    @Transactional public boolean syncMembership(BatchMembership membership) {
        if(membership.getStatus()!=MembershipStatus.APPROVED) {
            try { var cleared=bridge.reminderSync(membership.getStudent().getId(),membership.getBatch().getId(),List.of(),true); membership.setNotificationSetupPending(cleared.status()<200||cleared.status()>=300); }
            catch(Exception ignored){membership.setNotificationSetupPending(true);} return !membership.isNotificationSetupPending();
        }
        User user=membership.getStudent(); Batch batch=membership.getBatch(); BatchReminderPreference pref=preferences.findByUserId(user.getId()).orElseGet(()->defaultPreference(user));
        List<Map<String,Object>> jobs=new ArrayList<>();
        try { for(BatchTimetableEvent e:events.findByBatchIdOrderByEventDateAscStartTimeAsc(batch.getId())) {
            if(e.getStatus()!=BatchEventStatus.PUBLISHED)continue;
            Map<String,Object> data=eventData(e); long start=(long)data.get("startAt");
            for(int mins:offsets(pref,e.getType())) {
                String id="event:"+e.getId()+":v"+e.getVersion()+":user:"+user.getId()+":before:"+mins;
                Map<String,Object> payload=Map.of("title","TimeAlert reminder","body",e.getTitle()+" ("+e.getType()+") at "+data.get("timeLabel")+" starts in "+(mins==1440?"1 day":mins+" minutes")+(e.getLocation()==null||e.getLocation().isBlank()?"":". Room: "+e.getLocation()+"."),"url",data.get("url"),"id",id);
                long scheduledAt=start-mins*60_000L;
                if(scheduledAt>Instant.now().toEpochMilli()) jobs.add(Map.of("id",id,"batchId",batch.getId().toString(),"eventId",e.getId().toString(),"version",e.getVersion(),"userId",user.getId().toString(),"kind","REMINDER","scheduledAt",scheduledAt,"payload",payload));
            }
        }
        var result=bridge.reminderSync(user.getId(),batch.getId(),jobs); membership.setNotificationSetupPending(result.status()<200||result.status()>=300); }
        catch(Exception ignored){membership.setNotificationSetupPending(true);} return !membership.isNotificationSetupPending();
    }
    @Transactional public boolean syncForUser(Long userId) {
        boolean ready=true;
        for(BatchMembership m:memberships.findByStudentIdAndStatus(userId,MembershipStatus.APPROVED)) ready &= syncMembership(m);
        BatchReminderPreference p=preferences.findByUserId(userId).orElse(null); if(p!=null) { p.setNotificationSetupPending(!ready); preferences.save(p); }
        return ready;
    }
    @Transactional public boolean resyncBatch(Long batchId) {
        Batch batch=batches.findById(batchId).orElseThrow(()->new NoSuchElementException("Batch not found."));
        boolean ready=true;
        for(BatchTimetableEvent e:events.findByBatchIdOrderByEventDateAscStartTimeAsc(batchId)) {
            if(e.getStatus()==BatchEventStatus.PUBLISHED) ready &= syncPublishedEvent(e);
            else if(e.getStatus()==BatchEventStatus.CANCELLED&&e.isNotificationSetupPending()) ready &= syncCancelledEvent(e);
        }
        for(BatchMembership m:memberships.findByBatchIdAndStatus(batchId,MembershipStatus.APPROVED)) { m.setNotificationSetupPending(!ready); memberships.save(m); }
        return ready;
    }
    @Transactional public boolean syncPreferences(User user) {
        boolean ready=true; for(BatchMembership m:memberships.findByStudentIdAndStatus(user.getId(),MembershipStatus.APPROVED)) ready &= syncMembership(m);
        return ready;
    }
    public Map<String,Object> summary(Long batchId) {
        var result=bridge.reminderSummary(batchId);
        Map<String,Object> response=new LinkedHashMap<>(result.body()); response.put("notificationSetupPending",result.status()<200||result.status()>=300);
        response.put("notificationSyncStatus",result.status()<200||result.status()>=300?"PENDING":"READY"); return response;
    }
}
