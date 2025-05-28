package org.slackerdb.cdb.message.response;

public class ProxyResponse {
    public static String getMessageClass(byte messageType) {
        return switch ((char) messageType) {
            case '!' -> "AdminClientResp";
            case 'R' -> "AuthenticationOk";
            case 'K' -> "BackendKeyData";
            case '2' -> "BindComplete";
            case '3' -> "CloseComplete";
            case 'C' -> "CommandComplete";
            case 'c' -> "CopyDone";
            case 'G' -> "CopyInResponse";
            case 'D' -> "DataRow";
            case 'E' -> "ErrorResponse";
            case 'N' -> "NoticeMessage";
            case 'S' -> "ParameterStatus";
            case '1' -> "ParseComplete";
            case 's' -> "PortalSuspended";
            case 'Z' -> "ReadyForQuery";
            case 'T' -> "RowDescription";
            default -> "Unknown";
        };
    }
}
