package org.slackerdb.connector.mysql.exception;

import org.slackerdb.common.exceptions.ServerException;

public class ConnectorMysqlBinlogNotExist extends ServerException {
    public ConnectorMysqlBinlogNotExist(String errorMessage) {
        super(errorMessage);
    }
}
