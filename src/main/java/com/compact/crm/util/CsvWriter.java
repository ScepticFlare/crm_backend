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

    // UTF-8 with a byte-order mark: without it Excel decodes the file as the
    // local ANSI code page and mangles non-ASCII names.
    public static byte[] toUtf8BytesWithBom(String csv) {

        byte[] body = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];

        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;

        System.arraycopy(body, 0, out, 3, body.length);

        return out;
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
