package org.slackerdb.dbdesk.access.http;

/**
 * 管理口调用失败的统一异常。
 *
 * <p>带 HTTP 状态码与服务端错误信封（{@code retCode}/{@code retMsg}）——"连不上"和"服务端说没有"
 * 是两种要区别对待的失败：前者状态栏标红、后者在操作处就地提示。</p>
 */
public class ManagementException extends Exception {

    private final int httpStatus;
    private final int retCode;
    private final String retMsg;

    public ManagementException(String message, Throwable cause) {
        this(message, 0, 0, null, cause);
    }

    public ManagementException(String message, int httpStatus, int retCode, String retMsg) {
        this(message, httpStatus, retCode, retMsg, null);
    }

    private ManagementException(String message, int httpStatus, int retCode, String retMsg, Throwable cause) {
        super(message + (retMsg == null || retMsg.isBlank() ? "" : "（" + retMsg + "）"), cause);
        this.httpStatus = httpStatus;
        this.retCode = retCode;
        this.retMsg = retMsg;
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
