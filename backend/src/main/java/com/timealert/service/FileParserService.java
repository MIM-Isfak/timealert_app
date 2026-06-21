package com.timealert.service;

import com.timealert.model.Event;
import com.timealert.model.User;
import com.timealert.repository.EventRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(FileParserService.class);

    @Autowired
    private EventRepository eventRepository;

    // ---------------------------------------------------------------------------
    // ParseResult: returned to the controller so it can report skipped rows
    // ---------------------------------------------------------------------------
    public static class ParseResult {
        private final int savedCount;
        private final List<String> skippedReasons;

        public ParseResult(int savedCount, List<String> skippedReasons) {
            this.savedCount = savedCount;
            this.skippedReasons = skippedReasons;
        }

        public int getSavedCount() { return savedCount; }
        public List<String> getSkippedReasons() { return skippedReasons; }
    }

    // ---------------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------------
    public ParseResult parseFile(MultipartFile file, User user) throws IOException {
        String filename = file.getOriginalFilename();

        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid file: no filename!");
        }

        String lowerName = filename.toLowerCase();

        if (lowerName.endsWith(".csv")) {
            return parseCSV(file, user);
        } else if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            return parseExcel(file, user);
        } else if (lowerName.endsWith(".pdf")) {
            return parsePDF(file, user);
        }

        throw new IllegalArgumentException("Unsupported file type! Please upload CSV, Excel (.xlsx/.xls), or PDF.");
    }

    // ---------------------------------------------------------------------------
    // CSV
    // ---------------------------------------------------------------------------
    private ParseResult parseCSV(MultipartFile file, User user) throws IOException {
        List<Event> events = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);

        try (CSVParser csvParser = new CSVParser(reader,
                CSVFormat.DEFAULT.withFirstRecordAsHeader().withTrim())) {

            int rowNum = 1;
            for (CSVRecord record : csvParser) {
                rowNum++;
                try {
                    Event event = new Event();
                    event.setTitle(requireNonBlank(record.get("Subject"), "Subject"));
                    event.setDescription(getOrEmpty(record, "Description"));
                    event.setEventDate(LocalDate.parse(requireNonBlank(record.get("Date"), "Date")));
                    event.setStartTime(LocalTime.parse(requireNonBlank(record.get("StartTime"), "StartTime")));
                    event.setEndTime(LocalTime.parse(requireNonBlank(record.get("EndTime"), "EndTime")));
                    event.setLocation(getOrEmpty(record, "Location"));
                    event.setNotified(false);
                    event.setUser(user);
                    events.add(event);
                } catch (Exception e) {
                    String reason = "Row " + rowNum + ": " + e.getMessage();
                    skipped.add(reason);
                    logger.warn("CSV parse — skipping {}", reason);
                }
            }
        }

        List<Event> saved = eventRepository.saveAll(events);
        logger.info("CSV upload: {} events saved, {} rows skipped.", saved.size(), skipped.size());
        return new ParseResult(saved.size(), skipped);
    }

    // ---------------------------------------------------------------------------
    // Excel (.xlsx / .xls)
    // ---------------------------------------------------------------------------
    private ParseResult parseExcel(MultipartFile file, User user) throws IOException {
        List<Event> events = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // BufferedInputStream required so POI can mark/reset the stream for .xls (HSSF)
        try (InputStream bis = new BufferedInputStream(file.getInputStream());
             Workbook workbook = WorkbookFactory.create(bis)) {

            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                try {
                    Event event = new Event();
                    event.setTitle(requireNonBlank(getCellValue(row, 0), "Title (col 0)"));
                    event.setDescription(getCellValue(row, 1));
                    event.setEventDate(LocalDate.parse(requireNonBlank(getCellValue(row, 2), "Date (col 2)")));
                    event.setStartTime(LocalTime.parse(requireNonBlank(getCellValue(row, 3), "StartTime (col 3)")));
                    event.setEndTime(LocalTime.parse(requireNonBlank(getCellValue(row, 4), "EndTime (col 4)")));
                    event.setLocation(getCellValue(row, 5));
                    event.setNotified(false);
                    event.setUser(user);
                    events.add(event);
                } catch (Exception e) {
                    String reason = "Row " + (i + 1) + ": " + e.getMessage();
                    skipped.add(reason);
                    logger.warn("Excel parse — skipping {}", reason);
                }
            }
        }

        List<Event> saved = eventRepository.saveAll(events);
        logger.info("Excel upload: {} events saved, {} rows skipped.", saved.size(), skipped.size());
        return new ParseResult(saved.size(), skipped);
    }

    // ---------------------------------------------------------------------------
    // PDF (best-effort: assumes comma-separated lines)
    // ---------------------------------------------------------------------------
    private ParseResult parsePDF(MultipartFile file, User user) throws IOException {
        List<Event> events = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            String[] lines = text.split("\n");

            int lineNum = 0;
            for (String line : lines) {
                lineNum++;
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length < 5) continue; // skip header/non-data lines silently

                try {
                    Event event = new Event();
                    event.setTitle(requireNonBlank(parts[0].trim(), "Title"));
                    event.setDescription(parts.length > 1 ? parts[1].trim() : "");
                    event.setEventDate(LocalDate.parse(requireNonBlank(parts[2].trim(), "Date")));
                    event.setStartTime(LocalTime.parse(requireNonBlank(parts[3].trim(), "StartTime")));
                    event.setEndTime(LocalTime.parse(requireNonBlank(parts[4].trim(), "EndTime")));
                    event.setLocation(parts.length > 5 ? parts[5].trim() : "");
                    event.setNotified(false);
                    event.setUser(user);
                    events.add(event);
                } catch (Exception e) {
                    String reason = "Line " + lineNum + ": " + e.getMessage();
                    skipped.add(reason);
                    logger.warn("PDF parse — skipping {}", reason);
                }
            }
        }

        List<Event> saved = eventRepository.saveAll(events);
        logger.info("PDF upload: {} events saved, {} rows skipped.", saved.size(), skipped.size());
        return new ParseResult(saved.size(), skipped);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------
    private String getCellValue(Row row, int cellIndex) {
        Cell cell = row.getCell(cellIndex);
        if (cell == null) return "";
        return cell.toString().trim();
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
        return value.trim();
    }

    private String getOrEmpty(CSVRecord record, String column) {
        try {
            String val = record.get(column);
            return val != null ? val : "";
        } catch (IllegalArgumentException e) {
            return ""; // column not present in header — treat as empty
        }
    }
}