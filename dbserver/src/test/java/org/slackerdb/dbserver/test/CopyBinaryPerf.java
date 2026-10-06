package org.slackerdb.dbserver.test;

import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.dbserver.server.DBInstance;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * {@code COPY ... FROM STDIN (FORMAT BINARY)} 入库路径的<b>可复现性能基准</b>。
 *
 * <p><b>为什么单独一个入口而不是 JUnit 用例</b>：性能数字必须能前后对比，而 surefire 只收集
 * {@code **}{@code /*Test.java}，把基准写成用例会让 {@code mvn test} 每次都跑百万行、且把
 * 机器噪声当成断言。因此这里是带 {@code main()} 的普通类，按需手工执行：</p>
 *
 * <pre>
 *   # 默认规模（每个场景 100 万行，3 轮，报告取每场景中位数）
 *   mvn -o -q -pl dbserver exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=org.slackerdb.dbserver.test.CopyBinaryPerf
 *
 *   # 自定义规模：-DcopyPerf.rows=200000 -DcopyPerf.rounds=5
 * </pre>
 *
 * <p><b>测的是什么</b>：从客户端 {@code CopyManager.copyIn()} 发起、到服务端把行写进 DuckDB 为止的
 * 端到端耗时，包含 PG 线协议往返、服务端解析、Appender 写入与事务提交。这正是"入库速度"。</p>
 *
 * <p><b>载荷怎么造</b>：本类自带一个 PG BINARY 编码器，<b>刻意不复用</b>
 * {@code PostgresSQLUtil.convertPGRowToByte}。原因是这份基准要独立于被测代码：
 * 如果编码与解码出自同一处，一旦二者一起偏离 PG 标准（历史上就发生过：时间戳纪元与
 * NUMERIC 符号位），往返测试依然会自洽通过，量出来的字节分布也不是真实客户端的。
 * 这里按 PG 线格式独立实现：基本类型大端、TIMESTAMP 为自 2000-01-01 的微秒、
 * NUMERIC 为 base-10000 数字组且负数符号位为 {@code 0x4000}。</p>
 *
 * <p>本类还有 {@code dumprow} 诊断入口，可打印单行载荷的逐字段布局。</p>
 *
 * <p><b>结果落在哪</b>：控制台打表，同时追加写入 {@code dbserver/target/copy-binary-perf/}，
 * 便于把"优化前 / 优化后"两次运行的数字直接对比存档。</p>
 */
public final class CopyBinaryPerf {

    // ==================================================================
    //  场景定义
    // ==================================================================

    /** 一个基准场景：目标表 DDL + 每行各列的载荷编码器。 */
    private interface Scenario {
        String name();

        String ddl();

        String copySql();

        /** 该场景的列数。 */
        int columnCount();

        /** 生成一行；返回的也是 PG BINARY 行载荷（不含行头列数）。 */
        void encode(AppendBuffer out, long rowIndex);

        /** 该场景里"参与校验"的列索引与期望值（用于确认优化没有改变写入语义）。 */
        String verificationSql();

        String expectedVerification();
    }

    /**
     * 场景一：混合类型宽表 —— 最接近真实业务导入。
     *
     * <p>列顺序刻意混排（定长/变长交错），这样"按列预计算派发"带来的收益才有代表性。</p>
     */
    private static final class MixedScenario implements Scenario {
        private static final String[] WORDS = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel"};

        @Override
        public String name() {
            return "mixed-8col";
        }

        @Override
        public String ddl() {
            return "CREATE OR REPLACE TABLE bench_mixed ("
                    + "id INTEGER, amount BIGINT, name VARCHAR(64), ratio DOUBLE, flag BOOLEAN,"
                    + " ts TIMESTAMP, price NUMERIC(18,4), score REAL)";
        }

        @Override
        public String copySql() {
            return "COPY bench_mixed FROM STDIN WITH (FORMAT BINARY)";
        }

        @Override
        public int columnCount() {
            return 8;
        }

        @Override
        public void encode(AppendBuffer out, long r) {
            out.writeInt(4).writeInt((int) (r % 100000));
            out.writeInt(8).writeLong(1_000_000L + r);
            String name = WORDS[(int) (r % WORDS.length)] + "-" + (r % 1000);
            out.writeVarchar(name);
            out.writeInt(8).writeLong(Double.doubleToLongBits(3.14159 * (r % 97)));
            out.writeInt(1).writeByte((r & 1) == 0 ? 1 : 0);
            // TIMESTAMP: 自 2000-01-01 的微秒（PG 线格式）
            out.writeInt(8).writeLong(PG_EPOCH_MICROS + (1_700_000_000_000_000L + r * 1000L));
            out.writeNumeric(new BigDecimal("1234.5678").add(new BigDecimal(r % 100).movePointLeft(4)));
            out.writeInt(4).writeInt(Float.floatToIntBits(1.5f * (r % 13)));
        }

        @Override
        public String verificationSql() {
            return "SELECT count(*), sum(id), sum(amount), min(name), max(name), count(ts)"
                    + " FROM bench_mixed";
        }

        @Override
        public String expectedVerification() {
            return null;   // 由 head 运行填充
        }
    }

    /**
     * 场景二：定长窄表 —— 大批量数值导入的典型形态，边界情况最少，最能反映纯写入吞吐。
     */
    private static final class NumericScenario implements Scenario {
        @Override
        public String name() {
            return "numeric-3col";
        }

        @Override
        public String ddl() {
            return "CREATE OR REPLACE TABLE bench_num (a BIGINT, b BIGINT, c DOUBLE)";
        }

        @Override
        public String copySql() {
            return "COPY bench_num FROM STDIN WITH (FORMAT BINARY)";
        }

        @Override
        public int columnCount() {
            return 3;
        }

        @Override
        public void encode(AppendBuffer out, long r) {
            out.writeInt(8).writeLong(r);
            out.writeInt(8).writeLong(r * 7919L);
            out.writeInt(8).writeLong(Double.doubleToLongBits(r * 0.5));
        }

        @Override
        public String verificationSql() {
            return "SELECT count(*), sum(a), sum(b) FROM bench_num";
        }

        @Override
        public String expectedVerification() {
            return null;
        }
    }

    /**
     * 场景三：边界语义表 —— NULL、空串、各类型 0 值/极值。
     *
     * <p>这个场景不为了跑得快，而是为了确保优化后的快路径与慢路径（NULL 分支、定长校验分支）
     * 在同一次运行里都被覆盖到，并且结果可校验。</p>
     */
    private static final class EdgeScenario implements Scenario {
        @Override
        public String name() {
            return "edges-null";
        }

        @Override
        public String ddl() {
            return "CREATE OR REPLACE TABLE bench_edge (id INTEGER, s VARCHAR, d DOUBLE, ts TIMESTAMP, b BOOLEAN)";
        }

        @Override
        public String copySql() {
            return "COPY bench_edge FROM STDIN WITH (FORMAT BINARY)";
        }

        @Override
        public int columnCount() {
            return 5;
        }

        @Override
        public void encode(AppendBuffer out, long r) {
            int kind = (int) (r % 5);
            // id
            if (kind == 1) {
                out.writeNull();
            } else {
                out.writeInt(4).writeInt((int) r);
            }
            // s: NULL / 空串 / 普通串 / 多字节 UTF-8 / 长串
            switch (kind) {
                case 1 -> out.writeNull();
                case 2 -> out.writeVarchar("");
                case 3 -> out.writeVarchar("中文-值-テスト-" + r);
                case 4 -> out.writeVarchar("x".repeat(200));
                default -> out.writeVarchar("plain");
            }
            // d
            if (kind == 1) {
                out.writeNull();
            } else {
                out.writeInt(8).writeLong(Double.doubleToLongBits(kind == 0 ? 0.0d : r * 1.5d));
            }
            // ts
            if (kind == 1) {
                out.writeNull();
            } else {
                out.writeInt(8).writeLong(PG_EPOCH_MICROS);
            }
            // b
            if (kind == 1) {
                out.writeNull();
            } else {
                out.writeInt(1).writeByte(kind == 0 ? 0 : 1);
            }
        }

        @Override
        public String verificationSql() {
            return "SELECT count(*),"
                    + " count(*) FILTER (WHERE id IS NULL),"
                    + " count(*) FILTER (WHERE s IS NULL),"
                    + " count(*) FILTER (WHERE s = ''),"
                    + " count(*) FILTER (WHERE d IS NULL),"
                    + " count(*) FILTER (WHERE ts IS NULL),"
                    + " count(*) FILTER (WHERE b IS NULL),"
                    + " count(*) FILTER (WHERE b),"
                    + " count(*) FILTER (WHERE ts = TIMESTAMP '2000-01-01 00:00:00')"
                    + " FROM bench_edge";
        }

        @Override
        public String expectedVerification() {
            return null;
        }
    }

    // ==================================================================
    //  PG BINARY 载荷编码（自包含，不复用被测代码）
    // ==================================================================

    /** 2000-01-01T00:00:00Z 相对 1970-01-01 的微秒数；PG 的 TIMESTAMP 二进制基准点。 */
    private static final long PG_EPOCH_MICROS = 946_684_800_000_000L;

    private static final byte[] COPY_BINARY_HEADER = {
            0x50, 0x47, 0x43, 0x4F, 0x50, 0x59, 0x0A, (byte) 0xFF, 0x0D, 0x0A, 0x00,
            0, 0, 0, 0,
            0, 0, 0, 0
    };

    /** 大端写入缓冲；行级复用，避免每格分配。 */
    private static final class AppendBuffer {
        private byte[] buf = new byte[1 << 16];
        private int pos;

        void reset() {
            pos = 0;
        }

        int size() {
            return pos;
        }

        byte[] array() {
            return buf;
        }

        private void ensure(int extra) {
            int need = pos + extra;
            if (need > buf.length) {
                int cap = buf.length;
                while (cap < need) {
                    cap <<= 1;
                }
                buf = java.util.Arrays.copyOf(buf, cap);
            }
        }

        AppendBuffer writeByte(int v) {
            ensure(1);
            buf[pos++] = (byte) v;
            return this;
        }

        AppendBuffer writeShort(int v) {
            ensure(2);
            buf[pos++] = (byte) (v >>> 8);
            buf[pos++] = (byte) v;
            return this;
        }

        AppendBuffer writeInt(int v) {
            ensure(4);
            buf[pos++] = (byte) (v >>> 24);
            buf[pos++] = (byte) (v >>> 16);
            buf[pos++] = (byte) (v >>> 8);
            buf[pos++] = (byte) v;
            return this;
        }

        AppendBuffer writeLong(long v) {
            ensure(8);
            for (int i = 7; i >= 0; i--) {
                buf[pos++] = (byte) (v >>> (8 * i));
            }
            return this;
        }

        /** 字段头（长度）+ 字节体。 */
        AppendBuffer writeBytes(byte[] src, int off, int len) {
            writeInt(len);
            ensure(len);
            System.arraycopy(src, off, buf, pos, len);
            pos += len;
            return this;
        }

        /** VARCHAR：长度 + UTF-8 字节。 */
        AppendBuffer writeVarchar(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            return writeBytes(b, 0, b.length);
        }

        /** NULL 字段：长度 -1。 */
        AppendBuffer writeNull() {
            return writeInt(-1);
        }

        /**
         * PG NUMERIC：int32 字段长度 + [int16 数字组数、int16 weight、int16 sign、int16 dscale、base-10000 数字组]。
         * 只支持 4 位小数、绝对值 &lt; 10000 的输入（基准数据的取值范围）。
         *
         * <p>符号位按 PG 标准：{@code 0x4000} 表示负数，{@code 0x0000} 表示正数。</p>
         */
        AppendBuffer writeNumeric(BigDecimal value) {
            BigDecimal v = value.setScale(4, java.math.RoundingMode.HALF_UP);
            long unscaled = v.unscaledValue().longValueExact();      // 例：1234.5678 -> 12345678
            int sign = unscaled < 0 ? 0x4000 : 0x0000;
            unscaled = Math.abs(unscaled);
            // 拆成 base-10000 组，低位在前，最后反转
            int[] groups = new int[8];
            int n = 0;
            while (unscaled > 0) {
                groups[n++] = (int) (unscaled % 10000);
                unscaled /= 10000;
            }
            // 字段长度 = 4 个头字段 + n 个数字组
            writeInt(8 + n * 2);
            // weight = 最高位组的位权；dscale=4 时小数占 1 组
            int weight = n - 1 - 1;
            writeShort(n);
            writeShort(weight);
            writeShort(sign);
            writeShort(4);
            for (int i = n - 1; i >= 0; i--) {
                writeShort(groups[i]);
            }
            return this;
        }
    }

    /** 造出一次 COPY 的完整 PG BINARY 载荷。 */
    private static byte[] buildPayload(Scenario sc, int rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try {
            out.write(COPY_BINARY_HEADER);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        AppendBuffer row = new AppendBuffer();
        for (int r = 0; r < rows; r++) {
            row.reset();
            sc.encode(row, r);
            // 行头：int16 列数（大端）；随后是该行的各列（每列 int32 长度 + 数据）
            out.write((sc.columnCount() >>> 8) & 0xFF);
            out.write(sc.columnCount() & 0xFF);
            out.write(row.array(), 0, row.size());
        }
        // 结束标志：int16 -1
        out.write(0xFF);
        out.write(0xFF);
        return out.toByteArray();
    }

    /** 自检：把造好的载荷按 PG BINARY 结构重新扫一遍，确保基准数据本身是合规的。 */
    private static void selfCheckPayload(Scenario sc, byte[] payload) {
        int pos = 19;
        long row = 1;
        long totalRows = 0;
        while (true) {
            int rawColCount = ((payload[pos] & 0xFF) << 8) | (payload[pos + 1] & 0xFF);
            pos += 2;
            if (rawColCount == 0xFFFF) {
                // int16 -1：COPY 数据流结束标志
                break;
            }
            int colCount = rawColCount;
            if (colCount != sc.columnCount()) {
                throw new AssertionError("[" + sc.name() + "] row " + row + " has colCount " + colCount
                        + " but scenario declares " + sc.columnCount());
            }
            for (int i = 0; i < colCount; i++) {
                int lenAt = pos;
                int len = ((payload[pos] & 0xFF) << 24) | ((payload[pos + 1] & 0xFF) << 16)
                        | ((payload[pos + 2] & 0xFF) << 8) | (payload[pos + 3] & 0xFF);
                pos += 4;
                if (len == -1) {
                    continue;
                }
                if (len < 0 || pos + len > payload.length) {
                    throw new AssertionError("[" + sc.name() + "] row " + row + " col " + i
                            + " length " + len + " at offset " + lenAt + " overruns payload");
                }
                pos += len;
            }
            row++;
            totalRows++;
        }
        if (pos != payload.length) {
            throw new AssertionError("[" + sc.name() + "] payload has " + (payload.length - pos)
                    + " trailing bytes after the terminator");
        }
        System.out.println("  [" + sc.name() + "] payload self-check OK: " + totalRows + " rows, "
                + payload.length + " bytes");
    }

    // ==================================================================
    //  运行器
    // ==================================================================

    private static int dbPort;
    private static DBInstance dbInstance;
    private static final StringBuilder REPORT = new StringBuilder();

    /** 诊断用：把 MixedScenario 第一行的每个字节连同一行内偏移打出来。 */
    private static void dumpRow() {
        Scenario sc = new MixedScenario();
        AppendBuffer row = new AppendBuffer();
        sc.encode(row, 0);
        byte[] b = java.util.Arrays.copyOf(row.array(), row.size());
        System.out.println("row bytes=" + b.length + ", declared columns=" + sc.columnCount());
        for (int i = 0; i < b.length; i += 8) {
            StringBuilder hex = new StringBuilder();
            StringBuilder asc = new StringBuilder();
            for (int j = i; j < Math.min(i + 8, b.length); j++) {
                hex.append(String.format("%02X ", b[j]));
                asc.append(b[j] >= 32 && b[j] < 127 ? (char) b[j] : '.');
            }
            System.out.printf("  %3d: %-24s %s%n", i, hex.toString().trim(), asc.toString());
        }
        // 按列解析
        int pos = 0;
        for (int c = 0; c < sc.columnCount(); c++) {
            int len = ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16)
                    | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            System.out.println("  col " + c + " lenAt=" + pos + " len=" + len + " bodyAt=" + (pos + 4));
            pos += 4 + Math.max(len, 0);
        }
        System.out.println("  consumed=" + pos + " of " + b.length);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "dumprow".equals(args[0])) {
            dumpRow();
            return;
        }
        int rows = Integer.getInteger("copyPerf.rows", 1_000_000);
        int rounds = Integer.getInteger("copyPerf.rounds", 3);
        int warmup = Integer.getInteger("copyPerf.warmup", 0);

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        useRealPgJdbc();

        ServerConfiguration cfg = new ServerConfiguration();
        cfg.setPort(0);
        cfg.setData("copyperf");
        cfg.setLog_level("WARN");
        cfg.setSqlHistory("OFF");
        dbInstance = new DBInstance(cfg);
        dbInstance.start();
        dbPort = cfg.getPort();

        header(String.format("COPY BINARY ingest benchmark — rows/round=%d rounds=%d warmup=%d jvm=%s",
                rows, rounds, warmup, System.getProperty("java.version")));
        header("host cpu=" + System.getProperty("os.arch") + " cpus=" + Runtime.getRuntime().availableProcessors()
                + " maxHeap=" + (Runtime.getRuntime().maxMemory() >> 20) + "MB");

        List<Scenario> scenarios = List.of(new MixedScenario(), new NumericScenario(), new EdgeScenario());
        List<String> summary = new ArrayList<>();

        try (Connection conn = connect()) {
            for (Scenario sc : scenarios) {
                // 正确性基线：先跑一次，把结果作为后续每轮的期望值
                prepare(conn, sc);
                byte[] payload = buildPayload(sc, rows);
                selfCheckPayload(sc, payload);
                copy(conn, sc, payload);
                String expected = verify(conn, sc);
                long expectedCount = count(conn, tableOf(sc));

                for (int i = 0; i < warmup; i++) {
                    prepare(conn, sc);
                    copy(conn, sc, payload);
                }

                double[] millis = new double[rounds];
                long[] rowCounts = new long[rounds];
                for (int i = 0; i < rounds; i++) {
                    prepare(conn, sc);
                    long t0 = System.nanoTime();
                    copy(conn, sc, payload);
                    long t1 = System.nanoTime();
                    millis[i] = (t1 - t0) / 1e6;
                    rowCounts[i] = count(conn, tableOf(sc));
                    String actual = verify(conn, sc);
                    if (rowCounts[i] != expectedCount || !actual.equals(expected)) {
                        throw new AssertionError("[" + sc.name() + "] round " + i
                                + " produced different data than the baseline.\n  expected rows="
                                + expectedCount + " verify=" + expected
                                + "\n  actual   rows=" + rowCounts[i] + " verify=" + actual);
                    }
                }

                double best = Double.MAX_VALUE;
                double median = median(millis);
                for (double m : millis) {
                    best = Math.min(best, m);
                }
                double mbPerSec = (payload.length / 1048576.0) / (median / 1000.0);
                double rowsPerSec = rows / (median / 1000.0);

                String line = String.format(
                        "%-14s payload=%7.1fMB rows=%,9d  median=%7.0fms best=%7.0fms  %,10.0f rows/s  %7.1f MB/s  rounds=%s",
                        sc.name(), payload.length / 1048576.0, rows, median, best, rowsPerSec, mbPerSec,
                        formatRounds(millis));
                record(line);
                summary.add(line);
            }
        } finally {
            dbInstance.stop();
        }

        header("summary");
        for (String s : summary) {
            record(s);
        }
        Path out = writeReport();
        System.out.println();
        System.out.println("report written to " + out);

        // 显式结束进程。
        //
        // 必须这样做的原因：本基准用 `mvn exec:java` 运行，而 exec:java 默认在 **Maven 自己的 JVM 内**
        // 执行 main（不 fork 新进程）。服务端 PostgresServer 的监听线程是非守护线程
        // （PostgresServer.java:640 的 `while(true) sleep(1)` 只有在被 interrupt 时才退出），
        // 于是 main 返回后 JVM 仍被这个线程吊住、Maven 永不结束。
        // 结果已经 flush 到文件与控制台，这里直接以 0 退出，让基准可以放进脚本里无人值守运行。
        System.out.flush();
        System.exit(0);
    }

    private static String tableOf(Scenario sc) {
        // DDL 里表名紧跟 "TABLE "
        String ddl = sc.ddl();
        int at = ddl.indexOf("TABLE ") + "TABLE ".length();
        int end = ddl.indexOf(' ', at);
        return ddl.substring(at, end);
    }

    private static void prepare(Connection conn, Scenario sc) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sc.ddl());
        }
    }

    private static void copy(Connection conn, Scenario sc, byte[] payload) throws SQLException {
        try {
            new CopyManager((BaseConnection) conn).copyIn(sc.copySql(), new ByteArrayInputStream(payload));
        } catch (IOException e) {
            throw new SQLException(e);
        }
    }

    private static long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String verify(Connection conn, Scenario sc) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sc.verificationSql())) {
            int n = rs.getMetaData().getColumnCount();
            StringBuilder sb = new StringBuilder();
            rs.next();
            for (int i = 1; i <= n; i++) {
                if (i > 1) {
                    sb.append('|');
                }
                sb.append(rs.getString(i));
            }
            return sb.toString();
        }
    }

    /**
     * 用真实 pgjdbc（{@code jdbc:postgresql://}）+ {@code org.postgresql.copy.CopyManager} 连接，
     * 与真实客户端一致。
     *
     * <p><b>为什么要先注销本项目自带的驱动</b>：{@code org.slackerdb.jdbc.Driver} 也会被
     * {@code DriverManager} 自动加载（dbdriver 在测试类路径上），而它只接受
     * {@code jdbc:slackerdb://}；{@code DriverManager.getConnection} 对每个已注册驱动都会调用
     * {@code connect()}（并不先用 {@code acceptsURL()} 过滤），于是它会抢先抛
     * {@code Unable to parse URL jdbc:postgresql://...}，真 pgjdbc 便没有机会接手。
     * 本基准要测的是"真实 PG 客户端的 BINARY COPY"，所以这里显式把它摘掉。</p>
     */
    private static void useRealPgJdbc() {
        java.util.Enumeration<java.sql.Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            java.sql.Driver d = drivers.nextElement();
            if (d.getClass().getName().startsWith("org.slackerdb.jdbc.")) {
                try {
                    DriverManager.deregisterDriver(d);
                } catch (SQLException e) {
                    throw new IllegalStateException("failed to deregister " + d.getClass().getName(), e);
                }
            }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + dbPort + "/copyperf", "", "");
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int n = copy.length;
        return (n % 2 == 1) ? copy[n / 2] : (copy[n / 2 - 1] + copy[n / 2]) / 2.0;
    }

    private static String formatRounds(double[] millis) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < millis.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format("%.0f", millis[i]));
        }
        return sb.append(']').toString();
    }

    private static void header(String s) {
        record("");
        record("=== " + s);
    }

    private static void record(String s) {
        System.out.println(s);
        REPORT.append(s).append(System.lineSeparator());
    }

    private static Path writeReport() throws IOException {
        // 输出目录可配置。默认 dbserver/target/copy-binary-perf —— 显式带上模块目录，
        // 因为 `mvn -pl dbserver exec:java` 的工作目录是 **reactor 根**（不是模块目录），
        // 直接用相对路径 "target/..." 会把报告写到根目录的 target 下，反而找不到。
        Path dir = Paths.get(System.getProperty("copyPerf.outDir",
                "dbserver" + java.io.File.separator + "target" + java.io.File.separator + "copy-binary-perf"));
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = dir.resolve("copy-binary-perf-" + stamp + ".txt");
        Files.write(file, REPORT.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.write(dir.resolve("latest.txt"), REPORT.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return file.toAbsolutePath();
    }

    private CopyBinaryPerf() {
    }
}
