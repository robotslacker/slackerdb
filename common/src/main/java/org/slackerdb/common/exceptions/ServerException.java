package org.slackerdb.common.exceptions;

public class ServerException extends RuntimeException
{
    private String errorCode;
    private String errorMessage;

    public ServerException(Throwable cause)
    {
        super(cause);
    }

    public ServerException(String errorMessage)
    {
        super(errorMessage);
    }

    public ServerException(String errorCode, String errorMessage)
    {
        super(errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public String getErrorCode()
    {
        return errorCode;
    }

    public String getErrorMessage()
    {
        return errorMessage;
    }

    public ServerException(String errorMessage, Throwable cause)
    {
        super(errorMessage, cause);
    }
}
