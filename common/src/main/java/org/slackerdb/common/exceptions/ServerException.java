package org.slackerdb.common.exceptions;

/**
 * Slackerdb服务器异常类。
 * 表示服务器运行时发生的异常，包含错误代码和错误消息。
 *
 * <p>注意：当前实现中errorMessage字段与父类的message字段重复，建议重构。</p>
 */
public class ServerException extends RuntimeException
{
    /** 序列化版本UID */
    private static final long serialVersionUID = 1L;

    /** 错误代码 */
    private String errorCode;

    /** 错误消息（注意：与父类的message字段重复） */
    private String errorMessage;

    /**
     * 使用底层原因构造服务器异常。
     *
     * @param cause 导致此异常的原因
     */
    public ServerException(Throwable cause)
    {
        super(cause);
    }

    /**
     * 使用错误消息构造服务器异常。
     *
     * @param errorMessage 错误消息
     */
    public ServerException(String errorMessage)
    {
        super(errorMessage);
    }

    /**
     * 使用错误代码和错误消息构造服务器异常。
     *
     * @param errorCode 错误代码
     * @param errorMessage 错误消息
     */
    public ServerException(String errorCode, String errorMessage)
    {
        super(errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    /**
     * 获取错误代码。
     *
     * @return 错误代码
     */
    public String getErrorCode()
    {
        return errorCode;
    }

    /**
     * 获取错误消息。
     *
     * @return 错误消息
     */
    public String getErrorMessage()
    {
        return errorMessage;
    }

    /**
     * 使用错误消息和原因构造服务器异常。
     *
     * @param errorMessage 错误消息
     * @param cause 导致此异常的原因
     */
    public ServerException(String errorMessage, Throwable cause)
    {
        super(errorMessage, cause);
    }
}
