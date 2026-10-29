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
import org.apache.pdfbox.io.RandomAccessReadBuffer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class FileParserService {

    private static final Logger logger = LoggerFactory.getLogger(FileParserService.class);

    // Characters that trigger formula execution when a CSV/Excel is opened in a spreadsheet app.
    // Cells starting with these must be sanitized before storage.
    private static final String FORMULA_INJECTION_CHARS = "=+-@";

    @Autowired
    private EventRepository eventRepository;

    // ---------------------------------------------------------------------------
    // ParseResult: returned to the controller so it can report skipped rows
    // ---------------------------------------------------------------------------
    public static class ParseResult {
        private final int savedCount;
        private final List<String> skippedReasons;
        private final List<Event> events;

        public ParseResult(int savedCount, List<String> skippedReasons, List<Event> events) {
            this.savedCount = savedCount;
            this.skippedReasons = skippedReasons;
            this.events = events;
        }

        public int getSavedCount() { return savedCount; }
        public List<String> getSkippedReasons() { return skippedReasons; }
        public List<Event> getEvents() { return events; }
    }

    // ---------------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------------
    public ParseResult parseFile(MultipartFile file, User user) throws IOException {
        return parse(file, user, true);
    }

    /** Uses the same CSV/Excel/PDF parser without writing to personal event storage. */
    public ParseResult parseForBatch(MultipartFile file) throws IOException {
        return parse(file, null, false);
    }

    private ParseResult parse(MultipartFile file, User user, boolean persist) throws IOException {
        String rawFilename = file.getOriginalFilename();

        if (rawFilename == null || rawFilename.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid file: no filename!");
        }

        // Sanitize filename: strip path traversal components before any use (including logging).
        String filename = Paths.get(rawFilename).getFileName().toString();
        String lowerName = filename.toLowerCase();

        if (lowerName.endsWith(".csv")) {
            return parseCSV(file, user, persist);
        } else if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            return parseExcel(file, user, persist);
        } else if (lowerName.endsWith(".pdf")) {
            return parsePDF(file, user, persist);
        }

        throw new IllegalArgumentException("Unsupported file type! Please upload CSV, Excel (.xlsx/.xls), or PDF.");
    }

    // ---------------------------------------------------------------------------
    // CSV
    // ---------------------------------------------------------------------------
    private ParseResult parseCSV(MultipartFile file, User user, boolean persist) throws IOException {
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
                    event.setTitle(sanitize(requireNonBlank(record.get("Subject"), "Subject")));
                    event.setDescription(sanitize(getOrEmpty(record, "Description")));
                    event.setEventDate(LocalDate.parse(requireNonBlank(record.get("Date"), "Date")));
                    event.setStartTime(LocalTime.parse(requireNonBlank(record.get("StartTime"), "StartTime")));
                    event.setEndTime(LocalTime.parse(requireNonBlank(record.get("EndTime"), "EndTime")));
                    event.setLocation(sanitize(getOrEmpty(record, "Location")));
                    event.setNotified(false);
                    event.setNotified30(false);
                    event.setNotified20(false);
                    event.setNotified10(false);
                    event.setUser(user);
                    events.add(event);
                } catch (Exception e) {
                    String reason = "Row " + rowNum + ": " + e.getMessage();
                    skipped.add(reason);
                    logger.warn("CSV parse — skipping {}", reason);
                }
            }
        }

        List<Event> saved = persist ? eventRepository.saveAll(events) : events;
        logger.info("CSV upload: {} events parsed, {} rows skipped.", saved.size(), skipped.size());
        return new ParseResult(saved.size(), skipped, events);
    }

    // ---------------------------------------------------------------------------
    // Excel (.xlsx / .xls)
    // ---------------------------------------------------------------------------
    private ParseResult parseExcel(MultipartFile file, User user, boolean persist) throws IOException {
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
                    event.setTitle(sanitize(requireNonBlank(getCellValue(row, 0), "Title (col 0)")));
                    event.setDescription(sanitize(getCellValue(row, 1)));
                    event.setEventDate(LocalDate.parse(requireNonBlank(getCellValue(row, 2), "Date (col 2)")));
                    event.setStartTime(LocalTime.parse(requireNonBlank(getCellValue(row, 3), "StartTime (col 3)")));
                    event.setEndTime(LocalTime.parse(requireNonBlank(getCellValue(row, 4), "EndTime (col 4)")));
                    event.setLocation(sanitize(getCellValue(row, 5)));
                    event.setNotified(false);
                    event.setNotified30(false);
                    event.setNotified20(false);
                    event.setNotified10(false);
                    event.setUser(user);
                    events.add(event);
                } catch (Exception e) {
                    String reason = "Row " + (i + 1) + ": " + e.getMessage();
                    skipped.add(reason);
                    logger.warn("Excel parse — skipping {}", reason);
                }
            }
        }

        List<Event> saved = persist ? eventRepository.saveAll(events) : events;
        logger.info("Excel upload: {} events saved, {} rows skipped.", saved.size(), skipped.size());
        return new ParseResult(saved.size(), skipped, events);
    }

    // ---------------------------------------------------------------------------
    // PDF (best-effort: assumes comma-separated lines with no commas in field values)
    // NOTE: PDF timetables must have no commas inside field values — commas are
    // used as delimiters. This is a known limitation of the text-extraction approach.
    // ---------------------------------------------------------------------------
    private ParseResult parsePDF(MultipartFile file, User user, boolean persist) throws IOException {
        List<Event> events = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // RandomAccessReadBuffer wraps the stream for PDFBox 3.x — avoids loading the entire
        // PDF into a byte[] (which would double memory: multipart buffer + PDFBox internal).
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(file.getInputStream()))) {
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
                    event.setTitle(sanitize(requireNonBlank(parts[0].trim(), "Title")));
                    event.setDescription(sanitize(parts[1].trim())); // parts.length >= 5 guaranteed above
                    event.setEventDate(LocalDate.parse(requireNonBlank(parts[2].trim(), "Date")));
                    event.setStartTime(LocalTime.parse(requireNonBlank(parts[3].trim(), "StartTime")));
                    event.setEndTime(LocalTime.parse(requireNonBlank(parts[4].trim(), "EndTime")));
                    event.setLocation(sanitize(parts.length > 5 ? parts[5].trim() : ""));
                    event.setNotified(false);
                    event.setNotified30(false);
                    event.setNotified20(false);
                    event.setNotified10(false);
                    event.setUser(user);
                    events.add(event);
                } catch (Exception e) {
                    String reason = "Line " + lineNum + ": " + e.getMessage();
                    skipped.add(reason);
                    logger.warn("PDF parse — skipping {}", reason);
                }
            }
        }

        List<Event> saved = persist ? eventRepository.saveAll(events) : events;
        logger.info("PDF upload: {} events saved, {} rows skipped.", saved.size(), skipped.size());
        return new ParseResult(saved.size(), skipped, events);
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

    /**
     * Sanitizes a string value against CSV/Excel formula injection.
     *
     * Cells beginning with '=', '+', '-', or '@' are formula triggers in spreadsheet
     * applications. We prepend a single-quote prefix to neutralize them. This ensures
     * that if data is ever exported to CSV or rendered in a spreadsheet, no formula
     * executes. The prefix is invisible in spreadsheet cell display but prevents execution.
     *
     * See: https://owasp.org/www-community/attacks/CSV_Injection
     */
    private String sanitize(String value) {
        if (value == null || value.isEmpty()) return value;
        if (FORMULA_INJECTION_CHARS.indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }
}
