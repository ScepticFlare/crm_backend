package com.compact.crm.util;

import java.util.List;

/**
 * Minimal RFC-4180-ish CSV writer. No new dependency: export is plain CSV
 * by design (see task scope - XLSX import/export infrastructure is a
 * separate future phase).
 */
public final class CsvWriter {

    private CsvWriter() {
    }

    public static String write(List<String> headers, List<List<String>> rows) {

        StringBuilder sb = new StringBuilder();

        appendRow(sb, headers);

        for (List<String> row : rows) {
            appendRow(sb, row);
        }

        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> values) {

        for (int i = 0; i < values.size(); i++) {

            if (i > 0) {
                sb.append(',');
            }

            sb.append(escape(values.get(i)));
        }

        sb.append("\r\n");
    }

    private static String escape(String value) {

        if (value == null) {
            return "";
        }

        boolean needsQuoting = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");

        String escaped = value.replace("\"", "\"\"");

        return needsQuoting ? "\"" + escaped + "\"" : escaped;
    }
}
