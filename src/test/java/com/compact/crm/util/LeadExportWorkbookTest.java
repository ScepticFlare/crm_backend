package com.compact.crm.util;

import com.compact.crm.entity.Battery;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.LeadSourceMaster;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Product;
import com.compact.crm.entity.SalesStage;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.SalesStageType;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layout and field mapping of the Leads XLSX export (no Spring context).
 */
class LeadExportWorkbookTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

    private static final List<String> EXPECTED_BUSINESS_HEADERS = List.of(
            "S.NO", "date", "today", "age", "Customer name", "mob", "email id", "Organisation name",
            "location", "ME name", "lead quality", "status-in", "likely conversion", "remarks",
            "final remarks classification");

    private Employee rani;
    private Lead converted;
    private Lead invalid;
    private Lead sparse;
    private Opportunity opportunity;
    private XSSFSheet sheet;

    @BeforeEach
    void setUp() throws IOException {

        rani = Employee.builder().id(7L).name("Rani Rebecca").email("rani@compactups.in").build();

        converted = Lead.builder()
                .id(501L)
                .companyName("UR PRINTERERS")
                .contactPerson("Arul ananthan Vengadesan")
                .phone("06382054630")
                .alternatePhone("04314021154")
                .email("unruledpaperindia@gmail.com")
                .secondaryEmail("accounts@urprinterers.in")
                .city("tirupattur")
                .state("Tamil Nadu")
                .assignedEmployee(rani)
                .leadStatus(LeadStatus.VALID)
                .leadValidity(LeadValidity.VALID)
                .description("OFFER SHARED")
                .finalRemarks("WON")
                .leadSource(LeadSourceMaster.builder().name("IndiaMART").build())
                .industry(Industry.builder().name("Printing").build())
                .createdAt(LocalDateTime.of(2026, 9, 21, 10, 15))
                .updatedAt(LocalDateTime.of(2026, 10, 2, 16, 40))
                .build();
        converted.setConverted(true);
        converted.getLeadProducts().add(LeadProduct.builder().lead(converted)
                .product(Product.builder().name("Online UPS").build()).quantity(2).build());
        converted.getLeadBatteries().add(LeadBattery.builder().lead(converted)
                .battery(Battery.builder().name("SMF 12V 42Ah").build()).quantity(16).build());

        opportunity = Opportunity.builder()
                .id(90L)
                .lead(converted)
                .salesStage(SalesStage.builder().name("WON").stageType(SalesStageType.WON).build())
                .expectedClosingDate(LocalDate.of(2026, 10, 31))
                .build();

        invalid = Lead.builder()
                .id(502L)
                .companyName("NA")
                .contactPerson("Prakash")
                .phone("7200913369")
                .email("prakash a@gmail.com, other@gmail.com") // not linkable
                .city("chennai")
                .assignedEmployee(rani)
                .leadStatus(LeadStatus.INVALID)
                .leadValidity(LeadValidity.INVALID)
                .invalidReason("NEW STARTUP JUST CHECKING")
                .createdAt(LocalDateTime.of(2026, 9, 28, 9, 0))
                .build();
        invalid.setConverted(false);

        // Almost nothing filled in: every optional column must stay blank.
        sparse = Lead.builder()
                .id(503L)
                .phone("9840292883")
                .leadStatus(LeadStatus.NEW)
                .build();

        sheet = render(List.of(converted, invalid, sparse), Map.of(converted.getId(), opportunity));
    }

    // ------------------------------------------------------------- headers

    @Test
    void firstFifteenHeaders_matchTheBusinessReportExactly() {

        List<String> headers = headers(sheet);

        assertThat(headers.subList(0, 15)).containsExactlyElementsOf(EXPECTED_BUSINESS_HEADERS);
        assertThat(LeadExportWorkbook.BUSINESS_HEADERS).containsExactlyElementsOf(EXPECTED_BUSINESS_HEADERS);
    }

    @Test
    void crmSpecificColumns_allComeAfterTheFifteenBusinessColumns_withoutDuplicates() {

        List<String> headers = headers(sheet);
        List<String> extra = headers.subList(15, headers.size());

        assertThat(extra).containsExactly(
                "Alternate Phone", "Secondary Email", "State", "Lead Category", "Lead Status",
                "Invalid Reason", "Lead Source", "Industry", "Campaign Code", "Products", "Batteries",
                "Lead ID", "Last Updated");

        assertThat(extra).doesNotContainAnyElementsOf(EXPECTED_BUSINESS_HEADERS);
        assertThat(new HashSet<>(headers)).hasSameSizeAs(headers);

        // The old headers the business columns replaced are not repeated.
        assertThat(headers).doesNotContain("Company", "Contact Person", "Phone", "Email", "City",
                "Assigned Employee", "Created Date");
    }

    // ------------------------------------------------------------- mapping

    @Test
    void meName_isTheAssignedEmployeesName_notTheirEmailIdOrTheContactPerson() {

        assertThat(text(1, "ME name")).isEqualTo("Rani Rebecca");
        assertThat(text(1, "ME name")).isNotEqualTo(rani.getEmail()).isNotEqualTo("7");
        assertThat(text(1, "Customer name")).isEqualTo("Arul ananthan Vengadesan");
    }

    @Test
    void customerName_organisation_location_email_mapToTheLeadsContactFields() {

        assertThat(text(1, "Customer name")).isEqualTo("Arul ananthan Vengadesan");
        assertThat(text(1, "Organisation name")).isEqualTo("UR PRINTERERS");
        assertThat(text(1, "location")).isEqualTo("tirupattur");
        assertThat(text(1, "email id")).isEqualTo("unruledpaperindia@gmail.com");
        assertThat(text(1, "State")).isEqualTo("Tamil Nadu");
        // stored placeholder values are exported as stored, not cleaned up or replaced
        assertThat(text(2, "Organisation name")).isEqualTo("NA");
    }

    @Test
    void serialNumbers_startAtOne_andIncrement_independentOfDatabaseIds() {

        for (int r = 1; r <= 3; r++) {
            XSSFCell cell = cell(r, "S.NO");
            assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat((int) cell.getNumericCellValue()).isEqualTo(r);
        }

        assertThat((long) cell(1, "Lead ID").getNumericCellValue()).isEqualTo(501L);
    }

    @Test
    void dates_areRealDateCells_inOneConsistentFormat_andTodayIsTheGenerationDate() {

        XSSFCell date = cell(1, "date");
        XSSFCell today = cell(1, "today");
        XSSFCell likely = cell(1, "likely conversion");

        for (XSSFCell c : List.of(date, today, likely)) {
            assertThat(DateUtil.isCellDateFormatted(c)).isTrue();
            assertThat(c.getCellStyle().getDataFormatString()).isEqualTo("dd-mmm-yyyy");
        }

        assertThat(date.getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(likely.getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 31));

        // Same report date on every row - including a row with no lead date.
        for (int r = 1; r <= 3; r++) {
            assertThat(cell(r, "today").getLocalDateTimeCellValue().toLocalDate()).isEqualTo(TODAY);
        }

        XSSFCell updated = cell(1, "Last Updated");
        assertThat(updated.getCellStyle().getDataFormatString()).isEqualTo("dd-mmm-yyyy hh:mm");
        assertThat(updated.getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 10, 2, 16, 40));
    }

    @Test
    void age_isWholeDaysFromLeadDateToReportDate_matchingTheBusinessSheet() {

        // Business sheet: 21-Sep-26 -> 43 and 28-Sep-26 -> 36 on 03-Nov-26.
        assertThat((int) cell(1, "age").getNumericCellValue()).isEqualTo(43);
        assertThat((int) cell(2, "age").getNumericCellValue()).isEqualTo(36);
        assertThat(isBlank(3, "age")).isTrue();
    }

    @Test
    void phoneNumbers_areTextCells_keepingLeadingZeros() {

        for (String header : List.of("mob", "Alternate Phone")) {
            XSSFCell c = cell(1, header);
            assertThat(c.getCellType()).isEqualTo(CellType.STRING);
            assertThat(c.getCellStyle().getDataFormatString()).isEqualTo("@");
        }

        assertThat(text(1, "mob")).isEqualTo("06382054630");
        assertThat(text(1, "Alternate Phone")).isEqualTo("04314021154");
    }

    @Test
    void leadQuality_isTheLeadsValidity_andStatusIn_followsLeadThenOpportunity() {

        assertThat(text(1, "lead quality")).isEqualTo("VALID");
        assertThat(text(2, "lead quality")).isEqualTo("INVALID");

        // converted -> the opportunity's stage; invalid -> INVALID; otherwise the lead status
        assertThat(text(1, "status-in")).isEqualTo("WON");
        assertThat(text(2, "status-in")).isEqualTo("INVALID");
        assertThat(text(3, "status-in")).isEqualTo("NEW");

        // the CRM's own lifecycle / stored status stay available, distinct from the above
        assertThat(text(1, "Lead Category")).isEqualTo("Converted to opportunity");
        assertThat(text(1, "Lead Status")).isEqualTo("VALID");
        assertThat(text(2, "Lead Category")).isEqualTo("Invalid");
    }

    @Test
    void invalidLeadWithAnOpportunity_isStillReportedInvalid() {

        Lead lead = Lead.builder().id(9L).leadStatus(LeadStatus.INVALID).leadValidity(LeadValidity.INVALID).build();
        Opportunity won = Opportunity.builder().lead(lead)
                .salesStage(SalesStage.builder().name("WON").stageType(SalesStageType.WON).build()).build();

        assertThat(LeadExportWorkbook.statusIn(lead, won).label()).isEqualTo("INVALID");
    }

    @Test
    void renamedStage_isReportedByItsDisplayName() {

        Lead lead = Lead.builder().id(9L).leadStatus(LeadStatus.VALID).build();
        Opportunity o = Opportunity.builder().lead(lead)
                .salesStage(SalesStage.builder().name("Dropped by customer").stageType(SalesStageType.LEGACY_DROPPED).build())
                .build();

        assertThat(LeadExportWorkbook.statusIn(lead, o).label()).isEqualTo("Dropped by customer");
        assertThat(LeadExportWorkbook.statusIn(lead, o).tone()).isEqualTo(LeadExportWorkbook.Tone.NEGATIVE);
    }

    @Test
    void remarks_finalRemarks_invalidReason_areSeparateSourceFields() {

        assertThat(text(1, "remarks")).isEqualTo("OFFER SHARED");
        assertThat(text(1, "final remarks classification")).isEqualTo("WON");
        assertThat(isBlank(1, "Invalid Reason")).isTrue();

        assertThat(isBlank(2, "remarks")).isTrue();
        assertThat(isBlank(2, "final remarks classification")).isTrue();
        assertThat(text(2, "Invalid Reason")).isEqualTo("NEW STARTUP JUST CHECKING");
    }

    @Test
    void productsAndBatteries_listNamesWithQuantities() {

        assertThat(text(1, "Products")).isEqualTo("Online UPS x2");
        assertThat(text(1, "Batteries")).isEqualTo("SMF 12V 42Ah x16");
    }

    @Test
    void missingFields_stayBlank_nothingIsFabricated() {

        for (String header : List.of("date", "age", "Customer name", "email id", "Organisation name",
                "location", "ME name", "lead quality", "likely conversion", "remarks",
                "final remarks classification", "Alternate Phone", "Secondary Email", "State",
                "Lead Category", "Invalid Reason", "Lead Source", "Industry", "Campaign Code",
                "Products", "Batteries", "Last Updated")) {
            assertThat(isBlank(3, header)).as(header).isTrue();
        }

        // No opportunity -> no likely conversion, even for a VALID lead.
        assertThat(isBlank(2, "likely conversion")).isTrue();
    }

    // ------------------------------------------------------------ formatting

    @Test
    void emails_areClickableOnlyWhenTheyAreAPlainAddress() {

        assertThat(cell(1, "email id").getHyperlink()).isNotNull();
        assertThat(cell(1, "email id").getHyperlink().getAddress()).isEqualTo("mailto:unruledpaperindia@gmail.com");
        assertThat(cell(2, "email id").getHyperlink()).isNull();
        assertThat(text(2, "email id")).isEqualTo("prakash a@gmail.com, other@gmail.com");
    }

    @Test
    void sheet_hasFrozenBoldHeader_filterOverTheWholeTable_andNoStrayRowsOrColumns() {

        assertThat(sheet.getSheetName()).isEqualTo("Leads");
        assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
        assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
        assertThat(sheet.getPaneInformation().getVerticalSplitPosition()).isEqualTo((short) 0);

        assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFont().getBold()).isTrue();

        int lastCol = headers(sheet).size() - 1;
        CellRangeAddress filter = CellRangeAddress.valueOf(
                sheet.getCTWorksheet().getAutoFilter().getRef());
        assertThat(filter.getFirstRow()).isZero();
        assertThat(filter.getLastRow()).isEqualTo(3);
        assertThat(filter.getFirstColumn()).isZero();
        assertThat(filter.getLastColumn()).isEqualTo(lastCol);

        assertThat(sheet.getLastRowNum()).isEqualTo(3);
        assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) (lastCol + 1));
    }

    @Test
    void columnWidths_fitContent_withinSensibleBounds() {

        List<String> headers = headers(sheet);

        for (int c = 0; c < headers.size(); c++) {
            int chars = sheet.getColumnWidth(c) / 256;
            assertThat(chars).as(headers.get(c)).isBetween(6, 42);
            // never narrower than its own header + filter arrow
            assertThat(chars).as(headers.get(c)).isGreaterThanOrEqualTo(headers.get(c).length() + 3);
        }

        // long contact name is fully visible
        assertThat(sheet.getColumnWidth(col("Customer name")) / 256)
                .isGreaterThanOrEqualTo("Arul ananthan Vengadesan".length());
    }

    @Test
    void longRemarks_wrap_andTheRowGrowsToShowThem() throws IOException {

        Lead chatty = Lead.builder().id(1L).phone("9000000000").leadStatus(LeadStatus.NEW)
                .description("Customer asked for a revised quotation including installation, AMC for three years "
                        + "and battery replacement terms.\nCall back after the board meeting.")
                .build();

        XSSFSheet s = render(List.of(chatty), Map.of());
        XSSFCell remarks = s.getRow(1).getCell(LeadExportWorkbook.headers().indexOf("remarks"));

        assertThat(remarks.getCellStyle().getWrapText()).isTrue();
        assertThat(s.getRow(1).getHeightInPoints()).isGreaterThan(s.getDefaultRowHeightInPoints() * 2);
        assertThat(s.getRow(1).getHeightInPoints()).isLessThanOrEqualTo(6 * 15f + 2f);
    }

    @Test
    void rowsWhoseTextFits_keepTheDefaultHeight() {

        // "Arul ananthan Vengadesan" and "Converted to opportunity" fit their
        // columns, so nothing on row 1 wraps and no height is forced.
        assertThat(sheet.getRow(1).getHeightInPoints()).isEqualTo(sheet.getDefaultRowHeightInPoints());
        assertThat(LeadExportWorkbook.wrappedLines("Converted to opportunity", 24)).isEqualTo(1);
        assertThat(LeadExportWorkbook.wrappedLines("Converted to opportunity", 23)).isEqualTo(2);
        assertThat(LeadExportWorkbook.wrappedLines("a\nb\nc", 40)).isEqualTo(3);
    }

    @Test
    void emptyExport_isJustTheHeaderRow() throws IOException {

        XSSFSheet s = render(List.of(), Map.of());

        assertThat(s.getLastRowNum()).isZero();
        assertThat(headers(s).subList(0, 15)).containsExactlyElementsOf(EXPECTED_BUSINESS_HEADERS);
    }

    // Writes a sample of the real output so it can be opened and inspected.
    @Test
    void writesASampleWorkbookForManualInspection() throws IOException {

        byte[] bytes = LeadExportWorkbook.toXlsx(
                List.of(converted, invalid, sparse), Map.of(converted.getId(), opportunity), TODAY);

        Path out = Path.of("target", "lead-export-sample.xlsx");
        Files.createDirectories(out.getParent());
        Files.write(out, bytes);

        assertThat(Files.size(out)).isPositive();
    }

    // ---------------------------------------------------------------- helpers

    private static XSSFSheet render(List<Lead> leads, Map<Long, Opportunity> opportunities) throws IOException {
        byte[] bytes = LeadExportWorkbook.toXlsx(leads, opportunities, TODAY);
        return new XSSFWorkbook(new ByteArrayInputStream(bytes)).getSheetAt(0);
    }

    private static List<String> headers(XSSFSheet s) {
        List<String> headers = new ArrayList<>();
        s.getRow(0).forEach(c -> headers.add(c.getStringCellValue()));
        return headers;
    }

    private int col(String header) {
        int i = headers(sheet).indexOf(header);
        assertThat(i).as("column " + header).isNotNegative();
        return i;
    }

    private XSSFCell cell(int row, String header) {
        XSSFRow r = sheet.getRow(row);
        return r == null ? null : r.getCell(col(header));
    }

    private String text(int row, String header) {
        return cell(row, header).getStringCellValue();
    }

    private boolean isBlank(int row, String header) {
        XSSFCell c = cell(row, header);
        return c == null || c.getCellType() == CellType.BLANK;
    }
}
