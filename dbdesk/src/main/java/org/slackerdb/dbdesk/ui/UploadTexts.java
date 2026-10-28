package org.slackerdb.dbdesk.ui;

import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.UploadI18N;

/**
 * Vaadin {@link Upload} 自带文案的中文化。
 *
 * <p>{@code I18NProvider} 的 key 名属于组件内部实现，改版本就可能变；Upload 只有 4 处，
 * 直接显式配置更稳（不依赖 Vaadin 内部 key）。</p>
 */
final class UploadTexts {

    private UploadTexts() {
    }

    static void apply(Upload upload) {
        if (upload == null) {
            return;
        }
        UploadI18N i18n = new UploadI18N();
        i18n.setAddFiles(new UploadI18N.AddFiles()
                .setOne(I18n.t("upload.addFile"))
                .setMany(I18n.t("upload.addFiles")));
        i18n.setDropFiles(new UploadI18N.DropFiles()
                .setOne(I18n.t("upload.dropFile"))
                .setMany(I18n.t("upload.dropFiles")));
        i18n.setFile(new UploadI18N.File()
                .setRetry(I18n.t("upload.retry"))
                .setStart(I18n.t("upload.start"))
                .setRemove(I18n.t("upload.remove")));
        i18n.setError(new UploadI18N.Error()
                .setTooManyFiles(I18n.t("upload.error.tooManyFiles"))
                .setFileIsTooBig(I18n.t("upload.error.fileTooBig"))
                .setIncorrectFileType(I18n.t("upload.error.incorrectFileType")));
        i18n.setUploading(new UploadI18N.Uploading()
                .setStatus(new UploadI18N.Uploading.Status()
                        .setConnecting(I18n.t("upload.status.connecting"))
                        .setStalled(I18n.t("upload.status.stalled"))
                        .setProcessing(I18n.t("upload.status.processing"))
                        .setHeld(I18n.t("upload.status.held")))
                .setError(new UploadI18N.Uploading.Error()
                        .setServerUnavailable(I18n.t("upload.error.serverUnavailable"))
                        .setUnexpectedServerError(I18n.t("upload.error.unexpectedServerError"))
                        .setForbidden(I18n.t("upload.error.forbidden"))));
        upload.setI18n(i18n);
    }
}
