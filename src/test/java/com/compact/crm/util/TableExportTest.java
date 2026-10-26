package com.compact.crm.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableExportTest {

    private record Row(String phone, String code, Long count, Double value, LocalDate day,
                       LocalDateTime stamp, String company) {
    }

    private static final String LONG_NAME = "Shree Ganesh Industrial Batteries & Power Systems Private Limited";

    private static final List<TableExport.Column<Row>> COLUMNS = List.of(
            TableExport.Column.text("Phone", Row::phone),
            TableExport.Column.text("Code", Row::code),
            TableExport.Column.integer("Count", Row::count),
            TableExport.Column.decimal("Value", Row::value),
            TableExport.Column.date("Closing Date", Row::day),
            TableExport.Column.dateTime("Created Date", Row::stamp),
            TableExport.Column.text("Company", Row::company)
    );

    private static final List<Row> ROWS = List.of(
            new Row("9876543210", "007", 5L, 1234567.5, LocalDate.of(2026, 3, 9),
                    LocalDateTime.of(2026, 3, 9, 14, 5), LONG_NAME),
            new Row("+919876543210123", "0044", 12L, 0.0, LocalDate.of(2027, 12, 31),
                    LocalDateTime.of(2027, 12, 31, 23, 59), "ABC"),
            new Row("00441234567890123456", null, null, null, null, null, ""));

    private XSSFSheet sheet(byte[] xlsx) throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx));
        return wb.getSheetAt(0);
    }

    @Test
    void phoneNumbers_areTextCellsKeepingLeadingZeroesAndPrefixes() throws Exception {

        XSSFSheet sheet = sheet(TableExport.toXlsx("Leads", COLUMNS, ROWS));

        Cell plain = sheet.getRow(1).getCell(0);
        Cell plus = sheet.getRow(2).getCell(0);
        Cell zeroes = sheet.getRow(3).getCell(0);

        for (Cell cell : List.of(plain, plus, zeroes)) {
            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getCellStyle().getDataFormatString()).isEqualTo("@");
        }

        assertThat(plain.getStringCellValue()).isEqualTo("9876543210");
        assertThat(plus.getStringCellValue()).isEqualTo("+919876543210123");
        assertThat(zeroes.getStringCellValue()).isEqualTo("00441234567890123456");

        // a text identifier with leading zeroes is also preserved
        assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("007");
    }

    @Test
    void numbersAndDates_areRealTypedCellsWithFormats() throws Exception {

        XSSFSheet sheet = sheet(TableExport.toXlsx("Leads", COLUMNS, ROWS));

        Cell count = sheet.getRow(1).getCell(2);
        Cell value = sheet.getRow(1).getCell(3);
        Cell day = sheet.getRow(1).getCell(4);
        Cell stamp = sheet.getRow(1).getCell(5);

        assertThat(count.getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(count.getNumericCellValue()).isEqualTo(5.0);

        assertThat(value.getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(value.getNumericCellValue()).isEqualTo(1234567.5);
        assertThat(value.getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");

        assertThat(DateUtil.isCellDateFormatted(day)).isTrue();
        assertThat(day.getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 9));
        assertThat(day.getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd");

        assertThat(DateUtil.isCellDateFormatted(stamp)).isTrue();
        assertThat(stamp.getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 3, 9, 14, 5));
        assertThat(stamp.getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd hh:mm");
    }

    @Test
    void emptyValues_areBlankCells_notZeroOrEmptyText() throws Exception {

        XSSFSheet sheet = sheet(TableExport.toXlsx("Leads", COLUMNS, ROWS));

        for (int c = 1; c <= 6; c++) {
            Cell cell = sheet.getRow(3).getCell(c);
            assertThat(cell == null || cell.getCellType() == CellType.BLANK)
                    .as("column %d of the empty row", c).isTrue();
        }
    }

    @Test
    void columnWidths_fitTheContent_soNothingShowsAsHashes() throws Exception {

        XSSFSheet sheet = sheet(TableExport.toXlsx("Leads", COLUMNS, ROWS));

        // 1 width unit = 1/256 of a character
        assertThat(sheet.getColumnWidth(0)).isGreaterThanOrEqualTo(20 * 256);   // 20-digit phone
        assertThat(sheet.getColumnWidth(3)).isGreaterThanOrEqualTo(12 * 256);   // 1,234,567.50
        assertThat(sheet.getColumnWidth(4)).isGreaterThanOrEqualTo(12 * 256);   // yyyy-mm-dd
        assertThat(sheet.getColumnWidth(5)).isGreaterThanOrEqualTo(18 * 256);   // yyyy-mm-dd hh:mm
        assertThat(sheet.getColumnWidth(6)).isGreaterThanOrEqualTo(LONG_NAME.length() * 256);
        // a very long value is capped rather than producing an absurd column
        assertThat(sheet.getColumnWidth(6)).isLessThanOrEqualTo(90 * 256);
    }

    @Test
    void headers_areReadable_andTheSheetIsFilterableWithAFrozenHeader() throws Exception {

        XSSFSheet sheet = sheet(TableExport.toXlsx("Leads", COLUMNS, ROWS));

        assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Phone");
        assertThat(sheet.getRow(0).getCell(5).getStringCellValue()).isEqualTo("Created Date");
        assertThat(sheet.getWorkbook().getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
        assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
        assertThat(sheet.getPaneInformation()).isNotNull();
        assertThat(sheet.getPaneInformation().getHorizontalSplitTopRow()).isEqualTo((short) 1);
        assertThat(sheet.getLastRowNum()).isEqualTo(3);
    }

    @Test
    void response_defaultsToXlsx_andCsvIsOptIn() {

        ResponseEntity<byte[]> xlsx = TableExport.response("leads", null, "Leads", COLUMNS, ROWS);
        ResponseEntity<byte[]> csv = TableExport.response("leads", "CSV", "Leads", COLUMNS, ROWS);

        assertThat(xlsx.getHeaders().getContentType().toString()).isEqualTo(TableExport.XLSX_TYPE);
        assertThat(xlsx.getHeaders().getContentDisposition().getFilename()).isEqualTo("leads.xlsx");
        assertThat(csv.getHeaders().getContentDisposition().getFilename()).isEqualTo("leads.csv");
    }

    @Test
    void csv_hasBom_escaping_textPhones_andNoScientificNotation() {

        List<Row> rows = List.of(
                new Row("00441234567890123456", "007", 1234567890123L, 12345678901.5,
                        LocalDate.of(2026, 3, 9), LocalDateTime.of(2026, 3, 9, 14, 5, 7),
                        "Acme, \"Best\" Ltd"));

        byte[] bytes = TableExport.toCsv(COLUMNS, rows);

        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);

        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");

        assertThat(lines[0]).isEqualTo("Phone,Code,Count,Value,Closing Date,Created Date,Company");
        assertThat(lines[1]).isEqualTo(
                "00441234567890123456,007,1234567890123,12345678901.5,2026-03-09,2026-03-09 14:05:07,\"Acme, \"\"Best\"\" Ltd\"");
        assertThat(csv).doesNotContain("E+").doesNotContain("E1");
    }
}
