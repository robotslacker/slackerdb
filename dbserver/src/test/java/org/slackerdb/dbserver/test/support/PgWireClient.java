package org.slackerdb.dbserver.test.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 极简 PostgreSQL V3 线协议客户端（测试用）。
 *
 * <p>存在的理由：协议层的很多契约（报文的种类、顺序、有没有多余的 ReadyForQuery、
 * 名字里的结尾 0 是否被正确剥掉）只有读原始报文才能断言，JDBC 驱动会把这些细节抹平。
 * 本仓库此前在 11 个测试类里各写了一份这样的客户端（约 1500 行重复代码，且各自的
 * 解析器还有细微差异），新用例请复用这一个。</p>
 *
 * <p>报文构造刻意与真实驱动保持一致，例如 {@link #sendClose(char, String)} 会像
 * pgjdbc / dbdriver 的 {@code sendCloseStatement}/{@code sendClosePortal} 那样
 * 在名字后写出结尾 0 —— 服务端若把该 0 当成名字的一部分，缓存 key 就会对不上，
 * Close 会变成静默空操作。</p>
 */
public class PgWireClient implements AutoCloseable {

    /** 一个已读入内存的协议报文：{@code type} + 去掉长度字段后的报文体。 */
    public static final class Frame {
        public final char type;
        public final byte[] body;

        Frame(char type, byte[] body) {
            this.type = type;
            this.body = body;
        }

        /** 按大端读取报文体里的一个 Int32。 */
        public int int32(int offset) {
            return ((body[offset] & 0xFF) << 24)
                    | ((body[offset + 1] & 0xFF) << 16)
                    | ((body[offset + 2] & 0xFF) << 8)
                    | (body[offset + 3] & 0xFF);
        }

        /** 按大端读取报文体里的一个 Int16。 */
        public int int16(int offset) {
            return ((body[offset] & 0xFF) << 8) | (body[offset + 1] & 0xFF);
        }

        /** 读取从 {@code offset} 开始、以 0 结尾的字符串。 */
        public String cstring(int offset) {
            int end = offset;
            while (end < body.length && body[end] != 0) {
                end++;
            }
            return new String(body, offset, end - offset, StandardCharsets.UTF_8);
        }

        /** 报文体解码（用于 CommandComplete 的 tag、ErrorResponse 的字段等）。 */
        public String text() {
            int length = body.length;
            if (length > 0 && body[length - 1] == 0) {
                length--;
            }
            return new String(body, 0, length, StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return type + "(" + body.length + ")";
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final List<Frame> handshakeFrames = new ArrayList<>();
    private int backendPid = -1;
    private int backendSecret = -1;

    /**
     * 建立连接并完成 Startup 握手（读到第一个 ReadyForQuery 为止）。
     *
     * @param database StartupMessage 里的 database 参数（服务端要求必须存在）
     */
    public PgWireClient(String host, int port, String database, int timeoutMs) throws IOException {
        this(host, port, database, Map.of(), timeoutMs);
    }

    /**
     * 同上，但允许在 StartupMessage 里附加任意启动参数。
     *
     * <p><b>为什么需要</b>：有些契约只体现在握手报文的参数里，用 JDBC 驱动测不到——
     * 例如客户端把 {@code search_path} 放进启动包（asyncpg 的 {@code server_settings}、
     * Npgsql 的 {@code Search Path} 就是这么做的），pgjdbc/dbdriver 则只支持
     * {@code options=-c ...}，走的是另一个键。</p>
     *
     * @param extraStartupParameters 附加的启动参数（键值都会写进 StartupMessage）
     */
    public PgWireClient(String host, int port, String database, Map<String, String> extraStartupParameters,
                        int timeoutMs) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        in = socket.getInputStream();

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeInt32(body, 196608);                 // 协议版本 3.0
        writeCString(body, "user");
        writeCString(body, "test");
        writeCString(body, "database");
        writeCString(body, database);
        for (Map.Entry<String, String> parameter : extraStartupParameters.entrySet()) {
            writeCString(body, parameter.getKey());
            writeCString(body, parameter.getValue());
        }
        body.write(0);

        byte[] bodyBytes = body.toByteArray();
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeInt32(packet, bodyBytes.length + 4);
        packet.writeBytes(bodyBytes);
        write(packet.toByteArray());

        handshakeFrames.addAll(readUntilReadyForQuery());
        for (Frame frame : handshakeFrames) {
            if (frame.type == 'K' && frame.body.length >= 8) {
                backendPid = frame.int32(0);
                backendSecret = frame.int32(4);
            }
        }
    }

    /** 握手期收到的全部报文（含 AuthenticationOk/ParameterStatus/BackendKeyData/ReadyForQuery）。 */
    public List<Frame> handshakeFrames() {
        return handshakeFrames;
    }

    /** 服务端为本会话分配的 pid（BackendKeyData 里的值，等于服务端的 sessionId）。 */
    public int backendPid() {
        return backendPid;
    }

    /** BackendKeyData 里的 secret（取消报文用）。 */
    public int backendSecret() {
        return backendSecret;
    }

    // ------------------------------------------------------------------ 发送

    public void sendQuery(String sql) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeCString(body, sql);
        writeFrame('Q', body.toByteArray());
    }

    public void sendParse(String statementName, String sql) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeCString(body, statementName);
        writeCString(body, sql);
        writeInt16(body, 0);                       // 不声明参数类型
        writeFrame('P', body.toByteArray());
    }

    /**
     * Parse，并显式声明一个参数类型（服务端据此决定 Bind 时如何解码）。
     *
     * @param parameterTypeOid 参数的类型 OID（例如 23 = int4、20 = int8）
     */
    public void sendParse(String statementName, String sql, int parameterTypeOid) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeCString(body, statementName);
        writeCString(body, sql);
        writeInt16(body, 1);                       // 声明 1 个参数类型
        writeInt32(body, parameterTypeOid);
        writeFrame('P', body.toByteArray());
    }

    /**
     * Bind，并带一个<b>二进制格式</b>的参数。
     *
     * <p>用于验证绑定阶段的类型/长度校验（例如按 int4 声明却只发 3 字节，服务端应回 22P03）。</p>
     */
    public void sendBindWithBinaryParam(String portalName, String statementName, byte[] payload)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeCString(body, portalName);
        writeCString(body, statementName);
        writeInt16(body, 1);                       // 参数格式码个数 = 1
        writeInt16(body, 1);                       // 1 = 二进制
        writeInt16(body, 1);                       // 参数个数 = 1
        writeInt32(body, payload.length);
        body.writeBytes(payload);
        writeInt16(body, 0);                       // 结果格式码个数 = 0
        writeFrame('B', body.toByteArray());
    }

    public void sendBind(String portalName, String statementName) throws IOException {
        sendBind(portalName, statementName, new int[0]);
    }

    /**
     * 故意发送"参数格式码个数与参数个数不匹配"的 Bind（协议违规：个数只能是 0 / 1 / 参数个数）。
     *
     * <p>参数值一律写成 NULL(-1)，让用例只聚焦于格式码这一维度。</p>
     */
    public void sendBindWithFormatCodeCountMismatch(String portalName, String statementName,
                                                    int[] formatCodes, int parameterCount) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeCString(body, portalName);
        writeCString(body, statementName);
        writeInt16(body, formatCodes.length);
        for (int format : formatCodes) {
            writeInt16(body, format);
        }
        writeInt16(body, parameterCount);
        for (int i = 0; i < parameterCount; i++) {
            writeInt32(body, -1);                 // NULL 参数值
        }
        writeInt16(body, 0);                      // 结果格式码个数 = 0
        writeFrame('B', body.toByteArray());
    }

    /**
     * 故意发送"参数声明长度超出报文实际长度"的 Bind（截断报文）。
     *
     * <p>旧实现用 {@code Arrays.copyOfRange} 静默补 0，会把截断报文当成合法值；
     * 现在必须回 {@code 08P01}。</p>
     */
    public void sendBindWithTruncatedParameter(String portalName, String statementName,
                                               int declaredLength, byte[] actualBytes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeCString(body, portalName);
        writeCString(body, statementName);
        writeInt16(body, 0);                      // 参数格式码个数 = 0（全文本）
        writeInt16(body, 1);                      // 参数个数 = 1
        writeInt32(body, declaredLength);         // 声明长度（大于实际给出的字节数）
        body.writeBytes(actualBytes);
        writeFrame('B', body.toByteArray());
    }

    /**
     * Bind，并显式指定<b>结果列</b>格式码。
     *
     * <p>协议规则（PG 文档 Bind 报文）：结果格式码个数为 0 → 所有结果列用默认格式（<b>文本</b>）；
     * 为 1 → 该值广播到所有列；等于列数 → 逐列。需要二进制结果的客户端必须显式声明 1，
     * 服务端不得自行假设（声明与实际字节必须一致，否则客户端静默错值）。</p>
     *
     * @param resultFormats 结果列格式码；空数组 = 全部文本
     */
    public void sendBind(String portalName, String statementName, int[] resultFormats) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeCString(body, portalName);
        writeCString(body, statementName);
        writeInt16(body, 0);                       // 参数格式码个数 = 0（全文本）
        writeInt16(body, 0);                       // 参数个数 = 0
        writeInt16(body, resultFormats.length);    // 结果格式码个数
        for (int format : resultFormats) {
            writeInt16(body, format);              // 0 = 文本，1 = 二进制
        }
        writeFrame('B', body.toByteArray());
    }

    /** @param kind 'S' = 语句，'P' = 门户 */
    public void sendDescribe(char kind, String name) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(kind);
        writeCString(body, name);
        writeFrame('D', body.toByteArray());
    }

    public void sendExecute(String portalName, int maxRows) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeCString(body, portalName);
        writeInt32(body, maxRows);
        writeFrame('E', body.toByteArray());
    }

    /**
     * Close 报文：与真实驱动一致，<b>名字后面写出结尾 0</b>。
     *
     * @param kind 'S' = 关闭预备语句，'P' = 关闭门户
     */
    public void sendClose(char kind, String name) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(kind);
        writeCString(body, name);
        writeFrame('C', body.toByteArray());
    }

    public void sendSync() throws IOException {
        writeFrame('S', new byte[0]);
    }

    public void sendFlush() throws IOException {
        writeFrame('H', new byte[0]);
    }

    /** Terminate：客户端要求关闭连接（服务端不得因为"等待 Sync"而把它丢掉）。 */
    public void sendTerminate() throws IOException {
        writeFrame('X', new byte[0]);
    }

    /**
     * CopyData：COPY IN 子协议里发送一段数据。
     *
     * <p>与 {@link #sendQuery} 的动作不同：COPY 建立之后（客户端已收到 {@code CopyInResponse}）
     * 客户端改为发送 {@code 'd'} 报文，收尾发 {@link #sendCopyDone()}。</p>
     */
    public void sendCopyData(byte[] payload) throws IOException {
        writeFrame('d', payload == null ? new byte[0] : payload);
    }

    /** CopyDone：告诉服务端 COPY IN 的数据发完了。 */
    public void sendCopyDone() throws IOException {
        writeFrame('c', new byte[0]);
    }

    // ------------------------------------------------------------------ 接收

    /** 读一个报文；连接已关闭时返回 null。 */
    public Frame readFrame() throws IOException {
        int type = in.read();
        if (type == -1) {
            return null;
        }
        byte[] lengthBytes = readFully(4);
        int length = ((lengthBytes[0] & 0xFF) << 24)
                | ((lengthBytes[1] & 0xFF) << 16)
                | ((lengthBytes[2] & 0xFF) << 8)
                | (lengthBytes[3] & 0xFF);
        if (length < 4) {
            throw new IOException("报文体长度非法: " + length);
        }
        return new Frame((char) type, readFully(length - 4));
    }

    /** 一直读到 ReadyForQuery('Z')，返回沿途所有报文（含该 Z）。 */
    public List<Frame> readUntilReadyForQuery() throws IOException {
        List<Frame> frames = new ArrayList<>();
        while (true) {
            Frame frame = readFrame();
            if (frame == null) {
                throw new IOException("连接在 ReadyForQuery 之前被关闭，已收到：" + types(frames));
            }
            frames.add(frame);
            if (frame.type == 'Z') {
                return frames;
            }
        }
    }

    /** 把报文序列压成类型字符串（例如 {@code "1tTZ"}），便于断言顺序。 */
    public static String types(List<Frame> frames) {
        StringBuilder builder = new StringBuilder();
        for (Frame frame : frames) {
            builder.append(frame.type);
        }
        return builder.toString();
    }

    /** 取指定类型的报文（按出现顺序）。 */
    public static List<Frame> ofType(List<Frame> frames, char type) {
        List<Frame> matched = new ArrayList<>();
        for (Frame frame : frames) {
            if (frame.type == type) {
                matched.add(frame);
            }
        }
        return matched;
    }

    // ------------------------------------------------------------------ 结果集解析

    /**
     * {@code ReadyForQuery('Z')} 里的事务状态字节：{@code 'I'}（空闲）/ {@code 'T'}（事务块中）/
     * {@code 'E'}（事务块已被中止）。
     */
    public static char readyStatus(List<Frame> frames) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            Frame frame = frames.get(i);
            if (frame.type == 'Z' && frame.body.length > 0) {
                return (char) (frame.body[0] & 0xFF);
            }
        }
        throw new IllegalArgumentException("报文序列里没有 ReadyForQuery: " + types(frames));
    }

    /**
     * 取第一个 {@code ErrorResponse('E')} 里某个字段的值。
     *
     * @param wanted 字段类型：{@code 'C'} = SQLSTATE，{@code 'M'} = 消息
     * @return 字段值；没有 ErrorResponse、或没有该字段时返回 {@code null}
     */
    public static String errorField(List<Frame> frames, char wanted) {
        for (Frame frame : frames) {
            if (frame.type != 'E') {
                continue;
            }
            int pos = 0;
            while (pos < frame.body.length && frame.body[pos] != 0) {
                char fieldType = (char) (frame.body[pos] & 0xFF);
                pos++;
                int end = pos;
                while (end < frame.body.length && frame.body[end] != 0) {
                    end++;
                }
                if (fieldType == wanted) {
                    return new String(frame.body, pos, end - pos, StandardCharsets.UTF_8);
                }
                pos = end + 1;
            }
        }
        return null;
    }

    /**
     * {@code RowDescription('T')} 里的一列。
     *
     * <p>{@link #formatCode} 是<b>契约</b>：它声明该列的字节该怎么解（0 = 文本，1 = 二进制）。
     * 服务端必须保证声明与实际发出去的字节一致 —— 这正是本仓库出过问题的地方
     * （简单查询路径声明 text，却对定长类型发了二进制）。</p>
     */
    public static final class FieldDesc {
        public final String name;
        public final int tableOid;
        public final int attributeNumber;
        public final int typeOid;
        public final int typeSize;
        public final int typeModifier;
        public final int formatCode;

        FieldDesc(String name, int tableOid, int attributeNumber, int typeOid,
                  int typeSize, int typeModifier, int formatCode) {
            this.name = name;
            this.tableOid = tableOid;
            this.attributeNumber = attributeNumber;
            this.typeOid = typeOid;
            this.typeSize = typeSize;
            this.typeModifier = typeModifier;
            this.formatCode = formatCode;
        }

        @Override
        public String toString() {
            return name + "(oid=" + typeOid + ", size=" + typeSize + ", format=" + formatCode + ")";
        }
    }

    /** 解析 {@code RowDescription('T')} 报文体。 */
    public static List<FieldDesc> parseRowDescription(Frame frame) {
        if (frame.type != 'T') {
            throw new IllegalArgumentException("不是 RowDescription 报文: " + frame.type);
        }
        List<FieldDesc> fields = new ArrayList<>();
        int columnCount = frame.int16(0);
        int pos = 2;
        for (int i = 0; i < columnCount; i++) {
            int nameEnd = pos;
            while (nameEnd < frame.body.length && frame.body[nameEnd] != 0) {
                nameEnd++;
            }
            String name = new String(frame.body, pos, nameEnd - pos, StandardCharsets.UTF_8);
            pos = nameEnd + 1;
            int tableOid = frame.int32(pos);
            int attributeNumber = frame.int16(pos + 4);
            int typeOid = frame.int32(pos + 6);
            int typeSize = frame.int16(pos + 10);
            int typeModifier = frame.int32(pos + 12);
            int formatCode = frame.int16(pos + 16);
            pos += 18;
            fields.add(new FieldDesc(name, tableOid, attributeNumber, typeOid, typeSize,
                    typeModifier, formatCode));
        }
        return fields;
    }

    /**
     * 解析 {@code DataRow('D')} 的列值（原始字节）。
     *
     * <p>返回列表里的 {@code null} 表示 SQL NULL（长度字段为 -1）。
     * 这些字节是文本还是二进制<b>取决于同一结果集 RowDescription 里对应的 formatCode</b>。</p>
     */
    public static List<byte[]> dataRowValues(Frame frame) {
        if (frame.type != 'D') {
            throw new IllegalArgumentException("不是 DataRow 报文: " + frame.type);
        }
        List<byte[]> values = new ArrayList<>();
        int columnCount = frame.int16(0);
        int pos = 2;
        for (int i = 0; i < columnCount; i++) {
            int length = frame.int32(pos);
            pos += 4;
            if (length == -1) {
                values.add(null);
                continue;
            }
            byte[] value = new byte[length];
            System.arraycopy(frame.body, pos, value, 0, length);
            pos += length;
            values.add(value);
        }
        return values;
    }

    /** 把一列的值按文本格式解出来（仅在 formatCode==0 时有意义）。 */
    public static String textValue(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    /**
     * 模拟“客户端被外部强杀”：不发 Terminate，直接以 RST 断开（SO_LINGER=0）。
     *
     * <p>与 {@link #close()} 的差别只在 TCP 层的收尾方式（RST vs FIN）：两者服务端都只会看到
     * “连接没了”。本仓库的断连用例统一用它，避免误以为客户端做过任何礼貌收尾。</p>
     */
    public void abortConnection() throws IOException {
        try {
            socket.setSoLinger(true, 0);
        } catch (java.net.SocketException ignored) {
            // 部分平台/实现不允许改 SO_LINGER，退化为普通 close 即可（对服务端同样只是"连接没了"）
        }
        socket.close();
    }

    /**
     * 发送一个"带类型字节"的报文（用于构造本服务端不实现/不应支持的报文，例如 fastpath FunctionCall）。
     */
    public void sendFrameOfType(char type, byte[] body) throws IOException {
        writeFrame(type, body);
    }

    /**
     * 只做"GSSENCRequest + StartupMessage"这一段握手，验证服务端是否按 PG 语义
     * 对 GSSENCRequest 回<b>单个字节 {@code 'N'}</b>，并且随后的启动包能正常完成。
     *
     * <p>为什么需要单独实现：{@link #PgWireClient(String, int, String, int)} 的构造函数
     * 直接发 StartupMessage，测不到"加密协商被拒绝后仍能继续握手"这条路径。</p>
     *
     * @return {@code true} = 收到 'N' 且启动完成（读到 ReadyForQuery）
     */
    public static boolean startupAfterGssEncRequest(String host, int port, String database, int timeoutMs)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream socketOut = socket.getOutputStream();
            InputStream socketIn = socket.getInputStream();

            // GSSENCRequest：Int32(8) + Int32(80877104)
            socketOut.write(new byte[]{0, 0, 0, 8, 0x04, (byte) 0xD2, 0x16, 0x30});
            socketOut.flush();
            if (socketIn.read() != 'N') {
                return false;               // 期望单个 'N'（不支持 GSSAPI 加密）
            }

            // 随后正常发 StartupMessage
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeInt32(body, 196608);
            writeCString(body, "user");
            writeCString(body, "test");
            writeCString(body, "database");
            writeCString(body, database);
            body.write(0);
            byte[] bodyBytes = body.toByteArray();
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            writeInt32(packet, bodyBytes.length + 4);
            packet.writeBytes(bodyBytes);
            socketOut.write(packet.toByteArray());
            socketOut.flush();

            // 读到 ReadyForQuery('Z') 即成功；中途的 ErrorResponse 视为失败
            while (true) {
                int type = socketIn.read();
                if (type == -1) {
                    return false;
                }
                byte[] lengthBytes = readFully(socketIn, 4);
                int length = ((lengthBytes[0] & 0xFF) << 24) | ((lengthBytes[1] & 0xFF) << 16)
                        | ((lengthBytes[2] & 0xFF) << 8) | (lengthBytes[3] & 0xFF);
                readFully(socketIn, length - 4);
                if (type == 'Z') {
                    return true;
                }
                if (type == 'E') {
                    return false;
                }
            }
        }
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read == -1) {
                throw new IOException("连接提前关闭（期望再读 " + (length - offset) + " 字节）");
            }
            offset += read;
        }
        return buffer;
    }

    // ------------------------------------------------------------------ 内部

    private void writeFrame(char type, byte[] body) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(type);
        writeInt32(frame, 4 + body.length);
        frame.writeBytes(body);
        write(frame.toByteArray());
    }

    private void write(byte[] data) throws IOException {
        socket.getOutputStream().write(data);
        socket.getOutputStream().flush();
    }

    private byte[] readFully(int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read == -1) {
                throw new IOException("连接提前关闭（期望再读 " + (length - offset) + " 字节）");
            }
            offset += read;
        }
        return buffer;
    }

    private static void writeCString(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static void writeInt16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
