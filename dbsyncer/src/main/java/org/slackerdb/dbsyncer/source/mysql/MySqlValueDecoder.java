package org.slackerdb.dbsyncer.source.mysql;

import com.github.shyiko.mysql.binlog.event.deserialization.json.JsonBinary;
import org.slackerdb.dbsyncer.model.SourceTable;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MySQL binlog 值 → DuckDB 可绑定值。 */
public final class MySqlValueDecoder {

    private static final Pattern MEMBER = Pattern.compile("'((?:[^']|'')*)'");

    private MySqlValueDecoder() {
    }

    public static Object decode(SourceTable.SourceColumn column, Object value) {
        if (value == null || column == null) {
            return value;
        }
        String definition = column.typeDefinition == null ? "" : column.typeDefinition.trim();
        String upper = definition.toUpperCase(Locale.ROOT);
        String base = column.typeName == null ? "" : column.typeName.toLowerCase(Locale.ROOT);

        if (value instanceof byte[] bytes) {
            if ("bit".equals(base) || upper.startsWith("BIT(")) {
                long number = 0;
                for (byte b : bytes) {
                    number = (number << 8) | (b & 0xFFL);
                }
                return column.size <= 1 ? (number != 0) : number;
            }
            if (upper.startsWith("JSON") || "JSON".equalsIgnoreCase(column.typeName)) {
                try {
                    return JsonBinary.parseAsString(bytes);
                } catch (Exception e) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }
            if (isBinary(base, upper)) {
                return padBinary(column, bytes);
            }
            return new String(bytes, charset(column));
        }
        if (value instanceof Number number && upper.contains("UNSIGNED")) {
            return normalizeUnsigned(definition, number);
        }
        if (upper.startsWith("ENUM(")) {
            List<String> members = members(definition);
            int index = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
            return index >= 1 && index <= members.size() ? members.get(index - 1) : "";
        }
        if (upper.startsWith("SET(")) {
            long mask = value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
            List<String> members = members(definition);
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < members.size(); i++) {
                if ((mask & (1L << i)) != 0) {
                    selected.add(members.get(i));
                }
            }
            return String.join(",", selected);
        }
        if (value instanceof BitSet bits) {
            long number = 0;
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                number |= 1L << i;
            }
            return number;
        }
        if (value instanceof java.util.Date date && !(value instanceof java.sql.Date)
                && !(value instanceof java.sql.Time) && !(value instanceof Timestamp)) {
            return new Timestamp(date.getTime());
        }
        return value;
    }

    private static boolean isBinary(String base, String definition) {
        return base.contains("binary") || base.contains("blob") || base.equals("bit")
                || definition.startsWith("BINARY(") || definition.startsWith("VARBINARY(");
    }

    private static Object padBinary(SourceTable.SourceColumn column, byte[] bytes) {
        if (column.size > bytes.length && column.typeName != null
                && column.typeName.toLowerCase(Locale.ROOT).startsWith("binary")) {
            byte[] padded = new byte[column.size];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            return padded;
        }
        return bytes;
    }

    private static Charset charset(SourceTable.SourceColumn column) {
        if (column.characterSet == null || column.characterSet.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(column.characterSet);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static Object normalizeUnsigned(String definition, Number value) {
        String base = definition.trim().toLowerCase(Locale.ROOT);
        long raw = value.longValue();
        if (base.startsWith("tinyint")) {
            return (int) (raw & 0xFFL);
        }
        if (base.startsWith("smallint")) {
            return (int) (raw & 0xFFFFL);
        }
        if (base.startsWith("mediumint")) {
            return (int) (raw & 0xFFFFFFL);
        }
        if (base.startsWith("int")) {
            return raw & 0xFFFFFFFFL;
        }
        if (base.startsWith("bigint")) {
            BigInteger result = BigInteger.valueOf(raw);
            return raw < 0 ? result.add(BigInteger.ONE.shiftLeft(64)) : result;
        }
        return value;
    }

    private static List<String> members(String definition) {
        List<String> result = new ArrayList<>();
        int open = definition.indexOf('(');
        int close = definition.lastIndexOf(')');
        String body = open >= 0 && close > open ? definition.substring(open + 1, close) : definition;
        Matcher matcher = MEMBER.matcher(body);
        while (matcher.find()) {
            result.add(matcher.group(1).replace("''", "'"));
        }
        return result;
    }
}
