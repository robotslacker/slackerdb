package org.slackerdb.dbserver.server;

import java.io.*;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;

public class FileHandlerHelper {
    // ============================================================
    // 安全加固：将下载、上传、查看限制在专用的隔离目录中
    // ============================================================

    // 下载基础目录（只能下载此目录下的文件）
    private static final Path DOWNLOAD_BASE_DIR = Path.of("./downloads").toAbsolutePath().normalize();

    // 上传基础目录（只能上传到此目录下）
    private static final Path UPLOAD_BASE_DIR = Path.of("./uploads").toAbsolutePath().normalize();

    // 查看基础目录（只能查看此目录下的文件，如日志）
    private static final Path VIEW_BASE_DIR = Path.of("./logs").toAbsolutePath().normalize();

    // lines 参数的最大允许值，防止滥用
    private static final int MAX_LINES = 10000;

    // 每次 transferTo 的最大传输字节，避免单次调用受实现限制（如 2GB）
    private static final long TRANSFER_CHUNK_SIZE = 8L * 1024 * 1024; // 8MB

    // 禁止的文件名（Windows 设备名等）
    private static final Set<String> WINDOWS_DEVICE_NAMES = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    // Range header 解析： bytes=start-end
    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d*)-(\\d*)");

    // 文件名中禁止的字符（Windows 上）
    private static final Pattern INVALID_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");

    /**
     * 安全校验文件名，防止路径穿越攻击。
     *
     * @param filename 用户传入的文件名
     * @param baseDir  允许访问的基础目录
     * @return 解析后的安全路径
     * @throws SecurityException 如果路径不安全
     */
    private static Path resolveSafePath(String filename, Path baseDir) throws SecurityException {
        if (filename == null || filename.isBlank()) {
            throw new SecurityException("Filename is empty");
        }

        // 检查是否包含路径穿越字符
        if (filename.contains("..")) {
            throw new SecurityException("Path traversal detected: '..' is not allowed");
        }

        // 检查是否包含空字符（null byte injection）
        if (filename.contains("\0")) {
            throw new SecurityException("Null byte injection detected");
        }

        // 检查 Windows 备用数据流
        if (filename.contains(":") && !filename.contains(":/") && !filename.startsWith("http")) {
            // 允许 Windows 盘符如 C:/，但禁止备用数据流如 file.txt:stream
            if (filename.matches(".*:[^/\\\\].*")) {
                throw new SecurityException("Alternate data stream detected");
            }
        }

        // 检查文件名是否包含路径分隔符（不允许使用绝对路径或跨目录访问）
        // 注意：我们允许子目录，但必须经过 normalize 验证
        Path resolved = baseDir.resolve(filename).normalize();

        // 关键检查：解析后的路径必须以 baseDir 开头
        if (!resolved.startsWith(baseDir)) {
            throw new SecurityException("Path traversal detected: resolved path is outside base directory");
        }

        // 额外检查：确保 resolved 不是 baseDir 本身（防止访问目录本身）
        if (resolved.equals(baseDir)) {
            throw new SecurityException("Access to base directory itself is not allowed");
        }

        // 检查 Windows 设备名
        String fileNameOnly = resolved.getFileName().toString();
        String nameWithoutExt = fileNameOnly.contains(".") ?
            fileNameOnly.substring(0, fileNameOnly.lastIndexOf('.')) : fileNameOnly;
        if (WINDOWS_DEVICE_NAMES.contains(nameWithoutExt.toUpperCase())) {
            throw new SecurityException("Windows device name is not allowed");
        }

        return resolved;
    }

    /**
     * 对文件名进行安全处理，移除或替换危险字符
     */
    private static String sanitizeFileName(String filename) {
        // 只保留文件名部分（去掉路径）
        String cleanName = Path.of(filename).getFileName().toString();
        // 替换危险字符为下划线
        cleanName = INVALID_FILENAME_CHARS.matcher(cleanName).replaceAll("_");
        // 去除首尾空格和点
        cleanName = cleanName.trim();
        while (cleanName.startsWith(".")) {
            cleanName = cleanName.substring(1);
        }
        while (cleanName.endsWith(".")) {
            cleanName = cleanName.substring(0, cleanName.length() - 1);
        }
        // 如果处理后为空，使用默认名
        if (cleanName.isEmpty()) {
            cleanName = "unnamed_file";
        }
        return cleanName;
    }

    // 提供文件下载服务
    public static void handleFileDownload(Context ctx) throws IOException {
        String filename = ctx.queryParam("filename");
        if (filename == null || filename.isBlank())
        {
            ctx.status(400);
            ctx.result("Missing filename parameter for download.");
            return;
        }

        // 安全解析路径
        Path filePath;
        try {
            filePath = resolveSafePath(filename, DOWNLOAD_BASE_DIR);
        } catch (SecurityException e) {
            ctx.status(403).result("Download denied: " + e.getMessage());
            return;
        }

        // 检查文件是否存在且为普通文件
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            ctx.status(404).result("Download error. File does not exist or is not a plain file.");
            return;
        }

        long fileLength = Files.size(filePath);
        String contentType = Optional.ofNullable(Files.probeContentType(filePath)).orElse("application/octet-stream");

        // ETag / Last-Modified (简单实现)
        String eTag = Files.getLastModifiedTime(filePath).toMillis() + "-" + fileLength;
        String lastModified = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneId.of("GMT"))
                .format(Instant.ofEpochMilli(Files.getLastModifiedTime(filePath).toMillis()));

        ctx.header("Accept-Ranges", "bytes");
        ctx.header("ETag", eTag);
        ctx.header("Last-Modified", lastModified);
        ctx.header("Cache-Control", "private, max-age=0, no-transform");

        String rangeHeader = ctx.header("Range");
        if (rangeHeader == null) {
            // 完整下载
            ctx.status(200);
            ctx.header("Content-Type", contentType);
            ctx.header("Content-Length", String.valueOf(fileLength));
            ctx.header("Content-Disposition", "attachment; filename=\"" + encodeFileName(filePath.getFileName().toString()) + "\"");
            // 写出全文件
            try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r");
                 FileChannel fc = raf.getChannel()) {
                 streamFile(fc, 0, fileLength, ctx);
            }
            return;
        }

        // 处理 Range 请求（仅单区间），例如： Range: bytes=100-200  或 bytes=100-   或 bytes=-500
        Matcher m = RANGE_PATTERN.matcher(rangeHeader);
        if (!m.matches()) {
            ctx.status(416).header("Content-Range", "bytes */" + fileLength).result("Invalid Range");
            return;
        }

        String startGroup = m.group(1);
        String endGroup = m.group(2);
        long start;
        long end;

        if (startGroup.isEmpty()) {
            // suffix range: bytes=-500  => last 500 bytes
            long suffixLength = Long.parseLong(endGroup);
            if (suffixLength <= 0) {
                ctx.status(416).header("Content-Range", "bytes */" + fileLength).result("Invalid Range");
                return;
            }
            if (suffixLength >= fileLength) {
                start = 0;
            } else {
                start = fileLength - suffixLength;
            }
            end = fileLength - 1;
        } else {
            start = Long.parseLong(startGroup);
            if (endGroup.isEmpty()) {
                end = fileLength - 1;
            } else {
                end = Long.parseLong(endGroup);
            }
        }

        // 验证范围
        if (start > end || start >= fileLength) {
            ctx.status(416).header("Content-Range", "bytes */" + fileLength).result("Requested Range Not Satisfiable");
            return;
        }

        if (end >= fileLength) end = fileLength - 1;

        long contentLength = end - start + 1;

        ctx.status(206); // Partial Content
        ctx.header("Content-Type", contentType);
        ctx.header("Content-Length", String.valueOf(contentLength));
        ctx.header("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
        ctx.header("Content-Disposition", "attachment; filename=\"" + encodeFileName(filePath.getFileName().toString()) + "\"");

        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r");
             FileChannel fc = raf.getChannel())
        {
            streamFile(fc, start, contentLength, ctx);
        }
    }

    // 提供文件上传服务
    public static void handleFileUpload(Context ctx) throws IOException {
        String targetName = ctx.queryParam("filename");
        if (targetName == null || targetName.isBlank())
        {
            ctx.status(400);
            ctx.result("Missing filename parameter for upload.");
            return;
        }

        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            ctx.status(400).result("Missing file field");
            return;
        }

        // 对文件名进行安全处理
        String safeFilename = sanitizeFileName(targetName);

        // 安全解析路径
        Path target;
        try {
            target = resolveSafePath(safeFilename, UPLOAD_BASE_DIR);
        } catch (SecurityException e) {
            ctx.status(403).result("Upload denied: " + e.getMessage());
            return;
        }

        // 自动创建目录
        Files.createDirectories(target.getParent());

        // 保存文件（覆盖同名文件）
        try (InputStream is = file.content()) {
            Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        ctx.json("uploaded: " + target.toAbsolutePath());
    }

    // 把 fileChannel 中 [start, start+count) 部分传输到 HTTP 响应输出流。
    // 使用 transferTo 分块循环，避免单次传输大于平台限制的问题。
    private static void streamFile(FileChannel fc, long start, long count, Context ctx)  {
        long transferred = 0;
        // 获取底层 output stream 的 FileDescriptor? 直接使用 ctx.res().getOutputStream()
        try (var out = ctx.res().getOutputStream()) {
            while (transferred < count) {
                long chunk = Math.min(TRANSFER_CHUNK_SIZE, count - transferred);
                long pos = start + transferred;

                // transferTo 可能不会一次把所有数据传输完，返回实际传输字节数
                long n = fc.transferTo(pos, chunk, java.nio.channels.Channels.newChannel(out));
                if (n <= 0) {
                    // 保守回退到流拷贝方式（极少见）
                    fc.position(pos);
                    byte[] buffer = new byte[(int)Math.min(chunk, 64 * 1024)];
                    var inputStream = java.nio.channels.Channels.newInputStream(fc);
                    int read;
                    long remaining = chunk;
                    while (remaining > 0 && (read = inputStream.read(buffer, 0, (int)Math.min(buffer.length, remaining))) != -1) {
                        out.write(buffer, 0, read);
                        remaining -= read;
                        transferred += read;
                    }
                } else {
                    transferred += n;
                }
            }
            out.flush();
        } catch (IOException ignored) {
            // 客户端中断连接的时候会抛异常，忽略即可
        }
    }

    private static String encodeFileName(String filename) {
        try {
            // 简单处理文件名中的特殊字符，浏览器会更友好
            return URLEncoder.encode(filename, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        } catch (Exception e) {
            return filename;
        }
    }

    public static void handleFileView(Context ctx) {
        String filename = ctx.queryParam("filename");
        if (filename == null || filename.isBlank())
        {
            ctx.status(400);
            ctx.result("Missing filename parameter for view.");
            return;
        }

        int linesParam = ctx.queryParamAsClass("lines", Integer.class).getOrDefault(100);
        if (linesParam <= 0) {
            ctx.status(400).result("lines must be positive");
            return;
        }
        int lines = Math.min(linesParam, MAX_LINES);

        // 安全解析路径
        Path filePath;
        try {
            filePath = resolveSafePath(filename, VIEW_BASE_DIR);
        } catch (SecurityException e) {
            ctx.status(403).result("View denied: " + e.getMessage());
            return;
        }

        try {
            File file = filePath.toFile();
            if (!file.exists() || !file.isFile()) {
                ctx.status(404).result("file not found");
                return;
            }

            List<String> lastLines = readLastNLines(file, lines);
            ctx.json(lastLines);

        } catch (IllegalArgumentException e) {
            ctx.status(400).result("invalid filename");
        } catch (Exception e) {
            ctx.status(500).result("internal error: " + e.getMessage());
        }
    }

    /**
     * 从文件尾向前读取最后 n 行（按换行分行），返回从上到下的行列表（即顺序为文件中自然顺序）。
     * 注意：
     * - 使用 UTF-8 解码（若日志使用其它编码，请修改解码方式）
     * - 对非常大的单行或包含复杂 multi-byte 字符可能有极少数边界情况，但在常见日志场景下表现良好
     */
    private static List<String> readLastNLines(File file, int numLines) throws Exception {
        LinkedList<String> result = new LinkedList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long fileLength = raf.length();
            if (fileLength == 0) {
                return result;
            }

            long pointer = fileLength - 1;
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            int readLines = 0;

            while (pointer >= 0 && readLines < numLines) {
                raf.seek(pointer);
                int b = raf.read();
                if (b == '\n') {
                    // 当遇到 \n 时，已收集了当前行的字节（反向）
                    byte[] lineBytes = reverse(byteArrayOutputStream.toByteArray());
                    String line = new String(lineBytes, StandardCharsets.UTF_8);
                    // 去掉可能的回车
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    result.addFirst(line);
                    byteArrayOutputStream.reset();
                    readLines++;
                } else {
                    byteArrayOutputStream.write(b);
                }
                pointer--;
            }

            // 处理文件首部的第一行（如果没有以换行结束而循环结束）
            if (readLines < numLines && byteArrayOutputStream.size() > 0) {
                byte[] lineBytes = reverse(byteArrayOutputStream.toByteArray());
                String line = new String(lineBytes, StandardCharsets.UTF_8);
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                result.addFirst(line);
            }

            // 如果结果里有空行的占位（因为末尾有换行），并且超过 numLines，截断
            while (result.size() > numLines) {
                result.removeFirst();
            }

            return result;
        }
    }

    // 把字节数组倒序（我们从文件尾向前读取字节，所以每行的字节是反向收集的）
    private static byte[] reverse(byte[] src) {
        int n = src.length;
        byte[] dst = new byte[n];
        for (int i = 0; i < n; i++) {
            dst[i] = src[n - 1 - i];
        }
        return dst;
    }
}
