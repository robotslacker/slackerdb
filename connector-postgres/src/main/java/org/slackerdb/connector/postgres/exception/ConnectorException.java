package org.slackerdb.connector.postgres.exception;

public class ConnectorException extends RuntimeException
{
    private String errorCode;
    private String errorMessage;

    public ConnectorException(Throwable cause)
    {
        super(cause);
    }

    public ConnectorException(String errorMessage)
    {
        super(errorMessage);
    }

    public ConnectorException(String errorCode, String errorMessage)
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
        return getMessage();
    }

    public ConnectorException(String errorMessage, Throwable cause)
    {
        super(errorMessage, cause);
    }
}
