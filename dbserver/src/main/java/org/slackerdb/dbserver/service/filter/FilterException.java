package org.slackerdb.dbserver.service.filter;

/**
 * filter 表达式解析/求值失败的异常。
 *
 * <p>消息直接面向 API 调用方：不包含堆栈、内部类名或 SQL；{@code position} 是
 * filter 文本中的 1 基字符位置（无法定位时为 0）。</p>
 */
public class FilterException extends Exception {

    private final int position;

    public FilterException(String message) {
        this(message, 0);
    }

    public FilterException(String message, int position) {
        super(message);
        this.position = position;
    }

    public int getPosition() {
        return position;
    }

    /** 构造带位置的异常；{@code position <= 0} 时不追加位置信息。 */
    public static FilterException at(String message, int position) {
        if (position > 0) {
            return new FilterException(message + " at position " + position + ".", position);
        }
        return new FilterException(message + ".", position);
    }
}
