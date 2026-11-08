package xyz.tcheeric.cashu.voucher.domain.ledger;

import java.nio.charset.StandardCharsets;

/**
 * The JSON writing rule of voucher ledger v2, written by hand so the bytes never depend on a
 * library's escaping defaults: no whitespace; escape exactly the quote, the backslash, and
 * U+0000..U+001F (short forms for b, t, n, f, r, otherwise a lowercase six-character escape);
 * everything else is written as itself. A lone surrogate is refused.
 */
final class LedgerJson {

    private final StringBuilder out = new StringBuilder();
    private boolean needComma;

    LedgerJson begin() {
        out.append('{');
        needComma = false;
        return this;
    }

    LedgerJson end() {
        out.append('}');
        needComma = true;
        return this;
    }

    LedgerJson key(String name) {
        if (needComma) {
            out.append(',');
        }
        string(out, name);
        out.append(':');
        needComma = false;
        return this;
    }

    LedgerJson str(String name, String value) {
        key(name);
        string(out, value);
        needComma = true;
        return this;
    }

    LedgerJson optStr(String name, String value) {
        return value == null || value.isEmpty() ? this : str(name, value);
    }

    LedgerJson num(String name, long value) {
        key(name);
        out.append(value);
        needComma = true;
        return this;
    }

    LedgerJson optNum(String name, Long value) {
        return value == null ? this : num(name, value);
    }

    /** Writes an already-serialised JSON value verbatim. */
    LedgerJson raw(String json) {
        if (needComma) {
            out.append(',');
        }
        out.append(json);
        needComma = true;
        return this;
    }

    @Override
    public String toString() {
        return out.toString();
    }

    /** Escapes {@code value} as a JSON string literal under the ledger writing rule. */
    static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        string(sb, value);
        return sb.toString();
    }

    static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void string(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("string contains a lone surrogate");
                }
                sb.append(c).append(value.charAt(++i));
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("string contains a lone surrogate");
            }
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u00").append(Character.forDigit(c >> 4, 16))
                                .append(Character.forDigit(c & 0xf, 16));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
