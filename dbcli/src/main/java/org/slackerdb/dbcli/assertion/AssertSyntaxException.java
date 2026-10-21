package org.slackerdb.dbcli.assertion;

/**
 * 断言文本书写非法时抛出（例如只有一个操作数、比较符不认识、取值器越界）。
 *
 * <p>文案最终会被拼进状态行：{@code Assert fail. SyntaxError =>[<message>]}，
 * 所以 message 要能让人一眼看出错在哪。</p>
 */
public class AssertSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AssertSyntaxException(String message) {
        super(message);
    }

    public AssertSyntaxException(String message, Throwable cause) {
        super(message, cause);
    }
}
