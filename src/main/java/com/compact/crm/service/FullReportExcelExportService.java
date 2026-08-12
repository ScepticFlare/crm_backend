package com.compact.crm.service;

import com.compact.crm.dto.response.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

@Service
public class FullReportExcelExportService {

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private record ColumnSpec<T>(String header, Function<T, Object> valueFn) {
    }

    public byte[] export(
            FullReportResponse report,
            Set<String> recordTypes,
            Set<String> sections
    ) throws IOException {

        Workbook workbook = new XSSFWorkbook();

        Styles styles = new Styles(workbook);

        writeSummarySheet(workbook, styles, report.getSummary());

        if (recordTypes.contains("LEAD")) {
            writeSheet(
                    workbook, styles, "Leads",
                    leadColumns(sections), report.getLeads()
            );
        }

        if (recordTypes.contains("OPPORTUNITY")) {
            writeSheet(
                    workbook, styles, "Opportunities",
                    opportunityColumns(sections), report.getOpportunities()
            );
        }

        if (recordTypes.contains("CUSTOMER")) {
            writeSheet(
                    workbook, styles, "Customers",
                    customerColumns(sections), report.getCustomers()
            );
        }

        if (sections.contains("PRODUCT_INFO")) {
            writeSheet(
                    workbook, styles, "Product Line Items",
                    productLineItemColumns(), report.getProductLineItems()
            );
        }

        if (sections.contains("BATTERY_INFO")) {
            writeSheet(
                    workbook, styles, "Battery Line Items",
                    batteryLineItemColumns(), report.getBatteryLineItems()
            );
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        workbook.write(out);
        workbook.close();

        return out.toByteArray();
    }

    // ---------------- COLUMN DEFINITIONS ----------------

    private List<ColumnSpec<FullReportLeadRow>> leadColumns(Set<String> sections) {

        List<ColumnSpec<FullReportLeadRow>> cols = new ArrayList<>();

        cols.add(new ColumnSpec<>("Lead ID", FullReportLeadRow::getLeadId));
        cols.add(new ColumnSpec<>("Company Name", FullReportLeadRow::getCompanyName));
        cols.add(new ColumnSpec<>("Contact Person", FullReportLeadRow::getContactPerson));

        if (sections.contains("LEAD_INFO")) {
            cols.add(new ColumnSpec<>("Designation", FullReportLeadRow::getDesignation));
            cols.add(new ColumnSpec<>("Phone", FullReportLeadRow::getPhone));
            cols.add(new ColumnSpec<>("Alternate Phone", FullReportLeadRow::getAlternatePhone));
            cols.add(new ColumnSpec<>("Email", FullReportLeadRow::getEmail));
            cols.add(new ColumnSpec<>("Secondary Email", FullReportLeadRow::getSecondaryEmail));
            cols.add(new ColumnSpec<>("City", FullReportLeadRow::getCity));
            cols.add(new ColumnSpec<>("State", FullReportLeadRow::getState));
            cols.add(new ColumnSpec<>("Pincode", FullReportLeadRow::getPincode));
            cols.add(new ColumnSpec<>("Industry", FullReportLeadRow::getIndustry));
            cols.add(new ColumnSpec<>("Lead Source", FullReportLeadRow::getLeadSource));
            cols.add(new ColumnSpec<>("Lead Status", FullReportLeadRow::getLeadStatus));
            cols.add(new ColumnSpec<>("Validity", FullReportLeadRow::getLeadValidity));
            cols.add(new ColumnSpec<>("Description", FullReportLeadRow::getDescription));
            cols.add(new ColumnSpec<>("Final Remarks", FullReportLeadRow::getFinalRemarks));
        }

        if (sections.contains("EMPLOYEE_INFO")) {
            cols.add(new ColumnSpec<>("Assigned Employee", FullReportLeadRow::getAssignedEmployeeName));
            cols.add(new ColumnSpec<>("Employee Email", FullReportLeadRow::getAssignedEmployeeEmail));
        }

        cols.add(new ColumnSpec<>("Created At", FullReportLeadRow::getCreatedAt));

        if (sections.contains("PRODUCT_INFO")) {
            cols.add(new ColumnSpec<>("Products", FullReportLeadRow::getProducts));
        }

        if (sections.contains("BATTERY_INFO")) {
            cols.add(new ColumnSpec<>("Batteries", FullReportLeadRow::getBatteries));
        }

        return cols;
    }

    private List<ColumnSpec<FullReportOpportunityRow>> opportunityColumns(Set<String> sections) {

        List<ColumnSpec<FullReportOpportunityRow>> cols = new ArrayList<>();

        cols.add(new ColumnSpec<>("Opportunity ID", FullReportOpportunityRow::getOpportunityId));
        cols.add(new ColumnSpec<>("Title", FullReportOpportunityRow::getTitle));
        cols.add(new ColumnSpec<>("Sales Stage", FullReportOpportunityRow::getSalesStage));
        cols.add(new ColumnSpec<>("Company Name", FullReportOpportunityRow::getCompanyName));
        cols.add(new ColumnSpec<>("Contact Person", FullReportOpportunityRow::getContactPerson));

        if (sections.contains("OPPORTUNITY_INFO")) {
            cols.add(new ColumnSpec<>("Product Value", FullReportOpportunityRow::getProductValue));
            cols.add(new ColumnSpec<>("Expected Closing Date", FullReportOpportunityRow::getExpectedClosingDate));
            cols.add(new ColumnSpec<>("Validity", FullReportOpportunityRow::getLeadValidity));
        }

        if (sections.contains("LEAD_INFO")) {
            cols.add(new ColumnSpec<>("Lead ID", FullReportOpportunityRow::getLeadId));
            cols.add(new ColumnSpec<>("Phone", FullReportOpportunityRow::getPhone));
            cols.add(new ColumnSpec<>("Email", FullReportOpportunityRow::getEmail));
            cols.add(new ColumnSpec<>("Industry", FullReportOpportunityRow::getIndustry));
            cols.add(new ColumnSpec<>("Lead Source", FullReportOpportunityRow::getLeadSource));
        }

        if (sections.contains("EMPLOYEE_INFO")) {
            cols.add(new ColumnSpec<>("Assigned Employee", FullReportOpportunityRow::getAssignedEmployeeName));
            cols.add(new ColumnSpec<>("Employee Email", FullReportOpportunityRow::getAssignedEmployeeEmail));
        }

        cols.add(new ColumnSpec<>("Created At", FullReportOpportunityRow::getCreatedAt));

        if (sections.contains("PRODUCT_INFO")) {
            cols.add(new ColumnSpec<>("Products", FullReportOpportunityRow::getProducts));
        }

        if (sections.contains("BATTERY_INFO")) {
            cols.add(new ColumnSpec<>("Batteries", FullReportOpportunityRow::getBatteries));
        }

        return cols;
    }

    private List<ColumnSpec<FullReportCustomerRow>> customerColumns(Set<String> sections) {

        List<ColumnSpec<FullReportCustomerRow>> cols = new ArrayList<>();

        cols.add(new ColumnSpec<>("Customer ID", FullReportCustomerRow::getCustomerId));
        cols.add(new ColumnSpec<>("Customer Code", FullReportCustomerRow::getCustomerCode));
        cols.add(new ColumnSpec<>("Company Name", FullReportCustomerRow::getCompanyName));
        cols.add(new ColumnSpec<>("Contact Person", FullReportCustomerRow::getContactPerson));

        if (sections.contains("CUSTOMER_INFO")) {
            cols.add(new ColumnSpec<>("Designation", FullReportCustomerRow::getDesignation));
            cols.add(new ColumnSpec<>("Phone", FullReportCustomerRow::getPhone));
            cols.add(new ColumnSpec<>("Alternate Phone", FullReportCustomerRow::getAlternatePhone));
            cols.add(new ColumnSpec<>("Email", FullReportCustomerRow::getEmail));
            cols.add(new ColumnSpec<>("Secondary Email", FullReportCustomerRow::getSecondaryEmail));
            cols.add(new ColumnSpec<>("Website", FullReportCustomerRow::getWebsite));
            cols.add(new ColumnSpec<>("City", FullReportCustomerRow::getCity));
            cols.add(new ColumnSpec<>("State", FullReportCustomerRow::getState));
            cols.add(new ColumnSpec<>("Pincode", FullReportCustomerRow::getPincode));
            cols.add(new ColumnSpec<>("Billing Address", FullReportCustomerRow::getBillingAddress));
            cols.add(new ColumnSpec<>("Shipping Address", FullReportCustomerRow::getShippingAddress));
            cols.add(new ColumnSpec<>("GST Number", FullReportCustomerRow::getGstNumber));
        }

        if (sections.contains("EMPLOYEE_INFO")) {
            cols.add(new ColumnSpec<>("Assigned Employee", FullReportCustomerRow::getAssignedEmployeeName));
            cols.add(new ColumnSpec<>("Employee Email", FullReportCustomerRow::getAssignedEmployeeEmail));
        }

        cols.add(new ColumnSpec<>("Created At", FullReportCustomerRow::getCreatedAt));

        if (sections.contains("OPPORTUNITY_INFO")) {
            cols.add(new ColumnSpec<>("Opportunity ID", FullReportCustomerRow::getOpportunityId));
            cols.add(new ColumnSpec<>("Opportunity Title", FullReportCustomerRow::getOpportunityTitle));
            cols.add(new ColumnSpec<>("Sales Stage", FullReportCustomerRow::getSalesStage));
            cols.add(new ColumnSpec<>("Product Value", FullReportCustomerRow::getProductValue));
        }

        if (sections.contains("LEAD_INFO")) {
            cols.add(new ColumnSpec<>("Lead ID", FullReportCustomerRow::getLeadId));
            cols.add(new ColumnSpec<>("Industry", FullReportCustomerRow::getIndustry));
            cols.add(new ColumnSpec<>("Lead Source", FullReportCustomerRow::getLeadSource));
        }

        if (sections.contains("PRODUCT_INFO")) {
            cols.add(new ColumnSpec<>("Products", FullReportCustomerRow::getProducts));
        }

        if (sections.contains("BATTERY_INFO")) {
            cols.add(new ColumnSpec<>("Batteries", FullReportCustomerRow::getBatteries));
        }

        return cols;
    }

    private List<ColumnSpec<FullReportProductLineItem>> productLineItemColumns() {

        return List.of(
                new ColumnSpec<>("Lead ID", FullReportProductLineItem::getLeadId),
                new ColumnSpec<>("Company Name", FullReportProductLineItem::getCompanyName),
                new ColumnSpec<>("Contact Person", FullReportProductLineItem::getContactPerson),
                new ColumnSpec<>("Product", FullReportProductLineItem::getProductName),
                new ColumnSpec<>("Quantity", FullReportProductLineItem::getQuantity)
        );
    }

    private List<ColumnSpec<FullReportBatteryLineItem>> batteryLineItemColumns() {

        return List.of(
                new ColumnSpec<>("Lead ID", FullReportBatteryLineItem::getLeadId),
                new ColumnSpec<>("Company Name", FullReportBatteryLineItem::getCompanyName),
                new ColumnSpec<>("Contact Person", FullReportBatteryLineItem::getContactPerson),
                new ColumnSpec<>("Battery", FullReportBatteryLineItem::getBatteryName),
                new ColumnSpec<>("Quantity", FullReportBatteryLineItem::getQuantity)
        );
    }

    // ---------------- SHEET WRITERS ----------------

    private <T> void writeSheet(
            Workbook workbook,
            Styles styles,
            String sheetName,
            List<ColumnSpec<T>> columns,
            List<T> rows
    ) {

        Sheet sheet = workbook.createSheet(sheetName);

        int lastCol = columns.size() - 1;

        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, Math.max(lastCol, 0)));

        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(sheetName.toUpperCase());
        titleCell.setCellStyle(styles.title);

        Row headerRow = sheet.createRow(2);

        for (int c = 0; c < columns.size(); c++) {

            Cell cell = headerRow.createCell(c);
            cell.setCellValue(columns.get(c).header());
            cell.setCellStyle(styles.header);

            int width = Math.min(15000, Math.max(3000, (columns.get(c).header().length() + 6) * 256));
            sheet.setColumnWidth(c, width);
        }

        int rowNum = 3;

        for (T row : rows) {

            Row excelRow = sheet.createRow(rowNum++);

            for (int c = 0; c < columns.size(); c++) {

                Cell cell = excelRow.createCell(c);
                cell.setCellStyle(styles.data);

                setCellValue(cell, columns.get(c).valueFn().apply(row));
            }
        }

        if (!columns.isEmpty()) {
            sheet.setAutoFilter(new CellRangeAddress(2, 2, 0, lastCol));
        }

        sheet.createFreezePane(0, 3);
    }

    private void writeSummarySheet(Workbook workbook, Styles styles, FullReportSummary summary) {

        Sheet sheet = workbook.createSheet("Summary");

        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));

        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("FULL REPORT SUMMARY");
        titleCell.setCellStyle(styles.title);

        int rowNum = 2;

        rowNum = writeSummaryRow(sheet, styles, rowNum, "Total Leads", summary.getTotalLeads());
        rowNum = writeSummaryRow(sheet, styles, rowNum, "Valid Leads", summary.getValidLeads());
        rowNum = writeSummaryRow(sheet, styles, rowNum, "Invalid Leads", summary.getInvalidLeads());
        rowNum = writeSummaryRow(sheet, styles, rowNum, "Total Opportunities", summary.getTotalOpportunities());
        rowNum = writeSummaryRow(sheet, styles, rowNum, "Won", summary.getWon());
        rowNum = writeSummaryRow(sheet, styles, rowNum, "Lost", summary.getLost());
        rowNum = writeSummaryRow(sheet, styles, rowNum, "In Progress", summary.getInProgress());
        rowNum = writeSummaryRow(sheet, styles, rowNum, "Postponed", summary.getPostponed());
        rowNum = writeSummaryRow(sheet, styles, rowNum, "Dropped", summary.getDropped());
        rowNum = writeSummaryRow(sheet, styles, rowNum, "Unresponsive", summary.getUnresponsive());
        rowNum = writeSummaryRow(sheet, styles, rowNum, "Total Customers", summary.getTotalCustomers());

        rowNum++;

        rowNum = writeItemTotalsTable(sheet, styles, rowNum, "Product Totals", "Product", summary.getProductTotals());

        rowNum++;

        writeItemTotalsTable(sheet, styles, rowNum, "Battery Totals", "Battery", summary.getBatteryTotals());

        sheet.setColumnWidth(0, 7000);
        sheet.setColumnWidth(1, 4000);
    }

    private int writeSummaryRow(Sheet sheet, Styles styles, int rowNum, String label, long value) {

        Row row = sheet.createRow(rowNum);

        Cell labelCell = row.createCell(0);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(styles.header);

        Cell valueCell = row.createCell(1);
        valueCell.setCellValue(value);
        valueCell.setCellStyle(styles.data);

        return rowNum + 1;
    }

    private int writeItemTotalsTable(
            Sheet sheet,
            Styles styles,
            int rowNum,
            String tableTitle,
            String nameHeader,
            List<FullReportItemTotal> totals
    ) {

        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(tableTitle);
        titleCell.setCellStyle(styles.title);

        Row headerRow = sheet.createRow(rowNum++);

        Cell nameHeaderCell = headerRow.createCell(0);
        nameHeaderCell.setCellValue(nameHeader);
        nameHeaderCell.setCellStyle(styles.header);

        Cell qtyHeaderCell = headerRow.createCell(1);
        qtyHeaderCell.setCellValue("Total Quantity");
        qtyHeaderCell.setCellStyle(styles.header);

        if (totals.isEmpty()) {

            Row emptyRow = sheet.createRow(rowNum++);
            Cell emptyCell = emptyRow.createCell(0);
            emptyCell.setCellValue("No data");
            emptyCell.setCellStyle(styles.data);

            return rowNum;
        }

        for (FullReportItemTotal total : totals) {

            Row row = sheet.createRow(rowNum++);

            Cell nameCell = row.createCell(0);
            nameCell.setCellValue(total.getName());
            nameCell.setCellStyle(styles.data);

            Cell qtyCell = row.createCell(1);
            qtyCell.setCellValue(total.getTotalQuantity());
            qtyCell.setCellStyle(styles.data);
        }

        return rowNum;
    }

    private void setCellValue(Cell cell, Object value) {

        if (value == null) {
            return;
        }

        switch (value) {

            case String s -> cell.setCellValue(s);

            case java.time.LocalDateTime ldt -> cell.setCellValue(ldt.format(DATE_TIME_FORMAT));

            case java.time.LocalDate ld -> cell.setCellValue(ld.format(DATE_FORMAT));

            case Number n -> cell.setCellValue(n.doubleValue());

            default -> cell.setCellValue(value.toString());
        }
    }

    // ---------------- STYLES ----------------

    private static class Styles {

        final CellStyle title;
        final CellStyle header;
        final CellStyle data;

        Styles(Workbook workbook) {

            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);

            title = workbook.createCellStyle();
            title.setFont(titleFont);
            title.setAlignment(HorizontalAlignment.LEFT);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);

            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderTop(BorderStyle.THIN);
            header.setBorderBottom(BorderStyle.THIN);
            header.setBorderLeft(BorderStyle.THIN);
            header.setBorderRight(BorderStyle.THIN);

            data = workbook.createCellStyle();
            data.setAlignment(HorizontalAlignment.LEFT);
            data.setVerticalAlignment(VerticalAlignment.CENTER);
            data.setBorderTop(BorderStyle.THIN);
            data.setBorderBottom(BorderStyle.THIN);
            data.setBorderLeft(BorderStyle.THIN);
            data.setBorderRight(BorderStyle.THIN);
        }
    }
}
