package org.slackerdb.dbcli.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * UTF-8 严格读取（dbcli 只支持 UTF-8）。
 *
 * <p>两条约定：</p>
 * <ol>
 *   <li><b>不用</b> {@code Files.readString(path, charset)}：本机 JDK 上它对非 UTF-8 会静默读错；</li>
 *   <li>非法字节**报可读错误**，而不是替换成占位符后继续（"读成乱码还报成功"比报错更难排查）。</li>
 * </ol>
 *
 * <p>包可见：只有引擎内部（脚本文件、map 文件）需要它。</p>
 */
final class Utf8Text {

    private Utf8Text() {
    }

    /**
     * 按 UTF-8 严格解码整个文件。
     *
     * <p><b>容忍并去掉开头的 UTF-8 BOM</b>：Windows 上记事本、PowerShell 的 {@code Set-Content -Encoding UTF8}
     * 等工具默认会写 BOM，若不处理，脚本第一行会变成 {@code \uFEFF_connect} 而报"语法错误"——
     * 这类文件在 Windows 上极其常见，容忍它比要求用户改工具更合理。</p>
     *
     * @throws IOException 读不了或不是合法 UTF-8（消息里带可读原因）
     */
    static String read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException notUtf8) {
            throw new IOException("dbcli only supports UTF-8: " + notUtf8.getMessage(), notUtf8);
        }
    }

    /** 是否以 UTF-8 BOM（{@code EF BB BF}）开头。 */
    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF;
    }

    /** 按行切分：{@code CRLF}/{@code LF} 都算行结束符；以行结束符结尾时不补空行。 */
    static List<String> readLines(Path file) throws IOException {
        return splitLines(read(file));
    }

    /** 按行切分（同一套规则，供文本输入复用）；行首的 {@code \uFEFF} 也去掉。 */
    static List<String> splitLines(String text) {
        String body = text;
        if (!body.isEmpty() && body.charAt(0) == '\uFEFF') {
            body = body.substring(1);
        }
        if (body.endsWith("\n")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.endsWith("\r")) {
            body = body.substring(0, body.length() - 1);
        }
        return List.of(body.split("\r?\n", -1));
    }
}
