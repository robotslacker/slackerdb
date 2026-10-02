package org.slackerdb.dbserver.test.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeCString(body, portalName);
        writeCString(body, statementName);
        writeInt16(body, 0);                       // 参数格式码个数 = 0（全文本）
        writeInt16(body, 0);                       // 参数个数 = 0
        writeInt16(body, 0);                       // 结果格式码个数 = 0（全文本）
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
