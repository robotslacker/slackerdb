package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.server.streams.DownloadHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 把管理口返回的字节变成浏览器可点的下载链接。 */
final class DownloadSupport {

    private DownloadSupport() {
    }

    static Anchor anchor(String filename, byte[] bytes) {
        try {
            Path target = Files.createTempFile("dbdesk-", "-" + safeName(filename));
            Files.write(target, bytes);
            Anchor anchor = new Anchor(DownloadHandler.forFile(target.toFile()),
                    I18n.t("download.link", filename));
            anchor.setId("download-" + target.getFileName());
            return anchor;
        } catch (IOException e) {
            throw new IllegalStateException(I18n.t("download.prepareFailed", I18n.message(e)), e);
        }
    }

    private static String safeName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "download.bin";
        }
        String name = filename.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name.isBlank() ? "download.bin" : name;
    }
}
