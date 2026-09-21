package org.slackerdb.dbserver.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CopyCsvReader} 的语义与增量正确性测试。
 *
 * <p>重点两件事：</p>
 * <ol>
 *   <li><b>语义</b>：与 PG COPY CSV 方言一致，且能区分"未加引号的空字段（NULL）"与 {@code ""}（空字符串）；</li>
 *   <li><b>增量</b>：同一份载荷按任意切点分片喂入，结果必须完全一致 —— 这是增量解析器最容易出错的地方
 *       （状态没有跨分片正确保留）。</li>
 * </ol>
 */
public class CopyCsvReaderTest {

    // ---------------------------------------------------------------- 语义

    private static List<String> parse(String payload) {
        return parseChunked(payload.getBytes(StandardCharsets.UTF_8), Integer.MAX_VALUE);
    }

    /** 以固定切片大小喂入，返回 {@code 值} / {@code <NULL>} / {@code <EOR>} 的序列。 */
    private static List<String> parseChunked(byte[] payload, int chunk) {
        List<String> out = new ArrayList<>();
        CopyCsvReader reader = new CopyCsvReader();
        int off = 0;
        while (off < payload.length) {
            int len = Math.min(chunk, payload.length - off);
            reader.append(payload, off, len);
            drain(reader, out);
            off += len;
        }
        reader.markEof();
        drain(reader, out);
        return out;
    }

    private static void drain(CopyCsvReader reader, List<String> out) {
        while (reader.nextRow(new CopyCsvReader.FieldSink() {
            @Override
            public void field(int off, int len, boolean quoted) {
                if (len == 0 && !quoted) {
                    out.add("<NULL>");
                } else {
                    out.add(new String(reader.buffer(), off, len, StandardCharsets.UTF_8));
                }
            }

            @Override
            public void endRow() {
                out.add("<EOR>");
            }
        })) {
            // keep going
        }
    }

    @Test
    void parsesPgCopyCsvDialect() {
        assertEquals(Arrays.asList("1", "a,b", "2.5", "<EOR>"), parse("1,\"a,b\",2.5\n"));
        assertEquals(Arrays.asList("1", "he said \"hi\"", "2.5", "<EOR>"), parse("1,\"he said \"\"hi\"\"\",2.5\n"));
        assertEquals(Arrays.asList("1", "line1\nline2", "2.5", "<EOR>"), parse("1,\"line1\nline2\",2.5\n"));
        assertEquals(Arrays.asList("1", "a", "<EOR>", "2", "b", "<EOR>"), parse("1,a\r\n2,b\r\n"));
        assertEquals(Arrays.asList("1", "a", "<EOR>"), parse("1,a"));
        assertEquals(Arrays.asList("1", "a", "<EOR>", "2", "b", "<EOR>"), parse("1,a\n\n2,b\n"));
        assertEquals(Arrays.asList(), parse("\n"));
        assertEquals(Arrays.asList("a", "b", "c", "d", "e", "<EOR>"), parse("a,b,c,d,e\n"));
        // 裸 \r 也当行尾
        assertEquals(Arrays.asList("1", "a", "<EOR>", "2", "b", "<EOR>"), parse("1,a\r2,b\r"));
        // 引号只在该字段的第一个字符才有特殊含义
        assertEquals(Arrays.asList("a\"b", "<EOR>"), parse("a\"b\n"));
    }

    @Test
    void distinguishesNullFromEmptyString() {
        // 未加引号的空字段 -> NULL
        assertEquals(Arrays.asList("1", "<NULL>", "<EOR>"), parse("1,\n"));
        // "" -> 空字符串（不是 NULL）
        assertEquals(Arrays.asList("1", "", "<EOR>"), parse("1,\"\"\n"));
        // 两者并存，必须能区分（这正是 commons-csv 做不到、也是本次行为变更的核心）
        assertEquals(Arrays.asList("1", "", "<EOR>", "2", "<NULL>", "<EOR>"), parse("1,\"\"\n2,\n"));
        // 整行都是空字段
        assertEquals(Arrays.asList("<NULL>", "<NULL>", "<EOR>"), parse(",\n"));
        // 空行的四周
        assertEquals(Arrays.asList("<NULL>", "", "<EOR>"), parse("\n,\"\"\n\n"));
    }

    @Test
    void rejectsMalformedInput() {
        // 引号未闭合
        assertThrows(CopyCsvReader.CsvFormatException.class, () -> parse("1,\"abc\n"));
        // 闭引号之后出现非分隔字符
        assertThrows(CopyCsvReader.CsvFormatException.class, () -> parse("1,\"abc\"x\n"));
        assertThrows(CopyCsvReader.CsvFormatException.class, () -> parse("1,\"abc\" \n"));
    }

    // ---------------------------------------------------------------- 增量正确性

    /**
     * 分片不变性：同一份载荷按 1..N 字节切片、以及随机切点喂入，结果必须一致。
     * 这是"一行被 TCP 分片切断"的等价场景。
     */
    @Test
    void chunkedFeedingProducesIdenticalResult() {
        String[] payloads = {
                "1,a,2.5\n2,\"b,b\",3.5\n3,\"he said \"\"hi\"\"\",4.5\n",
                "1,\"line1\nline2\",x\n2,\"\",\n3,,\n",
                "a\r\nb\r\n\"c\"\r\n",
                "1,\"\"\n2,\n\n3,z",
                "1,\"multi\nline\nwith \"\"quotes\"\" inside\"\n",
                "\n\n1,a\n\n2,b\n\n",
        };
        for (String payload : payloads) {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            List<String> expected = parseChunked(bytes, Integer.MAX_VALUE);
            for (int chunk = 1; chunk <= 12; chunk++) {
                assertEquals(expected, parseChunked(bytes, chunk),
                        "切片大小 " + chunk + " 时结果不一致，载荷=" + escape(payload));
            }
            // 随机切点
            Random random = new Random(42);
            for (int round = 0; round < 50; round++) {
                List<String> got = new ArrayList<>();
                CopyCsvReader reader = new CopyCsvReader();
                int off = 0;
                while (off < bytes.length) {
                    int len = 1 + random.nextInt(Math.min(7, bytes.length - off));
                    reader.append(bytes, off, len);
                    drain(reader, got);
                    off += len;
                }
                reader.markEof();
                drain(reader, got);
                assertEquals(expected, got, "随机切分时结果不一致，载荷=" + escape(payload));
            }
        }
    }

    private static String escape(String s) {
        return "\"" + s.replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
}
