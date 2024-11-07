package org.slackerdb.dbproxy.message.response;

public class ProxyResponse {
    public static String getMessageClass(byte messageType) {
        switch ((char) messageType) {
            case '!':
                return "AdminClientResp";
            case 'R':
                return "AuthenticationOk";
            case 'K':
                return "BackendKeyData";
            case '2':
                return "BindComplete";
            case '3':
                return "CloseComplete";
            case 'C':
                return "CommandComplete";
            case 'c':
                return "CopyDone";
            case 'G':
                return "CopyInResponse";
            case 'D':
                return "DataRow";
            case 'E':
                return "ErrorResponse";
            case 'N':
                return "NoticeMessage";
            case 'S':
                return "ParameterStatus";
            case '1':
                return "ParseComplete";
            case 's':
                return "PortalSuspended";
            case 'Z':
                return "ReadyForQuery";
            case 'T':
                return "RowDescription";
            default:
                return "Unknown";
        }
    }
}
