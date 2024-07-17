package org.slackerdb.exceptions;

public class ServerException extends Exception
{
    private final int    errorCode;

    public ServerException(int errorCode, String errorMessage)
    {
        super(errorMessage);
        this.errorCode = errorCode;
    }

    public ServerException(int errorCode, String errorMessage, Throwable cause)
    {
        super(errorMessage, cause);
        this.errorCode = errorCode;
    }

    public ServerException(Throwable cause)
    {
        super(cause);
        this.errorCode = -1;
    }

    public int getErrorCode()
    {
        return this.errorCode;
    }
}
