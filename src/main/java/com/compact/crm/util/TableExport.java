package com.compact.crm.util;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * One column spec -> one export. Renders the same rows as an XLSX workbook
 * (the default) or as CSV.
 *
 * XLSX: TEXT cells (phone numbers, codes) are written as strings with the
 * "@" text format, so Excel never turns 9876543210 / 0044... / +91... into
 * 9.88E+09; dates and timestamps are real date cells with a date format;
 * money/number columns are real numbers with a number format; column widths
 * are sized from the content so nothing shows as ####.
 *
 * CSV: UTF-8 with a BOM (so Excel decodes it correctly) and plain-decimal
 * numbers. A CSV file carries no cell types, so Excel still guesses on open
 * and can show long digit strings in scientific notation - that cannot be
 * fixed from the file; use the XLSX export for analysis.
 */
public final class TableExport {

    public static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter CSV_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int MIN_WIDTH_CHARS = 8;
    private static final int MAX_WIDTH_CHARS = 80;

    private TableExport() {
    }

    public enum Type {
        // Any identifier or free text, including phone numbers: never numeric.
        TEXT,
        INTEGER,
        // Money / metric with two decimals and thousands separators.
        DECIMAL,
        DATE,
        DATETIME
    }

    public record Column<T>(String header, Type type, Function<T, Object> value) {

        public static <T> Column<T> text(String header, Function<T, ?> value) {
            return new Column<>(header, Type.TEXT, row -> value.apply(row));
        }

        public static <T> Column<T> integer(String header, Function<T, ?> value) {
            return new Column<>(header, Type.INTEGER, row -> value.apply(row));
        }

        public static <T> Column<T> decimal(String header, Function<T, ?> value) {
            return new Column<>(header, Type.DECIMAL, row -> value.apply(row));
        }

        public static <T> Column<T> date(String header, Function<T, ?> value) {
            return new Column<>(header, Type.DATE, row -> value.apply(row));
        }

        public static <T> Column<T> dateTime(String header, Function<T, ?> value) {
            return new Column<>(header, Type.DATETIME, row -> value.apply(row));
        }
    }

    public static boolean isCsv(String format) {
        return format != null && format.trim().equalsIgnoreCase("csv");
    }

    // Builds the download response. `format` is "csv" or anything else
    // (xlsx is the default); `baseName` has no extension.
    public static <T> ResponseEntity<byte[]> response(
            String baseName, String format, String sheetName, List<Column<T>> columns, List<T> rows) {

        HttpHeaders headers = new HttpHeaders();

        byte[] body;

        if (isCsv(format)) {
            body = toCsv(columns, rows);
            headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
            headers.setContentDisposition(
                    ContentDisposition.attachment().filename(baseName + ".csv", StandardCharsets.UTF_8).build());
        } else {
            body = toXlsx(sheetName, columns, rows);
            headers.setContentType(MediaType.parseMediaType(XLSX_TYPE));
            headers.setContentDisposition(
                    ContentDisposition.attachment().filename(baseName + ".xlsx", StandardCharsets.UTF_8).build());
        }

        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    // ---------------- XLSX ----------------

    public static <T> byte[] toXlsx(String sheetName, List<Column<T>> columns, List<T> rows) {

        try (Workbook workbook = new XSSFWorkbook()) {

            Sheet sheet = workbook.createSheet(sheetName);

            Font bold = workbook.createFont();
            bold.setBold(true);

            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            header.setAlignment(HorizontalAlignment.LEFT);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);

            var formats = workbook.createDataFormat();

            CellStyle text = workbook.createCellStyle();
            text.setDataFormat(formats.getFormat("@"));

            CellStyle integer = workbook.createCellStyle();
            integer.setDataFormat(formats.getFormat("0"));

            CellStyle decimal = workbook.createCellStyle();
            decimal.setDataFormat(formats.getFormat("#,##0.00"));

            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(formats.getFormat("yyyy-mm-dd"));
            date.setAlignment(HorizontalAlignment.LEFT);

            CellStyle dateTime = workbook.createCellStyle();
            dateTime.setDataFormat(formats.getFormat("yyyy-mm-dd hh:mm"));
            dateTime.setAlignment(HorizontalAlignment.LEFT);

            int[] widthChars = new int[columns.size()];

            Row headerRow = sheet.createRow(0);

            for (int c = 0; c < columns.size(); c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(columns.get(c).header());
                cell.setCellStyle(header);
                widthChars[c] = columns.get(c).header().length() + 3; // room for the filter arrow
            }

            int rowNum = 1;

            for (T item : rows) {

                Row row = sheet.createRow(rowNum++);

                for (int c = 0; c < columns.size(); c++) {

                    Column<T> column = columns.get(c);
                    Object value = column.value().apply(item);

                    if (value == null || (value instanceof String s && s.isEmpty())) {
                        continue;
                    }

                    Cell cell = row.createCell(c);

                    switch (column.type()) {

                        case TEXT -> {
                            String s = value.toString();
                            cell.setCellValue(s);
                            cell.setCellStyle(text);
                            widthChars[c] = Math.max(widthChars[c], longestLine(s));
                        }

                        case INTEGER -> {
                            Number n = (Number) value;
                            cell.setCellValue(n.doubleValue());
                            cell.setCellStyle(integer);
                            widthChars[c] = Math.max(widthChars[c], String.valueOf(n.longValue()).length() + 1);
                        }

                        case DECIMAL -> {
                            Number n = (Number) value;
                            cell.setCellValue(n.doubleValue());
                            cell.setCellStyle(decimal);
                            widthChars[c] = Math.max(widthChars[c],
                                    String.format("%,.2f", n.doubleValue()).length() + 2);
                        }

                        case DATE -> {
                            cell.setCellValue((LocalDate) value);
                            cell.setCellStyle(date);
                            widthChars[c] = Math.max(widthChars[c], 12);
                        }

                        case DATETIME -> {
                            cell.setCellValue((LocalDateTime) value);
                            cell.setCellStyle(dateTime);
                            widthChars[c] = Math.max(widthChars[c], 18);
                        }
                    }
                }
            }

            for (int c = 0; c < columns.size(); c++) {
                int chars = Math.min(MAX_WIDTH_CHARS, Math.max(MIN_WIDTH_CHARS, widthChars[c]));
                sheet.setColumnWidth(c, (chars + 2) * 256);
            }

            if (!columns.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowNum - 1), 0, columns.size() - 1));
            }

            sheet.createFreezePane(0, 1);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int longestLine(String s) {

        int longest = 0;

        for (String line : s.split("\\R", -1)) {
            longest = Math.max(longest, line.length());
        }

        return longest + 1;
    }

    // ---------------- CSV ----------------

    public static <T> byte[] toCsv(List<Column<T>> columns, List<T> rows) {

        List<String> headers = new ArrayList<>();
        columns.forEach(c -> headers.add(c.header()));

        List<List<String>> out = new ArrayList<>();

        for (T item : rows) {

            List<String> line = new ArrayList<>();

            for (Column<T> column : columns) {
                line.add(csvValue(column.value().apply(item)));
            }

            out.add(line);
        }

        return CsvWriter.toUtf8BytesWithBom(CsvWriter.write(headers, out));
    }

    private static String csvValue(Object value) {

        if (value == null) {
            return "";
        }

        return switch (value) {
            case LocalDate d -> d.format(CSV_DATE);
            case LocalDateTime d -> d.format(CSV_DATETIME);
            case Double d -> BigDecimal.valueOf(d).toPlainString();
            case Float f -> new BigDecimal(f.toString()).toPlainString();
            case BigDecimal b -> b.toPlainString();
            default -> value.toString();
        };
    }
}
