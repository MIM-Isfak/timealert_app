package com.timealert.service;

import com.timealert.model.Event;
import com.timealert.repository.EventRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.Loader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class FileParserService {

    @Autowired
    private EventRepository eventRepository;

    public List<Event> parseFile(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();

        if (filename == null || filename.trim().isEmpty()) {
            throw new RuntimeException("Invalid file: no filename!");
        }

        if (filename.endsWith(".csv")) {
            return parseCSV(file);
        } else if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
            return parseExcel(file);
        } else if (filename.endsWith(".pdf")) {
            return parsePDF(file);
        }
        throw new RuntimeException("Unsupported file type!");
    }

    private List<Event> parseCSV(MultipartFile file) throws IOException {
        List<Event> events = new ArrayList<>();

        // UTF-8 encoding fix — emoji சரியா வரும்
        Reader reader = new InputStreamReader(
            file.getInputStream(), StandardCharsets.UTF_8
        );

        CSVParser csvParser = new CSVParser(reader,
            CSVFormat.DEFAULT.withFirstRecordAsHeader().withTrim()
        );

        for (CSVRecord record : csvParser) {
            try {
                Event event = new Event();
                event.setTitle(record.get("title"));
                event.setDescription(record.get("description"));
                event.setEventDate(LocalDate.parse(record.get("eventDate")));
                event.setStartTime(LocalTime.parse(record.get("startTime")));
                event.setEndTime(LocalTime.parse(record.get("endTime")));
                event.setLocation(record.get("location"));
                event.setNotified(false);
                events.add(event);
            } catch (Exception e) {
                System.out.println("Skipping invalid row: " + e.getMessage());
            }
        }
        csvParser.close();
        return eventRepository.saveAll(events);
    }

    private List<Event> parseExcel(MultipartFile file) throws IOException {
        List<Event> events = new ArrayList<>();
        Workbook workbook = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = workbook.getSheetAt(0);

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            try {
                Event event = new Event();
                event.setTitle(getCellValue(row, 0));
                event.setDescription(getCellValue(row, 1));
                event.setEventDate(LocalDate.parse(getCellValue(row, 2)));
                event.setStartTime(LocalTime.parse(getCellValue(row, 3)));
                event.setEndTime(LocalTime.parse(getCellValue(row, 4)));
                event.setLocation(getCellValue(row, 5));
                event.setNotified(false);
                events.add(event);
            } catch (Exception e) {
                System.out.println("Skipping invalid row " + i + ": " + e.getMessage());
            }
        }
        workbook.close();
        return eventRepository.saveAll(events);
    }

    private List<Event> parsePDF(MultipartFile file) throws IOException {
        List<Event> events = new ArrayList<>();
        PDDocument document = Loader.loadPDF(file.getBytes());
        PDFTextStripper stripper = new PDFTextStripper();
        String text = stripper.getText(document);
        document.close();

        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;

            try {
                Event event = new Event();
                event.setTitle(parts[0].trim());
                event.setDescription(parts[1].trim());
                event.setEventDate(LocalDate.parse(parts[2].trim()));
                event.setStartTime(LocalTime.parse(parts[3].trim()));
                event.setEndTime(LocalTime.parse(parts[4].trim()));
                event.setLocation(parts[5].trim());
                event.setNotified(false);
                events.add(event);
            } catch (Exception e) {
                System.out.println("Skipping invalid PDF row: " + e.getMessage());
            }
        }
        return eventRepository.saveAll(events);
    }

    private String getCellValue(Row row, int cellIndex) {
        Cell cell = row.getCell(cellIndex);
        if (cell == null) return "";
        return cell.toString().trim();
    }
}