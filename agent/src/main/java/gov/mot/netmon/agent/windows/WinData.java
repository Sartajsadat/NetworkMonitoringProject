package gov.mot.netmon.agent.windows;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.collect.CollectorException;
import gov.mot.netmon.agent.collect.ReportBuilder;

/** Helpers to read sections of the PowerShell result safely. */
final class WinData {

    private WinData() {
    }

    /**
     * Rows of a section. Throws a CollectorException with an honest status when the section failed
     * or the whole query did not run.
     */
    static List<JsonNode> section(ReportBuilder b, String name) throws CollectorException {
        JsonNode root = b.platformData;
        if (root == null) {
            throw new CollectorException("UNAVAILABLE", "Windows data query did not run (see the 'windows-query' collector)");
        }
        JsonNode err = root.path("errors").get(name);
        if (err != null && !err.isNull()) {
            throw new CollectorException(statusFor(err.asText()), err.asText());
        }
        return rows(root.get(name));
    }

    /** PowerShell may serialise a one-element array as a plain object: accept both. */
    static List<JsonNode> rows(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        if (n == null || n.isNull() || n.isMissingNode()) return out;
        if (n.isArray()) n.forEach(out::add);
        else out.add(n);
        return out;
    }

    static String statusFor(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("access is denied") || m.contains("access denied") || m.contains("unauthorized")
                || m.contains("0x80041003")) return "PERMISSION_DENIED";
        if (m.contains("is not recognized") || m.contains("invalid class") || m.contains("not supported")
                || m.contains("0x80041010")) return "UNSUPPORTED";
        return "ERROR";
    }

    static Long lng(JsonNode row, String field) {
        JsonNode v = row.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) {
            BigInteger bi = v.bigIntegerValue();
            return bi.bitLength() > 63 ? Long.MAX_VALUE : bi.longValue();
        }
        try {
            return Long.parseLong(v.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double dbl(JsonNode row, String field) {
        JsonNode v = row.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.doubleValue();
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String str(JsonNode row, String field) {
        JsonNode v = row.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static Instant time(JsonNode row, String field) {
        String s = str(row, field);
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    static Long sum(Long... values) {
        long total = 0;
        boolean any = false;
        for (Long v : values) {
            if (v != null) {
                total += v;
                any = true;
            }
        }
        return any ? total : null;
    }
}
