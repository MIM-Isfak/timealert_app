package com.timealert.service;

import com.timealert.model.Event;
import com.timealert.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileParserPreviewTests {
    @Mock EventRepository events;
    @InjectMocks FileParserService parser;

    @Test void batchPreviewUsesSharedCsvParserAndDoesNotPersistPersonalEvents() throws Exception {
        String csv="Subject,Description,Date,StartTime,EndTime,Location\n"
                +"Algorithms,Lecture,2027-03-01,09:00,10:00,Room 2\n"
                +"Broken,No date,,09:00,10:00,Room 3\n";
        MockMultipartFile upload=new MockMultipartFile("file","schedule.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8));
        FileParserService.ParseResult result=parser.parseForBatch(upload);
        assertEquals(1,result.getSavedCount());
        assertEquals("Algorithms",result.getEvents().get(0).getTitle());
        assertEquals(1,result.getSkippedReasons().size());
        assertTrue(result.getSkippedReasons().get(0).contains("Row 3"));
        verify(events,never()).saveAll(anyList());
    }
}
