package com.timealert.service;

import com.timealert.model.*;
import com.timealert.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchTimetableServiceTests {
    @Mock BatchRepository batches;
    @Mock BatchMembershipRepository memberships;
    @Mock BatchTimetableEventRepository events;
    @Mock BatchEventHistoryRepository history;
    @Mock BatchEventAcknowledgementRepository acknowledgements;
    @Mock BatchReminderSyncService reminderSyncService;
    @InjectMocks BatchTimetableService service;
    User rep, otherRep, student;
    Batch batch;
    @BeforeEach void setup() {
        rep=new User(); rep.setId(1L); rep.setRole(Role.REP);
        otherRep=new User(); otherRep.setId(2L); otherRep.setRole(Role.REP);
        student=new User(); student.setId(3L); student.setRole(Role.STUDENT);
        batch=new Batch(); batch.setId(10L); batch.setRep(rep);
        when(batches.findById(10L)).thenReturn(Optional.of(batch));
    }
    private Map<String,Object> request(String date) {
        return new HashMap<>(Map.of("title","Algorithms", "eventDate",date,"startTime","09:00","endTime","10:00","type","CLASS"));
    }
    @Test void onlyOwningRepCanCreateAndCrossBatchEventIdsAreRejected() {
        when(events.save(any(BatchTimetableEvent.class))).thenAnswer(i -> i.getArgument(0));
        assertThrows(SecurityException.class,()->service.create(otherRep,10L,request("2027-01-04")));
        assertTrue(service.create(rep,10L,request("2027-01-04")).size()==1);
        BatchTimetableEvent foreign=new BatchTimetableEvent(); foreign.setBatch(new Batch()); foreign.getBatch().setId(11L);
        when(events.findById(5L)).thenReturn(Optional.of(foreign));
        assertThrows(SecurityException.class,()->service.edit(rep,10L,5L,Map.of("title","x")));
    }
    @Test void weeklyOccurrencesMaterializeWithSharedSeriesAndInvalidRangesAreRejected() {
        when(events.save(any(BatchTimetableEvent.class))).thenAnswer(i -> i.getArgument(0));
        Map<String,Object> weekly=request("2027-01-04"); weekly.put("repeatStart","2027-01-04"); weekly.put("repeatEnd","2027-01-25");
        List<BatchTimetableEvent> made=service.create(rep,10L,weekly);
        assertEquals(4,made.size()); assertEquals(1,made.stream().map(BatchTimetableEvent::getSeriesId).distinct().count());
        assertThrows(IllegalArgumentException.class,()->service.create(rep,10L,Map.of("title","x","eventDate","2027-01-04","startTime","10:00","endTime","09:00")));
        weekly.put("repeatEnd","2028-02-01"); assertThrows(IllegalArgumentException.class,()->service.create(rep,10L,weekly));
    }
    @Test void studentRequiresApprovedMembershipAndDraftsStayInvisible() {
        assertThrows(SecurityException.class,()->service.forStudent(student,10L));
        when(memberships.existsByBatchIdAndStudentIdAndStatus(10L,3L,MembershipStatus.APPROVED)).thenReturn(true);
        when(events.findByBatchIdOrderByEventDateAscStartTimeAsc(10L)).thenReturn(List.of());
        assertTrue(service.forStudent(student,10L).isEmpty());
    }
    @Test void importPreviewCreatesDraftsAndPublishMakesThemVisible() {
        when(events.save(any(BatchTimetableEvent.class))).thenAnswer(i -> i.getArgument(0));
        Event parsed=new Event(); parsed.setTitle("Imported exam"); parsed.setEventDate(LocalDate.now().plusDays(2)); parsed.setStartTime(LocalTime.of(9,0)); parsed.setEndTime(LocalTime.of(11,0));
        List<BatchTimetableEvent> preview=service.preview(rep,10L,List.of(parsed));
        assertEquals(BatchEventStatus.DRAFT,preview.get(0).getStatus());
        assertEquals(BatchEventType.OTHER,preview.get(0).getType());
        when(events.findByBatchIdOrderByEventDateAscStartTimeAsc(10L)).thenReturn(preview);
        assertEquals(BatchEventStatus.PUBLISHED,service.publish(rep,10L).get(0).getStatus());
        verify(events).save(any(BatchTimetableEvent.class));
    }
    @Test void publishRemainsPublishedWhenReminderSynchronizationIsPending() {
        BatchTimetableEvent draft=new BatchTimetableEvent();draft.setId(9L);draft.setBatch(batch);draft.setTitle("Published");draft.setEventDate(LocalDate.now().plusDays(1));draft.setStartTime(LocalTime.of(9,0));draft.setEndTime(LocalTime.of(10,0));draft.setType(BatchEventType.CLASS);
        when(events.findByBatchIdOrderByEventDateAscStartTimeAsc(10L)).thenReturn(List.of(draft));
        doAnswer(inv->{((BatchTimetableEvent)inv.getArgument(0)).setNotificationSetupPending(true);return false;}).when(reminderSyncService).syncPublishedEvent(draft);
        assertEquals(BatchEventStatus.PUBLISHED,service.publish(rep,10L).get(0).getStatus());
        assertTrue(draft.isNotificationSetupPending());
    }
    @Test void editAndCancellationIncrementVersionAndRecordBeforeAfterHistory() {
        BatchTimetableEvent event=new BatchTimetableEvent(); event.setId(7L); event.setBatch(batch); event.setTitle("Old"); event.setEventDate(LocalDate.now().plusDays(1)); event.setStartTime(LocalTime.of(9,0)); event.setEndTime(LocalTime.of(10,0)); event.setType(BatchEventType.CLASS); event.setStatus(BatchEventStatus.PUBLISHED); event.setVersion(1);
        when(events.findById(7L)).thenReturn(Optional.of(event)); when(events.existsByBatchIdAndEventDateAndStartTimeAndTitle(anyLong(),any(),any(),anyString())).thenReturn(false); when(events.save(any())).thenAnswer(i->i.getArgument(0));
        BatchTimetableEvent edited=service.edit(rep,10L,7L,Map.of("title","New","reason","Room changed"));
        assertEquals(2,edited.getVersion()); verify(history).save(argThat(h->h.getAction().equals("UPDATED")&&h.getBeforeState().contains("Old")&&h.getAfterState().contains("New")));
        BatchTimetableEvent cancelled=service.cancel(rep,10L,7L,"Class called off");
        assertEquals(3,cancelled.getVersion()); assertEquals(BatchEventStatus.CANCELLED,cancelled.getStatus());
        verify(history).save(argThat(h->h.getAction().equals("CANCELLED")&&h.getReason().equals("Class called off")));
        verify(reminderSyncService).syncPublishedEvent(edited); verify(reminderSyncService).syncCancelledEvent(cancelled);
        assertThrows(IllegalArgumentException.class,()->service.cancel(rep,10L,7L," "));
    }
    @Test void acknowledgementIsCurrentVersionOnlyAndCrossBatchIsForbidden() {
        when(memberships.existsByBatchIdAndStudentIdAndStatus(10L,3L,MembershipStatus.APPROVED)).thenReturn(true);
        BatchTimetableEvent e=new BatchTimetableEvent(); e.setId(8L); e.setBatch(batch); e.setStatus(BatchEventStatus.PUBLISHED); e.setVersion(2);
        when(events.findById(8L)).thenReturn(Optional.of(e)); when(acknowledgements.findByEventIdAndStudentId(8L,3L)).thenReturn(Optional.empty());
        Batch otherBatch=new Batch(); otherBatch.setId(11L); when(batches.findById(11L)).thenReturn(Optional.of(otherBatch));
        assertTrue(service.acknowledge(student,10L,8L)); verify(acknowledgements).save(argThat(a->a.getVersion()==2));
        when(acknowledgements.existsByEventIdAndStudentIdAndVersion(8L,3L,2)).thenReturn(false);
        assertFalse(service.hasAck(student,10L,8L));
        verify(acknowledgements).existsByEventIdAndStudentIdAndVersion(8L,3L,2);
        assertThrows(SecurityException.class,()->service.acknowledge(student,11L,8L));
    }
}
