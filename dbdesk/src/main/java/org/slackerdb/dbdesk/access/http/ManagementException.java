package org.slackerdb.dbdesk.access.http;

import org.slackerdb.dbdesk.i18n.Localized;
import org.slackerdb.dbdesk.i18n.LocalizedMessage;

/**
 * 管理口调用失败的统一异常。
 *
 * <p>带 HTTP 状态码与服务端错误信封（{@code retCode}/{@code retMsg}）——"连不上"和"服务端说没有"
 * 是两种要区别对待的失败：前者状态栏标红、后者在操作处就地提示。</p>
 *
 * <p>展示文案只携带 i18n key 与参数（{@link Localized}），由界面层按当前语言渲染；
 * {@code retMsg} 是服务端原文，作为参数原样拼进去，不再二次翻译。</p>
 */
public class ManagementException extends Exception implements Localized {

    private final int httpStatus;
    private final int retCode;
    private final String retMsg;
    private final LocalizedMessage detail;

    public ManagementException(LocalizedMessage detail, Throwable cause) {
        this(detail, 0, 0, null, cause);
    }

    public ManagementException(LocalizedMessage detail, int httpStatus, int retCode, String retMsg) {
        this(detail, httpStatus, retCode, retMsg, null);
    }

    private ManagementException(LocalizedMessage detail, int httpStatus, int retCode,
                                String retMsg, Throwable cause) {
        super(detail.messageKey(), cause);
        this.detail = detail;
        this.httpStatus = httpStatus;
        this.retCode = retCode;
        this.retMsg = retMsg;
    }

    @Override
    public String messageKey() {
        return detail.messageKey();
    }

    @Override
    public Object[] messageArgs() {
        return detail.messageArgs();
    }

    public int httpStatus() {
        return httpStatus;
    }

    public int retCode() {
        return retCode;
    }

    public String retMsg() {
        return retMsg;
    }

    /** 连不上/超时这类"服务端不在"的失败（UI 该标红而不是弹错）。 */
    public boolean transportFailure() {
        return httpStatus == 0;
    }
}
