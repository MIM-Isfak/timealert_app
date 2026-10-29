package com.timealert.service;

import com.timealert.model.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Service;

/** Download snapshot only: no public calendar URL, subscription token, or VALARM. */
@Service
public class BatchCalendarService {
    private final BatchTimetableService timetable;
    private static final DateTimeFormatter UTC=DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    public BatchCalendarService(BatchTimetableService timetable) { this.timetable=timetable; }
    public byte[] export(User student,Long batchId) {
        // The same role, approved-membership and batch checks protect calendar and UI reads.
        var events=timetable.forStudent(student,batchId);
        StringBuilder out=new StringBuilder();
        line(out,"BEGIN:VCALENDAR"); line(out,"VERSION:2.0"); line(out,"PRODID:-//TimeAlert//Batch Timetable//EN"); line(out,"CALSCALE:GREGORIAN");
        for(BatchTimetableEvent e:events) {
            if(e.getStatus()==BatchEventStatus.DRAFT)continue;
            ZoneId zone=ZoneId.of(e.getBatch().getTimezone());
            line(out,"BEGIN:VEVENT"); line(out,"UID:batch-"+batchId+"-event-"+e.getId()+"@timealert.local");
            line(out,"DTSTAMP:"+UTC.format(Instant.now()));
            line(out,"DTSTART:"+UTC.format(LocalDateTime.of(e.getEventDate(),e.getStartTime()).atZone(zone).toInstant()));
            line(out,"DTEND:"+UTC.format(LocalDateTime.of(e.getEventDate(),e.getEndTime()).atZone(zone).toInstant()));
            line(out,"SEQUENCE:"+e.getVersion()); line(out,"STATUS:"+(e.getStatus()==BatchEventStatus.CANCELLED?"CANCELLED":"CONFIRMED"));
            line(out,"SUMMARY:"+text(e.getTitle())); line(out,"CATEGORIES:"+e.getType().name());
            line(out,"LOCATION:"+text(e.getLocation()));
            line(out,"DESCRIPTION:"+text((e.getDescription()==null?"":e.getDescription())+(e.getChangeReason()==null?"":"\nChange: "+e.getChangeReason())));
            line(out,"END:VEVENT");
        }
        line(out,"END:VCALENDAR"); return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String text(String value) {
        if(value==null)return "";
        return value.replace("\\","\\\\").replace("\r\n","\n").replace("\r","\n").replace("\n","\\n").replace(";","\\;").replace(",","\\,");
    }
    // RFC 5545: fold at 75 octets without breaking a Unicode code point.
    private static void line(StringBuilder out,String value) {
        int bytes=0;
        for(int offset=0;offset<value.length();) {
            int point=value.codePointAt(offset); String s=new String(Character.toChars(point)); int size=s.getBytes(StandardCharsets.UTF_8).length;
            if(bytes+size>75){out.append("\r\n ");bytes=1;}
            out.append(s); bytes+=size; offset+=Character.charCount(point);
        }
        out.append("\r\n");
    }
}
