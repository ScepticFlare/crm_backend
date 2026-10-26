package com.compact.crm.service;

import com.compact.crm.dto.response.FullReportLeadRow;
import com.compact.crm.dto.response.FullReportResponse;
import com.compact.crm.dto.response.FullReportSummary;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FullReportExcelExportServiceTest {

    @Test
    void leadSheet_keepsPhonesAsText_writesRealDates_andSizesColumnsToTheData() throws Exception {

        FullReportLeadRow row = FullReportLeadRow.builder()
                .leadId(7L)
                .companyName("Shree Ganesh Industrial Batteries & Power Systems Private Limited")
                .phone("00441234567890123456")
                .alternatePhone("+919876543210")
                .createdAt(LocalDateTime.of(2026, 4, 2, 9, 30))
                .build();

        FullReportResponse report = FullReportResponse.builder()
                .summary(FullReportSummary.builder().productTotals(List.of()).batteryTotals(List.of()).build())
                .leads(List.of(row)).opportunities(List.of()).customers(List.of())
                .productLineItems(List.of()).batteryLineItems(List.of())
                .build();

        byte[] xlsx = new FullReportExcelExportService().export(report, Set.of("LEAD"), FullReportService.ALL_SECTIONS);

        XSSFSheet sheet = new XSSFWorkbook(new ByteArrayInputStream(xlsx)).getSheet("Leads");

        int phoneCol = -1;
        int altCol = -1;
        int createdCol = -1;
        int companyCol = -1;

        for (Cell h : sheet.getRow(2)) {
            switch (h.getStringCellValue()) {
                case "Phone" -> phoneCol = h.getColumnIndex();
                case "Alternate Phone" -> altCol = h.getColumnIndex();
                case "Created Date", "Created At" -> createdCol = h.getColumnIndex();
                case "Company Name" -> companyCol = h.getColumnIndex();
                default -> { }
            }
        }

        assertThat(phoneCol).isGreaterThanOrEqualTo(0);

        Cell phone = sheet.getRow(3).getCell(phoneCol);
        assertThat(phone.getCellType()).isEqualTo(CellType.STRING);
        assertThat(phone.getStringCellValue()).isEqualTo("00441234567890123456");
        assertThat(phone.getCellStyle().getDataFormatString()).isEqualTo("@");
        assertThat(sheet.getColumnWidth(phoneCol)).isGreaterThanOrEqualTo(20 * 256);

        assertThat(sheet.getRow(3).getCell(altCol).getStringCellValue()).isEqualTo("+919876543210");

        if (createdCol >= 0) {
            Cell created = sheet.getRow(3).getCell(createdCol);
            assertThat(DateUtil.isCellDateFormatted(created)).isTrue();
            assertThat(created.getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 4, 2, 9, 30));
            assertThat(sheet.getColumnWidth(createdCol)).isGreaterThanOrEqualTo(18 * 256);
        }

        assertThat(sheet.getColumnWidth(companyCol)).isGreaterThanOrEqualTo(60 * 256);
    }
}
