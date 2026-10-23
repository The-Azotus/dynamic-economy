package dynamiceconomy.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small CSV reader and writer for catalog spreadsheets. Reading accepts what common spreadsheet programs save:
 * a UTF-8 byte-order mark, comma or semicolon delimiters (detected from the header), quoted fields containing
 * delimiters, quotes ({@code ""}) or line breaks, and CRLF or LF line endings.
 */
public final class Csv {
    private Csv() {
    }

    /** A parsed file: lower-cased header names in file order, and each data row keyed by header. */
    public record Table(List<String> headers, List<Row> rows, char delimiter) {
    }

    /** One data row. {@code line} is the 1-based line it starts on, for error messages. */
    public record Row(int line, Map<String, String> values) {
        /** The trimmed value of a column, or "" if the column is missing or the row is short. */
        public String get(String column) {
            String value = values.get(column);
            return value == null ? "" : value.trim();
        }
    }

    public static Table parse(String text) {
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        char delimiter = detectDelimiter(text);
        List<List<String>> records = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        int line = 1;
        int recordLine = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty() && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
            } else if (c == delimiter) {
                record.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (c == '\r') {
                // Part of a CRLF line ending; the '\n' ends the record.
            } else if (c == '\n') {
                record.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
                addRecord(records, lines, record, recordLine);
                record = new ArrayList<>();
                line++;
                recordLine = line;
            } else {
                field.append(c);
                fieldStarted = true;
            }
        }
        if (fieldStarted || !field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString());
            addRecord(records, lines, record, recordLine);
        }

        if (records.isEmpty()) {
            return new Table(List.of(), List.of(), delimiter);
        }
        List<String> headers = new ArrayList<>();
        for (String header : records.get(0)) {
            headers.add(header.trim().toLowerCase(Locale.ROOT));
        }
        List<Row> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> values = records.get(r);
            Map<String, String> byHeader = new LinkedHashMap<>();
            for (int c = 0; c < headers.size() && c < values.size(); c++) {
                byHeader.putIfAbsent(headers.get(c), values.get(c));
            }
            rows.add(new Row(lines.get(r), byHeader));
        }
        return new Table(headers, rows, delimiter);
    }

    /** Skips blank lines, so a trailing newline or an empty row in a spreadsheet doesn't become a data row. */
    private static void addRecord(List<List<String>> records, List<Integer> lines, List<String> record, int line) {
        boolean blank = record.stream().allMatch(String::isBlank);
        if (!blank) {
            records.add(record);
            lines.add(line);
        }
    }

    /** Whichever of comma and semicolon appears more often, outside quotes, on the header line. Ties pick comma. */
    static char detectDelimiter(String text) {
        int commas = 0;
        int semicolons = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                break;
            } else if (!quoted && c == ',') {
                commas++;
            } else if (!quoted && c == ';') {
                semicolons++;
            }
        }
        return semicolons > commas ? ';' : ',';
    }

    /** One CSV line with RFC 4180 quoting where needed, ending in CRLF as spreadsheets expect. */
    public static String line(List<String> fields) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String field = fields.get(i);
            if (field.indexOf(',') >= 0 || field.indexOf(';') >= 0 || field.indexOf('"') >= 0
                    || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0 || (!field.isEmpty()
                    && (Character.isWhitespace(field.charAt(0)) || Character.isWhitespace(field.charAt(field.length() - 1))))) {
                out.append('"').append(field.replace("\"", "\"\"")).append('"');
            } else {
                out.append(field);
            }
        }
        return out.append("\r\n").toString();
    }
}
