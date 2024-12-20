package org.slackerdb.connector.mysql.exception;

import org.slackerdb.common.exceptions.ServerException;

public class ConnectorMysqlBinlogOffsetError extends ServerException {
    public ConnectorMysqlBinlogOffsetError(String errorMessage) {
        super(errorMessage);
    }
}
