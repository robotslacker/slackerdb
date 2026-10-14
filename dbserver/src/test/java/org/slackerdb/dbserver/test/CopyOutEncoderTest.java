package org.slackerdb.dbserver.test;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.slackerdb.dbserver.sql.CopyDialect;
import org.slackerdb.dbserver.sql.CopyOutEncoder;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CopyOutEncoder} 的**往返**单元测试：走公开的 {@code encodeRow}，
 * 用真实 text/CSV 方言，用动态代理桩掉 ResultSet，不依赖服务端。
 *
 * <p>导出必须能被导入侧（{@code CopyCsvReader}）读回原值，所以这里断言的字节形态
 * 就是"再导入一遍"的输入形态。</p>
 */
public class CopyOutEncoderTest {

    /** 用动态代理桩出一个"单列"的 ResultSet：getString/getBytes 返回给定值。 */
    private static ResultSet resultSetOf(Object value) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getString" -> value == null ? null : value.toString();
            case "getBytes" -> value;
            default -> throw new UnsupportedOperationException(
                    "encodeRow 不应调用 " + method.getName());
        };
        return (ResultSet) Proxy.newProxyInstance(
                CopyOutEncoderTest.class.getClassLoader(), new Class<?>[]{ResultSet.class}, handler);
    }

    /** 单列元数据：列类型名可控（决定是否按二进制列处理）。 */
    private static ResultSetMetaData metaDataOf(String typeName) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> 1;
            case "getColumnTypeName" -> typeName;
            case "getColumnName" -> "c1";
            default -> throw new UnsupportedOperationException(
                    "encodeRow 不应调用 " + method.getName());
        };
        return (ResultSetMetaData) Proxy.newProxyInstance(
                CopyOutEncoderTest.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class}, handler);
    }

    private static CopyDialect textDialect() {
        return CopyDialect.fromOptions(new JSONObject());
    }

    private static CopyDialect csvDialect() {
        JSONObject options = new JSONObject();
        options.put("FORMAT", "csv");
        return CopyDialect.fromOptions(options);
    }

    private static String encode(CopyDialect dialect, String typeName, Object value) throws Exception {
        CopyOutEncoder encoder = new CopyOutEncoder(metaDataOf(typeName), dialect);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encoder.encodeRow(resultSetOf(value), out);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String encodeText(String value) throws Exception {
        return encode(textDialect(), "TEXT", value);
    }

    private static String encodeCsv(String value) throws Exception {
        return encode(csvDialect(), "TEXT", value);
    }

    // ---------------------------------------------------------------- text

    @Test
    void textEscapesControlCharacters() throws Exception {
        assertEquals("a\\tb\n", encodeText("a\tb"));
        assertEquals("a\\nb\n", encodeText("a\nb"));
        assertEquals("a\\rb\n", encodeText("a\rb"));
    }

    @Test
    void textDoublesEveryBackslash() throws Exception {
        // 反斜杠无条件翻倍（PG 输出即如此）。这是唯一能保证往返无损的形态：
        //  - 两个反斜杠 → 四个反斜杠字节（读回两个）
        //  - 字面量 `\N` → `\\N`（读回字面量，而不是被当成 NULL）
        assertEquals("\\\\\n", encodeText("\\"));
        assertEquals("\\\\\\\\\n", encodeText("\\\\"));
        assertEquals("a\\\\\\\\b\n", encodeText("a\\\\b"));
        assertEquals("\\\\N\n", encodeText("\\N"));
    }

    @Test
    void textNullMarkerIsWrittenRaw() throws Exception {
        // NULL 必须原样写成 `\N`：若走转义会变成 `\\N`，读回来就成了**字面量** \N（值与 NULL 互换）
        assertEquals("\\N\n", encodeText(null));
    }

    // ---------------------------------------------------------------- csv

    @Test
    void csvQuotesWhenNeeded() throws Exception {
        assertEquals("plain\n", encodeCsv("plain"));
        assertEquals("\"has,comma\"\n", encodeCsv("has,comma"));
        assertEquals("\"has\"\"quote\"\n", encodeCsv("has\"quote"));
        assertEquals("\"line\nbreak\"\n", encodeCsv("line\nbreak"));
    }

    @Test
    void csvDistinguishesEmptyStringFromNull() throws Exception {
        // 空串必须加引号，否则读侧会把未加引号的空字段当成 NULL
        assertEquals("\"\"\n", encodeCsv(""));
        // NULL → 未加引号的空字段
        assertEquals("\n", encodeCsv(null));
    }

    // ---------------------------------------------------------------- blob

    @Test
    void blobIsWrittenAsPostgresHexText() throws Exception {
        // PG 的 bytea 文本表示：\x + 十六进制。
        // 注意这里**不经过** writeTextField（否则反斜杠会被翻倍成 \\x）。
        // 是否真能往返由 CopyOutTest 的 BLOB 往返用例验证。
        assertEquals("\\xcafebabe\n",
                encode(textDialect(), "BLOB", new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}));
    }

    @Test
    void blobNullIsNull() throws Exception {
        assertEquals("\\N\n", encode(textDialect(), "BLOB", null));
    }
}
