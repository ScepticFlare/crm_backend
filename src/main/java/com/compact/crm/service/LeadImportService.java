package com.compact.crm.service;

import com.compact.crm.entity.*;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Iterator;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LeadImportService {

    private final LeadRepository leadRepository;
    private final EmployeeRepository employeeRepository;
    private final ProductRepository productRepository;
    private final IndustryRepository industryRepository;
    private final LeadSourceMasterRepository leadSourceRepository;

    public String importExcel(MultipartFile file) {

        try {

            InputStream inputStream = file.getInputStream();

            Workbook workbook = WorkbookFactory.create(inputStream);

            Sheet sheet = workbook.getSheetAt(0);

            Iterator<Row> rows = sheet.iterator();

            if (rows.hasNext()) {
                rows.next();
            }

            Product defaultProduct =
                    productRepository.findByNameIgnoreCase("Online UPS")
                            .orElseThrow(() ->
                                    new RuntimeException("Product 'Online UPS' not found"));

            Industry defaultIndustry =
                    industryRepository.findByNameIgnoreCase("Manufacturing")
                            .orElseThrow(() ->
                                    new RuntimeException("Industry 'Manufacturing' not found"));

            LeadSourceMaster defaultLeadSource =
                    leadSourceRepository.findByNameIgnoreCase("IndiaMART")
                            .orElseThrow(() ->
                                    new RuntimeException("Lead Source 'IndiaMART' not found"));

            int imported = 0;
            int skipped = 0;

            while (rows.hasNext()) {

                Row row = rows.next();

                String contactPerson = getCellValue(row.getCell(4));
                String phone = getCellValue(row.getCell(5));
                String email = getCellValue(row.getCell(6));
                String company = getCellValue(row.getCell(7));
                String city = getCellValue(row.getCell(8));
                String employeeName = getCellValue(row.getCell(9));
                String validity = getCellValue(row.getCell(10));
                String status = getCellValue(row.getCell(11));
                String remarks = getCellValue(row.getCell(13));
                String finalRemarks = getCellValue(row.getCell(14));

                if (phone == null || phone.isBlank()) {
                    System.out.println("Skipped: Missing phone at row " + (row.getRowNum() + 1));
                    skipped++;
                    continue;
                }

                if (leadRepository.existsByPhone(phone)) {
                    System.out.println("Skipped: Duplicate phone " + phone);
                    skipped++;
                    continue;
                }

                if (email != null &&
                        !email.isBlank() &&
                        leadRepository.existsByEmail(email)) {

                    System.out.println("Skipped: Duplicate email " + email);

                    skipped++;
                    continue;
                }

                Employee assignedEmployee =
                        employeeRepository.findByNameIgnoreCase(employeeName.trim())
                                .orElseGet(() ->
                                        employeeRepository.findByNameIgnoreCase("Suresh")
                                                .orElse(null));

                Lead lead = new Lead();

                company = cleanText(company);
                contactPerson = cleanText(contactPerson);
                city = cleanText(city);

                phone = cleanPhone(phone);

                String[] emails = splitEmails(email);

                lead.setCompanyName(company);
                lead.setContactPerson(contactPerson);

                lead.setPhone(phone);

                lead.setEmail(emails[0]);
                lead.setSecondaryEmail(emails[1]);

                lead.setCity(city);

                lead.setDescription(remarks);
                lead.setFinalRemarks(finalRemarks);

                lead.setProduct(defaultProduct);
                lead.setIndustry(defaultIndustry);
                lead.setLeadSource(defaultLeadSource);

                lead.setAssignedEmployee(assignedEmployee);

                lead.setLeadValidity(mapValidity(validity));

                lead.setLeadStatus(mapStatus(status));

                leadRepository.save(lead);

                imported++;

            }

            workbook.close();

            return "Imported : " + imported + " | Skipped : " + skipped;

        } catch (Exception e) {

            e.printStackTrace();

            return e.getMessage();
        }

    }
    private LeadStatus mapStatus(String status) {

        if (status == null) {
            return LeadStatus.NEW;
        }

        status = status.trim().toLowerCase();

        switch (status) {

            case "won":
                return LeadStatus.WON;

            case "lost":
                return LeadStatus.LOST;

            case "in progress":
                return LeadStatus.CONTACTED;

            case "postponed":
                return LeadStatus.NEGOTIATION;

            default:
                return LeadStatus.NEW;
        }
    }

    private LeadValidity mapValidity(String validity) {

        if (validity == null) {
            return LeadValidity.VALID;
        }

        validity = validity.trim().toLowerCase();

        if (validity.equals("invalid")) {
            return LeadValidity.INVALID;
        }

        return LeadValidity.VALID;
    }

    private String cleanText(String value) {

        if (value == null) {
            return "";
        }

        value = value.trim();

        if (value.equalsIgnoreCase("NA")
                || value.equalsIgnoreCase("N/A")
                || value.equalsIgnoreCase("null")
                || value.equals("-")) {

            return "";
        }

        return value;

    }

    private String[] splitEmails(String email) {

        email = cleanText(email);

        if (email.isEmpty()) {
            return new String[]{"", ""};
        }

        String[] parts = email.split("\\s*[,;]\\s*");

        String primary = parts.length > 0 ? parts[0].trim() : "";

        String secondary = parts.length > 1 ? parts[1].trim() : "";

        return new String[]{primary, secondary};

    }

    private String cleanPhone(String phone) {

        phone = cleanText(phone);

        if (phone.isEmpty()) {
            return "";
        }

        phone = phone.replace("+91", "");

        phone = phone.replaceAll("[^0-9]", "");

        if (phone.contains(",")) {
            phone = phone.split(",")[0];
        }

        return phone;

    }

    private String getCellValue(Cell cell) {

        if (cell == null) {
            return "";
        }

        switch (cell.getCellType()) {

            case STRING:
                return cell.getStringCellValue().trim();

            case NUMERIC:

                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }

                double value = cell.getNumericCellValue();

                if (value == (long) value) {
                    return String.valueOf((long) value);
                }

                return String.valueOf(value);

            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());

            case FORMULA:

                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {

                    try {

                        double formulaValue = cell.getNumericCellValue();

                        if (formulaValue == (long) formulaValue) {
                            return String.valueOf((long) formulaValue);
                        }

                        return String.valueOf(formulaValue);

                    } catch (Exception ignored) {
                        return "";
                    }

                }

            default:
                return "";
        }

    }

}
