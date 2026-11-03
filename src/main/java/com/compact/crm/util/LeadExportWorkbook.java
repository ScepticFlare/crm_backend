package com.compact.crm.util;

import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.enums.LeadCategory;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.LeadValidity;
import com.compact.crm.enums.SalesStageType;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFHyperlink;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * XLSX layout of the Leads page export (GET /api/leads/export).
 *
 * The first 15 columns reproduce the business team's existing lead sheet -
 * same order, same header spelling - so the file drops straight into their
 * workflow. The mapping is the one service.LeadImportService already uses to
 * read that sheet back in (column 1 -> createdAt, 4 -> contactPerson,
 * 5 -> phone, 6 -> email, 7 -> companyName, 8 -> city, 9 -> assigned
 * employee name, 10 -> leadValidity, 13 -> description, 14 -> finalRemarks),
 * plus the derived columns:
 *   today              the report-generation date (business timezone)
 *   age                whole days from `date` to `today` (the business sheet's
 *                      own values: 21-Sep -> 03-Nov = 43)
 *   status-in          INVALID when the lead is invalid, otherwise the stage
 *                      of the lead's Opportunity when converted, otherwise the
 *                      lead's stored status
 *   likely conversion  the Opportunity's expected closing date (blank for an
 *                      unconverted lead - nothing is estimated)
 *
 * CRM-only fields follow after column 15. Missing values are left as empty
 * cells; nothing is invented. CSV exports are NOT produced here - they keep
 * the original TableExport column set.
 */
public final class LeadExportWorkbook {

    public static final String SHEET_NAME = "Leads";

    // Day-month-name-year: unambiguous in every Excel locale (no 04/05 vs
    // 05/04 question) and the same shape as the business sheet's 21-Sep-26,
    // with a four-digit year. Values stay real date cells.
    public static final String DATE_FORMAT = "dd-mmm-yyyy";
    public static final String DATETIME_FORMAT = "dd-mmm-yyyy hh:mm";

    public static final List<String> BUSINESS_HEADERS = List.of(
            "S.NO",
            "date",
            "today",
            "age",
            "Customer name",
            "mob",
            "email id",
            "Organisation name",
            "location",
            "ME name",
            "lead quality",
            "status-in",
            "likely conversion",
            "remarks",
            "final remarks classification"
    );

    // Conservative: only plain addresses become mailto links. Anything else
    // (two addresses in one field, stray spaces, odd characters) is still
    // exported, just as plain text, so a bad value can never break the file.
    private static final Pattern LINKABLE_EMAIL =
            Pattern.compile("^[A-Za-z0-9._+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    private static final int MAX_WRAPPED_LINES = 6;
    private static final float LINE_HEIGHT_POINTS = 15f;

    private LeadExportWorkbook() {
    }

    // ---------------------------------------------------------------- model

    enum Kind { SERIAL, INTEGER, TEXT, WRAP, PHONE, EMAIL, DATE, DATETIME, STATUS }

    // Subtle colour cue for status-like cells; NEUTRAL means plain.
    enum Tone { NEUTRAL, POSITIVE, NEGATIVE, PENDING }

    record Status(String label, Tone tone) {
    }

    // Per-row inputs that do not live on the Lead itself.
    record RowContext(int serial, LocalDate today, Opportunity opportunity) {
    }

    record Column(String header, Kind kind, int maxWidth, Function<Row, Object> value) {
    }

    // A lead plus its row context, handed to every column extractor.
    record Row(Lead lead, RowContext ctx) {
    }

    private static Column col(String header, Kind kind, int maxWidth, Function<Row, Object> value) {
        return new Column(header, kind, maxWidth, value);
    }

    static final List<Column> COLUMNS = List.of(
            // ---- business report layout (order and spelling are fixed) ----
            col("S.NO", Kind.SERIAL, 6, r -> r.ctx().serial()),
            col("date", Kind.DATE, 13, r -> leadDate(r.lead())),
            col("today", Kind.DATE, 13, r -> r.ctx().today()),
            col("age", Kind.INTEGER, 6, r -> ageInDays(r.lead(), r.ctx().today())),
            col("Customer name", Kind.TEXT, 32, r -> r.lead().getContactPerson()),
            col("mob", Kind.PHONE, 18, r -> r.lead().getPhone()),
            col("email id", Kind.EMAIL, 36, r -> r.lead().getEmail()),
            col("Organisation name", Kind.TEXT, 36, r -> r.lead().getCompanyName()),
            col("location", Kind.TEXT, 22, r -> r.lead().getCity()),
            col("ME name", Kind.TEXT, 22, r -> r.lead().getAssignedEmployee() != null
                    ? r.lead().getAssignedEmployee().getName() : null),
            col("lead quality", Kind.STATUS, 14, r -> leadQuality(r.lead())),
            col("status-in", Kind.STATUS, 18, r -> statusIn(r.lead(), r.ctx().opportunity())),
            col("likely conversion", Kind.DATE, 13, r -> likelyConversion(r.ctx().opportunity())),
            col("remarks", Kind.WRAP, 40, r -> r.lead().getDescription()),
            col("final remarks classification", Kind.WRAP, 36, r -> r.lead().getFinalRemarks()),

            // ---- CRM-specific fields not represented above ----
            col("Alternate Phone", Kind.PHONE, 18, r -> r.lead().getAlternatePhone()),
            col("Secondary Email", Kind.EMAIL, 32, r -> r.lead().getSecondaryEmail()),
            col("State", Kind.TEXT, 18, r -> r.lead().getState()),
            col("Lead Category", Kind.TEXT, 26, r -> r.lead().getCategory() != null
                    ? categoryLabel(r.lead().getCategory()) : null),
            col("Lead Status", Kind.TEXT, 16, r -> r.lead().getLeadStatus() != null
                    ? r.lead().getLeadStatus().name() : null),
            col("Invalid Reason", Kind.WRAP, 36, r -> r.lead().getInvalidReason()),
            col("Lead Source", Kind.TEXT, 20, r -> r.lead().getLeadSource() != null
                    ? r.lead().getLeadSource().getName() : null),
            col("Industry", Kind.TEXT, 22, r -> r.lead().getIndustry() != null
                    ? r.lead().getIndustry().getName() : null),
            col("Campaign Code", Kind.TEXT, 20, r -> r.lead().getCampaignCode()),
            col("Products", Kind.WRAP, 32, r -> describeProducts(r.lead())),
            col("Batteries", Kind.WRAP, 28, r -> describeBatteries(r.lead())),
            col("Lead ID", Kind.INTEGER, 9, r -> r.lead().getId()),
            col("Last Updated", Kind.DATETIME, 18, r -> r.lead().getUpdatedAt())
    );

    public static List<String> headers() {
        return COLUMNS.stream().map(Column::header).toList();
    }

    // ------------------------------------------------------- field mapping

    // The lead's original capture date (LeadImportService writes the
    // business sheet's `date` into createdAt).
    static LocalDate leadDate(Lead lead) {
        LocalDateTime created = lead.getCreatedAt();
        return created != null ? created.toLocalDate() : null;
    }

    static Long ageInDays(Lead lead, LocalDate today) {
        LocalDate date = leadDate(lead);
        return date != null && today != null ? ChronoUnit.DAYS.between(date, today) : null;
    }

    static Status leadQuality(Lead lead) {

        LeadValidity validity = lead.getLeadValidity();

        if (validity == null) {
            return null;
        }

        return new Status(validity.name(), validity == LeadValidity.VALID ? Tone.POSITIVE : Tone.NEGATIVE);
    }

    // The business sheet's status column mixes lead and opportunity outcomes
    // (invalid / dropped / lost / postponed / WON). INVALID takes precedence
    // (same rule as enums.LeadCategory); a converted lead reports its
    // Opportunity's stage by its configured display name; anything else
    // reports the lead's own stored status.
    static Status statusIn(Lead lead, Opportunity opportunity) {

        if (lead.getLeadStatus() == LeadStatus.INVALID) {
            return new Status(label(LeadStatus.INVALID), Tone.NEGATIVE);
        }

        if (opportunity != null && opportunity.getSalesStage() != null
                && opportunity.getSalesStage().getName() != null
                && !opportunity.getSalesStage().getName().isBlank()) {

            return new Status(opportunity.getSalesStage().getName().trim(),
                    toneOf(opportunity.getSalesStage().getStageType()));
        }

        LeadStatus status = lead.getLeadStatus();

        return status != null ? new Status(label(status), toneOf(status)) : null;
    }

    static LocalDate likelyConversion(Opportunity opportunity) {
        return opportunity != null ? opportunity.getExpectedClosingDate() : null;
    }

    public static String categoryLabel(LeadCategory category) {
        return switch (category) {
            case NEW -> "New";
            case VALID_UNCONVERTED -> "Valid (not converted)";
            case INVALID -> "Invalid";
            case CONVERTED -> "Converted to opportunity";
        };
    }

    static String describeProducts(Lead lead) {

        List<LeadProduct> items = lead.getLeadProducts();

        if (items == null) {
            return null;
        }

        return items.stream()
                .filter(i -> i.getProduct() != null && i.getProduct().getName() != null)
                .map(i -> i.getProduct().getName() + quantitySuffix(i.getQuantity()))
                .collect(Collectors.joining("; "));
    }

    static String describeBatteries(Lead lead) {

        List<LeadBattery> items = lead.getLeadBatteries();

        if (items == null) {
            return null;
        }

        return items.stream()
                .filter(i -> i.getBattery() != null && i.getBattery().getName() != null)
                .map(i -> i.getBattery().getName() + quantitySuffix(i.getQuantity()))
                .collect(Collectors.joining("; "));
    }

    private static String quantitySuffix(Integer quantity) {
        return quantity != null ? " x" + quantity : "";
    }

    private static String label(LeadStatus status) {
        return status.name().replace('_', ' ');
    }

    private static Tone toneOf(LeadStatus status) {
        return switch (status) {
            case VALID, WON -> Tone.POSITIVE;
            case INVALID, LOST, DROPPED, UNRESPONSIVE -> Tone.NEGATIVE;
            default -> Tone.NEUTRAL;
        };
    }

    // Keyed off the stage's stable type, so a renamed stage keeps its colour.
    private static Tone toneOf(SalesStageType type) {

        if (type == null) {
            return Tone.NEUTRAL;
        }

        return switch (type) {
            case WON -> Tone.POSITIVE;
            case LOST, LEGACY_DROPPED, LEGACY_UNRESPONSIVE, LEGACY_INVALID -> Tone.NEGATIVE;
            case POSTPONED -> Tone.PENDING;
            default -> Tone.NEUTRAL;
        };
    }

    // ------------------------------------------------------------- writing

    /**
     * @param leads           rows in export order (serial numbers follow it)
     * @param opportunityByLeadId the converted leads' Opportunity, by lead id
     * @param today           report-generation date
     */
    public static byte[] toXlsx(List<Lead> leads, Map<Long, Opportunity> opportunityByLeadId, LocalDate today) {

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {

            Styles styles = new Styles(workbook);
            XSSFSheet sheet = workbook.createSheet(SHEET_NAME);
            var helper = workbook.getCreationHelper();

            int columnCount = COLUMNS.size();
            int[] widths = new int[columnCount];

            XSSFRow headerRow = sheet.createRow(0);
            headerRow.setHeightInPoints(20f);

            for (int c = 0; c < columnCount; c++) {
                Column column = COLUMNS.get(c);
                XSSFCell cell = headerRow.createCell(c);
                cell.setCellValue(column.header());
                cell.setCellStyle(styles.header);
                // header text + room for the filter drop-down arrow
                widths[c] = column.header().length() + 3;
            }

            // Pass 1: values + content widths. Kept so row heights can be
            // computed against the final (clamped) column widths.
            Object[][] values = new Object[leads.size()][columnCount];

            for (int r = 0; r < leads.size(); r++) {

                Lead lead = leads.get(r);
                Opportunity opportunity = opportunityByLeadId != null ? opportunityByLeadId.get(lead.getId()) : null;
                Row row = new Row(lead, new RowContext(r + 1, today, opportunity));

                for (int c = 0; c < columnCount; c++) {
                    Object value = normalise(COLUMNS.get(c).value().apply(row));
                    values[r][c] = value;
                    widths[c] = Math.max(widths[c], displayWidth(COLUMNS.get(c).kind(), value));
                }
            }

            for (int c = 0; c < columnCount; c++) {
                widths[c] = Math.min(widths[c], Math.max(COLUMNS.get(c).maxWidth(), COLUMNS.get(c).header().length() + 3));
                widths[c] = Math.max(widths[c], 6);
                sheet.setColumnWidth(c, (widths[c] + 1) * 256);
            }

            // Pass 2: cells.
            for (int r = 0; r < leads.size(); r++) {

                XSSFRow row = sheet.createRow(r + 1);
                int lines = 1;

                for (int c = 0; c < columnCount; c++) {

                    Column column = COLUMNS.get(c);
                    Object value = values[r][c];
                    XSSFCell cell = row.createCell(c);

                    if (value == null) {
                        // An empty but bordered cell keeps the grid tidy.
                        cell.setCellStyle(styles.forEmpty(column.kind()));
                        continue;
                    }

                    switch (column.kind()) {

                        case SERIAL, INTEGER -> {
                            cell.setCellValue(((Number) value).doubleValue());
                            cell.setCellStyle(column.kind() == Kind.SERIAL ? styles.serial : styles.integer);
                        }

                        case DATE -> {
                            cell.setCellValue((LocalDate) value);
                            cell.setCellStyle(styles.date);
                        }

                        case DATETIME -> {
                            cell.setCellValue((LocalDateTime) value);
                            cell.setCellStyle(styles.dateTime);
                        }

                        case STATUS -> {
                            Status status = (Status) value;
                            cell.setCellValue(status.label());
                            cell.setCellStyle(styles.status.get(status.tone()));
                        }

                        case EMAIL -> {
                            String email = (String) value;
                            cell.setCellValue(email);

                            if (LINKABLE_EMAIL.matcher(email).matches()) {
                                XSSFHyperlink link = (XSSFHyperlink) helper.createHyperlink(HyperlinkType.EMAIL);
                                link.setAddress("mailto:" + email);
                                cell.setHyperlink(link);
                                cell.setCellStyle(styles.email);
                            } else {
                                cell.setCellStyle(styles.text);
                            }
                        }

                        case PHONE -> {
                            // Always a string cell with the "@" text format:
                            // keeps leading zeros and +country codes, and
                            // never shows as 9.84E+09.
                            cell.setCellValue((String) value);
                            cell.setCellStyle(styles.text);
                        }

                        case TEXT, WRAP -> {
                            String text = (String) value;
                            cell.setCellValue(text);
                            cell.setCellStyle(styles.wrap);
                            lines = Math.max(lines, wrappedLines(text, widths[c]));
                        }
                    }
                }

                if (lines > 1) {
                    row.setHeightInPoints(Math.min(lines, MAX_WRAPPED_LINES) * LINE_HEIGHT_POINTS + 2f);
                }
            }

            int lastRow = leads.size();

            sheet.setAutoFilter(new CellRangeAddress(0, lastRow, 0, columnCount - 1));
            sheet.createFreezePane(0, 1);

            // Printing: landscape, one page wide, header repeated per page.
            sheet.getPrintSetup().setLandscape(true);
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.setRepeatingRows(CellRangeAddress.valueOf("1:1"));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Blank text is "missing": no cell value is written for it.
    private static Object normalise(Object value) {

        if (value instanceof String s) {
            String trimmed = s.strip();
            return trimmed.isEmpty() ? null : trimmed;
        }

        if (value instanceof Status s && (s.label() == null || s.label().isBlank())) {
            return null;
        }

        return value;
    }

    private static int displayWidth(Kind kind, Object value) {

        if (value == null) {
            return 0;
        }

        return switch (kind) {
            case DATE -> 12;
            case DATETIME -> 17;
            case SERIAL, INTEGER -> String.valueOf(((Number) value).longValue()).length() + 1;
            case STATUS -> ((Status) value).label().length() + 1;
            default -> longestLine(value.toString()) + 1;
        };
    }

    private static int longestLine(String s) {
        return Stream.of(s.split("\\R", -1)).mapToInt(String::length).max().orElse(0);
    }

    // `widthChars` is the content width; the column itself is set one
    // character wider (see toXlsx), so a line of exactly widthChars fits.
    static int wrappedLines(String text, int widthChars) {

        int usable = Math.max(1, widthChars);
        int lines = 0;

        for (String line : text.split("\\R", -1)) {
            lines += Math.max(1, (int) Math.ceil(line.length() / (double) usable));
        }

        return lines;
    }

    // ---------------------------------------------------------------- styles

    private static final class Styles {

        final XSSFCellStyle header;
        final XSSFCellStyle text;
        final XSSFCellStyle wrap;
        final XSSFCellStyle email;
        final XSSFCellStyle serial;
        final XSSFCellStyle integer;
        final XSSFCellStyle date;
        final XSSFCellStyle dateTime;
        final XSSFCellStyle blank;
        final XSSFCellStyle blankCentered;
        final Map<Tone, XSSFCellStyle> status = new EnumMap<>(Tone.class);

        private final XSSFWorkbook workbook;
        private final XSSFColor gridColor;

        Styles(XSSFWorkbook workbook) {

            this.workbook = workbook;
            this.gridColor = rgb(0xD0, 0xD5, 0xDD);

            var formats = workbook.createDataFormat();

            XSSFFont bold = workbook.createFont();
            bold.setBold(true);
            bold.setColor(rgb(0x1F, 0x2A, 0x37));

            header = base();
            header.setFont(bold);
            header.setFillForegroundColor(rgb(0xDD, 0xE4, 0xEE));
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setBorderBottom(BorderStyle.MEDIUM);
            header.setBottomBorderColor(rgb(0x8A, 0x99, 0xAD));

            text = base();
            text.setDataFormat(formats.getFormat("@"));

            wrap = base();
            wrap.setDataFormat(formats.getFormat("@"));
            wrap.setWrapText(true);

            XSSFFont linkFont = workbook.createFont();
            linkFont.setUnderline(XSSFFont.U_SINGLE);
            linkFont.setColor(rgb(0x05, 0x63, 0xC1));

            email = base();
            email.setDataFormat(formats.getFormat("@"));
            email.setFont(linkFont);

            serial = base();
            serial.setDataFormat(formats.getFormat("0"));
            serial.setAlignment(HorizontalAlignment.CENTER);

            integer = base();
            integer.setDataFormat(formats.getFormat("0"));
            integer.setAlignment(HorizontalAlignment.CENTER);

            date = base();
            date.setDataFormat(formats.getFormat(DATE_FORMAT));
            date.setAlignment(HorizontalAlignment.LEFT);

            dateTime = base();
            dateTime.setDataFormat(formats.getFormat(DATETIME_FORMAT));
            dateTime.setAlignment(HorizontalAlignment.LEFT);

            blank = base();
            blankCentered = base();
            blankCentered.setAlignment(HorizontalAlignment.CENTER);

            status.put(Tone.NEUTRAL, base());
            status.put(Tone.POSITIVE, tinted(0xE6, 0xF4, 0xEA, 0x1E, 0x6B, 0x3A));
            status.put(Tone.NEGATIVE, tinted(0xFB, 0xE9, 0xE9, 0x9B, 0x1C, 0x1C));
            status.put(Tone.PENDING, tinted(0xFF, 0xF4, 0xD6, 0x7A, 0x55, 0x00));
        }

        XSSFCellStyle forEmpty(Kind kind) {
            return kind == Kind.SERIAL || kind == Kind.INTEGER ? blankCentered : blank;
        }

        private XSSFCellStyle base() {

            XSSFCellStyle s = workbook.createCellStyle();
            s.setVerticalAlignment(VerticalAlignment.TOP);
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            s.setTopBorderColor(gridColor);
            s.setBottomBorderColor(gridColor);
            s.setLeftBorderColor(gridColor);
            s.setRightBorderColor(gridColor);
            return s;
        }

        private XSSFCellStyle tinted(int fr, int fg, int fb, int tr, int tg, int tb) {

            XSSFFont font = workbook.createFont();
            font.setColor(rgb(tr, tg, tb));

            XSSFCellStyle s = base();
            s.setFillForegroundColor(rgb(fr, fg, fb));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setFont(font);
            return s;
        }

        private static XSSFColor rgb(int r, int g, int b) {
            return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, new DefaultIndexedColorMap());
        }
    }
}
