package com.timealert.service;

import com.timealert.model.*;
import com.timealert.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchReminderSyncServiceTests {
    @Mock PushBridgeService bridge;
    @Mock BatchMembershipRepository memberships;
    @Mock BatchReminderPreferenceRepository preferences;
    @Mock BatchTimetableEventRepository events;
    @Mock BatchRepository batches;
    @InjectMocks BatchReminderSyncService sync;
    User rep,student; Batch batch; BatchTimetableEvent event; BatchMembership member; BatchReminderPreference pref;
    @BeforeEach void setup(){
        rep=new User();rep.setId(9L);rep.setRole(Role.REP); student=new User();student.setId(42L);student.setRole(Role.STUDENT);
        batch=new Batch();batch.setId(12L);batch.setRep(rep);batch.setTimezone("Asia/Colombo");
        event=new BatchTimetableEvent();event.setId(77L);event.setBatch(batch);event.setTitle("Discrete Mathematics");event.setType(BatchEventType.EXAM);event.setEventDate(LocalDate.of(2028,2,1));event.setStartTime(LocalTime.of(9,30));event.setEndTime(LocalTime.of(11,0));event.setLocation("Room 4");event.setVersion(3);event.setStatus(BatchEventStatus.PUBLISHED);
        member=new BatchMembership();member.setId(4L);member.setBatch(batch);member.setStudent(student);member.setStatus(MembershipStatus.APPROVED);
        pref=new BatchReminderPreference();pref.setUser(student);pref.setFiveMinutes(true);pref.setTenMinutes(false);pref.setThirtyMinutes(true);pref.setExamDayBefore(true);
    }
    @Test void publishSynchronizationUsesBatchIanaTimezoneAndPreferenceOffsets(){
        when(memberships.findByBatchIdAndStatus(12L,MembershipStatus.APPROVED)).thenReturn(List.of(member));
        when(preferences.findByUserId(42L)).thenReturn(Optional.of(pref));
        when(bridge.reminderReplace(eq(12L),eq(77L),eq(3),anyMap(),anyList())).thenReturn(new PushBridgeService.Result(200,Map.of("status","replaced")));
        assertTrue(sync.syncPublishedEvent(event));
        ArgumentCaptor<Map> eventData=ArgumentCaptor.forClass(Map.class);
        verify(bridge).reminderReplace(eq(12L),eq(77L),eq(3),eventData.capture(),argThat(recipients->recipients.size()==1&&((List<?>)recipients.get(0).get("reminderMinutes")).equals(List.of(5,30,1440))));
        assertEquals("2028-02-01 09:30 (Asia/Colombo)",eventData.getValue().get("timeLabel")); assertEquals("/?batchId=12&eventId=77",eventData.getValue().get("url"));
        long expected=LocalDateTime.of(event.getEventDate(),event.getStartTime()).atZone(ZoneId.of("Asia/Colombo")).toInstant().toEpochMilli();
        assertEquals(expected,eventData.getValue().get("startAt"));
        assertFalse(event.isNotificationSetupPending());
    }
    @Test void publishFailureIsRecordedAsPendingAndDoesNotThrow(){
        when(memberships.findByBatchIdAndStatus(12L,MembershipStatus.APPROVED)).thenReturn(List.of(member));
        when(preferences.findByUserId(42L)).thenReturn(Optional.of(pref));
        when(bridge.reminderReplace(anyLong(),anyLong(),anyInt(),anyMap(),anyList())).thenReturn(PushBridgeService.failure(503,"unavailable"));
        assertFalse(sync.syncPublishedEvent(event)); assertTrue(event.isNotificationSetupPending());
    }
    @Test void approvalRebuildsPublishedJobsAndRecordsWorkerFailureWithoutFailingApproval(){
        when(preferences.findByUserId(42L)).thenReturn(Optional.of(pref)); when(events.findByBatchIdOrderByEventDateAscStartTimeAsc(12L)).thenReturn(List.of(event));
        when(bridge.reminderSync(eq(42L),eq(12L),anyList())).thenReturn(PushBridgeService.failure(503,"unavailable"));
        assertFalse(sync.syncMembership(member)); assertTrue(member.isNotificationSetupPending());
        verify(bridge).reminderSync(eq(42L),eq(12L),argThat(jobs->jobs.size()==3));
    }
    @Test void cancellationCallsWorkerForCurrentApprovedMembers(){
        event.setStatus(BatchEventStatus.CANCELLED);event.setChangeReason("Instructor unavailable");
        when(memberships.findByBatchIdAndStatus(12L,MembershipStatus.APPROVED)).thenReturn(List.of(member));
        when(bridge.reminderCancel(eq(12L),eq(77L),eq(3),anyMap(),eq(List.of("42")),eq("Instructor unavailable"))).thenReturn(new PushBridgeService.Result(200,Map.of("status","cancelled")));
        assertTrue(sync.syncCancelledEvent(event)); verify(bridge).reminderCancel(eq(12L),eq(77L),eq(3),argThat(data->((Long)data.get("startAt"))>0),eq(List.of("42")),eq("Instructor unavailable"));
    }
}
