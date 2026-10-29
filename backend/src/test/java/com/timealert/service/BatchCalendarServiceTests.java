package com.timealert.service;

import com.timealert.model.*;
import com.timealert.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchCalendarServiceTests {
    @Mock BatchRepository batches;
    @Mock BatchMembershipRepository memberships;
    @Mock BatchTimetableEventRepository events;
    @InjectMocks BatchTimetableService timetable;
    User student; Batch batch; BatchCalendarService calendar;
    @BeforeEach void setup() {
        student=new User();student.setId(3L);student.setRole(Role.STUDENT);
        batch=new Batch();batch.setId(10L);batch.setTimezone("Asia/Colombo");
        lenient().when(batches.findById(10L)).thenReturn(Optional.of(batch));calendar=new BatchCalendarService(timetable);
    }
    private BatchTimetableEvent event(long id,BatchEventStatus status) {
        var e=new BatchTimetableEvent();e.setId(id);e.setBatch(batch);e.setTitle("Exam, A; B");e.setDescription("First\r\nBEGIN:VEVENT\nSecond\\line");
        e.setLocation("Room 1");e.setType(BatchEventType.EXAM);e.setStatus(status);e.setVersion(4);
        e.setEventDate(LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(2));e.setStartTime(LocalTime.of(9,0));e.setEndTime(LocalTime.of(10,0));return e;
    }
    @Test void approvedExportUsesUtcStableUidVersionsCancellationAndEscapedContent() {
        when(memberships.existsByBatchIdAndStudentIdAndStatus(10L,3L,MembershipStatus.APPROVED)).thenReturn(true);
        var published=event(1,BatchEventStatus.PUBLISHED);var cancelled=event(2,BatchEventStatus.CANCELLED);cancelled.setChangeReason("Moved");
        when(events.findByBatchIdOrderByEventDateAscStartTimeAsc(10L)).thenReturn(List.of(published,cancelled,event(3,BatchEventStatus.DRAFT)));
        String out=new String(calendar.export(student,10L),StandardCharsets.UTF_8);
        assertTrue(out.contains("BEGIN:VCALENDAR\r\nVERSION:2.0"));assertTrue(out.contains("UID:batch-10-event-1@timealert.local"));
        assertTrue(out.contains("DTSTART:"+published.getEventDate().toString().replace("-","")+"T033000Z"));
        assertTrue(out.contains("DTEND:"+published.getEventDate().toString().replace("-","")+"T043000Z"));
        assertTrue(out.contains("SUMMARY:Exam\\, A\\; B"));assertTrue(out.contains("\\nBEGIN:VEVENT\\nSecond\\\\line"));
        assertTrue(out.contains("STATUS:CANCELLED"));assertTrue(out.contains("SEQUENCE:4"));assertFalse(out.contains("event-3@"));assertFalse(out.contains("VALARM"));
        assertEquals(2,out.split("BEGIN:VEVENT\r\n",-1).length-1);assertFalse(out.replace("\r\n","").contains("\n"));
    }
    @Test void exportFoldsUnicodeAt75OctetsWithoutSplittingCodePoints() {
        when(memberships.existsByBatchIdAndStudentIdAndStatus(10L,3L,MembershipStatus.APPROVED)).thenReturn(true);
        var e=event(1,BatchEventStatus.PUBLISHED);e.setTitle("数学📚".repeat(30));
        when(events.findByBatchIdOrderByEventDateAscStartTimeAsc(10L)).thenReturn(List.of(e));
        String out=new String(calendar.export(student,10L),StandardCharsets.UTF_8);
        for(String line:out.split("\r\n"))assertTrue(line.getBytes(StandardCharsets.UTF_8).length<=75);
        assertTrue(out.replace("\r\n ","").contains("SUMMARY:"+e.getTitle()));assertFalse(out.contains("\uFFFD"));
    }
    @Test void pendingRejectedAndNonmembersCannotExport() {
        assertThrows(SecurityException.class,()->calendar.export(student,10L));
        verify(memberships).existsByBatchIdAndStudentIdAndStatus(10L,3L,MembershipStatus.APPROVED);verifyNoInteractions(events);
    }
    @Test void approvalOfAnotherBatchDoesNotGrantExportAccess() {
        Batch other=new Batch();other.setId(11L);when(batches.findById(11L)).thenReturn(Optional.of(other));
        assertThrows(SecurityException.class,()->calendar.export(student,11L));verifyNoInteractions(events);
    }
    @Test void repCannotUseStudentCalendarExport() {
        student.setRole(Role.REP);assertThrows(SecurityException.class,()->calendar.export(student,10L));verifyNoInteractions(events);
    }
}
