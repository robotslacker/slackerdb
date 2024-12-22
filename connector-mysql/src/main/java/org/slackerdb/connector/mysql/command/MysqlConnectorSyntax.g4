grammar MysqlConnectorSyntax;

options {
    caseInsensitive = true;
}

// 主入口
command
    : (
        createConnector |
        startConnector  |
        shutdownConnector |
        dropConnector |
        alterConnector |
        showConnector
       ) (SColon)? EOF
    ;

showConnector
    :  SHOW CONNECTOR connectorName (TASK taskName)? STATUS
    ;

alterConnector
    : ALTER CONNECTOR connectorName
      (
        (ADD TASK taskName taskOptions) |
        (REMOVE TASK taskName) |
        (START TASK taskName) |
        (SHUTDOWN TASK taskName) |
        (RESYNC TASK taskName) |
        (RESYNC FULL) |
        (RESYNC TABLE tableName)
      )
    ;

dropConnector
    : DROP CONNECTOR connectorName;

shutdownConnector
    : SHUTDOWN CONNECTOR connectorName
    ;

startConnector
    : START CONNECTOR connectorName
    ;

createConnector
    :  CREATE TEMPORARY? CONNECTOR (ifnotexists)? connectorName CONNECT TO connectorOptions
    ;

connectorName
    :   Identifier
    ;

taskName
    :   Identifier
    ;

connectorOptions
    :   String
    ;

taskOptions
    :  String
    ;

tableName
    :   String
    ;

ifnotexists
    :  'IF' 'NOT' 'EXISTS'
    ;

// 忽略空白字符
WS: [ \t\r\n\u000C]+ -> channel(HIDDEN);
CRLF                : '\n';
SColon   : ';';
Assign   : '=';
Comma    : ',';
QMark    : '?';
Colon    : ':';

// 保留字
DROP:           'DROP';
CREATE:         'CREATE';
ALTER:          'ALTER';
START:          'START';
SHUTDOWN:       'SHUTDOWN';
CONNECTOR:      'CONNECTOR';
CONNECT:        'CONNECT';
TO:             'TO';
ADD:            'ADD';
REMOVE:         'REMOVE';
TASK:           'TASK';
RESYNC:         'RESYNC';
TABLE:          'TABLE';
FULL:           'FULL';
SHOW:           'SHOW';
STATUS:         'STATUS';
TEMPORARY:      'TEMPORARY';

Bool
 : 'true'
 | 'false'
 ;

Number
 : Int ( '.' Digit* )?
 ;

Identifier
 : ([a-z_] [a-z_0-9.]* ) | '${' [a-z_] [a-z_0-9.]* '}'
 ;

String
 : ["] ( ~["\r\n\\] | '\\' ~[\r\n] )* ["]
 | ['] ( ~['\r\n\\] | '\\' ~[\r\n] )* [']
 ;

Comment
 : ( '--' ~[\r\n]* | '/*' .*? '*/' ) -> channel(HIDDEN)
 ;

fragment Int
 : [1-9] Digit*
 | '0'
 ;

fragment Digit
 : [0-9]
 ;