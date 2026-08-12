package com.compact.crm.service;

import com.compact.crm.dto.response.LeadReportResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Service
public class ExcelExportService {

    public byte[] exportLeadReport(List<LeadReportResponse> report) throws IOException {

        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Lead Report");

        //---------------- TITLE STYLE ----------------

        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short)16);

        CellStyle titleStyle = workbook.createCellStyle();
        titleStyle.setFont(titleFont);
        titleStyle.setAlignment(HorizontalAlignment.CENTER);
        titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        //---------------- HEADER STYLE ----------------

        Font headerFont = workbook.createFont();
        headerFont.setBold(true);

        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(headerFont);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        headerStyle.setBorderTop(BorderStyle.THIN);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setBorderLeft(BorderStyle.THIN);
        headerStyle.setBorderRight(BorderStyle.THIN);

        //---------------- DATA STYLE ----------------

        CellStyle dataStyle = workbook.createCellStyle();
        dataStyle.setAlignment(HorizontalAlignment.CENTER);
        dataStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        dataStyle.setBorderTop(BorderStyle.THIN);
        dataStyle.setBorderBottom(BorderStyle.THIN);
        dataStyle.setBorderLeft(BorderStyle.THIN);
        dataStyle.setBorderRight(BorderStyle.THIN);

        //---------------- PERCENT STYLE ----------------

        CellStyle percentStyle = workbook.createCellStyle();
        percentStyle.cloneStyleFrom(dataStyle);
        percentStyle.setDataFormat(
                workbook.createDataFormat().getFormat("0.00%")
        );

        //---------------- TITLE ----------------

        sheet.addMergedRegion(new CellRangeAddress(0,0,0,10));

        Row titleRow = sheet.createRow(0);

        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("LEAD REPORT");
        titleCell.setCellStyle(titleStyle);

        //---------------- HEADER ROW 1 ----------------

        Row row1 = sheet.createRow(2);

        row1.createCell(0).setCellValue("Month");
        row1.createCell(1).setCellValue("Total Leads Recd");
        row1.createCell(2).setCellValue("Invalid Leads");

        row1.createCell(3).setCellValue("Valid Leads Breakup");

        sheet.addMergedRegion(new CellRangeAddress(2,2,3,10));

        //---------------- HEADER ROW 2 ----------------

        Row row2 = sheet.createRow(3);

        row2.createCell(3).setCellValue("Total");
        row2.createCell(4).setCellValue("Won");
        row2.createCell(5).setCellValue("Lost");
        row2.createCell(6).setCellValue("In Progress");
        row2.createCell(7).setCellValue("Postponed");
        row2.createCell(8).setCellValue("Dropped");
        row2.createCell(9).setCellValue("Unresponsive");
        row2.createCell(10).setCellValue("Won %");

        //---------------- STYLE HEADERS ----------------

        for(int c=0;c<=10;c++)
        {
            if(row1.getCell(c)!=null)
                row1.getCell(c).setCellStyle(headerStyle);

            if(row2.getCell(c)!=null)
                row2.getCell(c).setCellStyle(headerStyle);
        }

        //---------------- DATA ----------------

        int rowNum = 4;

        for(LeadReportResponse r : report)
        {
            Row row = sheet.createRow(rowNum++);

            Cell c0 = row.createCell(0);
            c0.setCellValue(r.getMonth());
            c0.setCellStyle(dataStyle);

            Cell c1 = row.createCell(1);
            c1.setCellValue(r.getTotalLeadsReceived());
            c1.setCellStyle(dataStyle);

            Cell c2 = row.createCell(2);
            c2.setCellValue(r.getInvalidLeads());
            c2.setCellStyle(dataStyle);

            Cell c3 = row.createCell(3);
            c3.setCellValue(r.getValidLeads());
            c3.setCellStyle(dataStyle);

            Cell c4 = row.createCell(4);
            c4.setCellValue(r.getWon());
            c4.setCellStyle(dataStyle);

            Cell c5 = row.createCell(5);
            c5.setCellValue(r.getLost());
            c5.setCellStyle(dataStyle);

            Cell c6 = row.createCell(6);
            c6.setCellValue(r.getInProgress());
            c6.setCellStyle(dataStyle);

            Cell c7 = row.createCell(7);
            c7.setCellValue(r.getPostponed());
            c7.setCellStyle(dataStyle);

            Cell c8 = row.createCell(8);
            c8.setCellValue(r.getDropped());
            c8.setCellStyle(dataStyle);

            Cell c9 = row.createCell(9);
            c9.setCellValue(r.getUnresponsive());
            c9.setCellStyle(dataStyle);

            Cell c10 = row.createCell(10);
            c10.setCellValue(r.getWonConversionRate()/100.0);
            c10.setCellStyle(percentStyle);
        }

        //---------------- FILTER ----------------

        sheet.setAutoFilter(new CellRangeAddress(3,3,0,10));

        //---------------- FREEZE HEADER ----------------

        sheet.createFreezePane(0,4);

        //---------------- COLUMN WIDTH ----------------

        sheet.setColumnWidth(0,5000);
        sheet.setColumnWidth(1,5000);
        sheet.setColumnWidth(2,4500);
        sheet.setColumnWidth(3,4000);
        sheet.setColumnWidth(4,3500);
        sheet.setColumnWidth(5,3500);
        sheet.setColumnWidth(6,5000);
        sheet.setColumnWidth(7,4500);
        sheet.setColumnWidth(8,4000);
        sheet.setColumnWidth(9,4500);
        sheet.setColumnWidth(10,4000);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        workbook.write(out);
        workbook.close();

        return out.toByteArray();
    }
}