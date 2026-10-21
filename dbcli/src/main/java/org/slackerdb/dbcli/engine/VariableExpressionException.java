package org.slackerdb.dbcli.engine;

/**
 * 变量替换的可读错误（不支持表达式，写错了要**明确报错**而不是静默留原文）。
 *
 * <p>触发场景：{@code ${a+b}} 这类含运算符/函数调用的替换目标。
 * 空名字（{@code ${}} / {@code ${ }}）、反斜杠转义与其它怪形态按普通文本原样保留，不算错误。
 * 消息一行、可直接打印到 stderr。</p>
 */
public class VariableExpressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VariableExpressionException(String message) {
        super(message);
    }

    public VariableExpressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
