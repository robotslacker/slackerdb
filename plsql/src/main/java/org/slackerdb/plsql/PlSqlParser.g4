grammar PlSqlParser;

options {
    caseInsensitive = true;
}

// 主入口
plsql_script
    : declare_block? begin_block? (CRLF)? EOF
    ;

declare_block
    : DECLARE variable_declaration* (CRLF)?
    ;

begin_block
    : BEGIN CRLF? begin_code_block (exception begin_exception_block)? END SColon CRLF?
    ;

begin_code_block
    : (if_block| for_block | loop_block | sql_block)*
    ;

begin_exception_block
    : (if_block | for_block | loop_block | sql_block)*
    ;

sql_block
    :    let | if_block |begin_block | fetchsql | breakloop |  fetchstatement |
         cursor_open_statement | cursor_close_statement |
         exit | sql
    ;

if_block
    :
        if sql_block* elseif_block* else_block? endif CRLF?
    ;

elseif_block
    : elseif sql_block*
    ;

else_block
    : else sql_block*
    ;

exception
    : EXCEPTION ':' CRLF?
    ;

loop_block
    : LOOP CRLF? sql_block* exit_when_statement sql_block* END LOOP SColon CRLF?
    ;

for_block
    : FOR bindIdentifier IN ((expression TO expression) | list)
      LOOP CRLF? sql_block* END LOOP SColon CRLF?
    ;

variable_declaration
    : variable_name datatype (':=' variable_defaultValue)? SColon (CRLF)?
    | CURSOR variable_name IS sql (CRLF)?
    ;

variable_name
    : Identifier
    ;

variable_defaultValue
    : Identifier
    ;

let
    : LET Identifier '=' expression SColon CRLF?
    ;

sql
    : (PASS |sql_part ) SColon CRLF?
    ;

fetchsql
    :  sql_part fetch_list sql_part SColon CRLF?
    ;

sql_part
    : (sql_token)*?
    ;

sql_token
    :  bindIdentifier |
       (~(SColon | EXCEPTION | LOOP | END | FOR | FETCH | EXIT | OPEN | CLOSE | PASS | IF | ELSE |ELSEIF | BREAK))
    ;

// 打开游标
cursor_open_statement
    : OPEN Identifier SColon CRLF?
    ;

// 关闭游标
cursor_close_statement
    : CLOSE Identifier SColon CRLF?
    ;

// 游标提取
fetchstatement
    : FETCH Identifier fetch_list SColon CRLF?
    ;

// EXIT WHEN 语句
exit_when_statement
    : EXIT WHEN Identifier '%' NOTFOUND SColon CRLF?
    ;

// 解析变量列表
fetch_list
    : INTO bindIdentifier (',' bindIdentifier)*
    ;

if
    :  IF expression THEN CRLF?
    ;

elseif
    :  ELSEIF expression THEN CRLF?
    ;

else
    :  ELSE CRLF?
    ;

breakloop
    :  BREAK SColon CRLF?
    ;

endif
    :  ENDIF SColon CRLF?
    ;

// 基本类型定义
datatype
    : 'INT'
    | 'TEXT'
    | 'BIGINT'
    | 'DOUBLE'
    | 'DATE'
    | 'TIMESTAMP'
    | 'FLOAT'
    ;

exit
    :  EXIT SColon CRLF?
    ;

functionCall
 : Identifier '(' exprList? ')' #identifierFunctionCall
 ;

exprList
 : expression ( ',' expression )*
 ;

expression
 : '-' expression                                       #unaryMinusExpression
 | '!' expression                                       #notExpression
 | <assoc=right> expression '^' expression              #powerExpression
 | expression op=( '*' | '/' | '%' ) expression         #multExpression
 | expression op=( '+' | '-' ) expression               #addExpression
 | expression op=( '>=' | '<=' | '>' | '<' ) expression #compExpression
 | expression op=( '==' | '!=' ) expression             #eqExpression
 | expression '&&' expression                           #andExpression
 | expression '||' expression                           #orExpression
 | expression '?' expression ':' expression             #ternaryExpression
 | expression IN expression                             #inExpression
 | Number                                               #numberExpression
 | Bool                                                 #boolExpression
 | Null                                                 #nullExpression
 | functionCall                                         #functionCallExpression
 | list                                                 #listExpression
 | Identifier                                           #identifierExpression
 | bindIdentifier                                       #bindIdentifierExpression
 | String                                               #stringExpression
 | '(' expression ')'                                   #expressionExpression
 ;

bindIdentifier
 : ':' Identifier
 ;

list
 : '[' exprList? ']'
 ;

// 忽略空白字符
WS: [ \t\r\n\u000C]+ -> channel(HIDDEN);
CRLF                : '\n';

Null     : 'null';
Or       : '||';
And      : '&&';
Equals   : '==';
NEquals  : '!=';
GTEquals : '>=';
LTEquals : '<=';
Pow      : '^';
Excl     : '!';
GT       : '>';
LT       : '<';
Add      : '+';
Subtract : '-';
Multiply : '*';
Divide   : '/';
Modulus  : '%';
OBrace   : '{';
CBrace   : '}';
OBracket : '[';
CBracket : ']';
OParen   : '(';
CParen   : ')';
SColon   : ';';
Assign   : '=';
Comma    : ',';
QMark    : '?';
Colon    : ':';

// 保留字
DECLARE: 'DECLARE';
BEGIN: 'BEGIN';
END: 'END';
CURSOR: 'CURSOR';
IS: 'IS';
LOOP: 'LOOP';
FETCH: 'FETCH';
OPEN: 'OPEN';
LET:  'LET';
CLOSE: 'CLOSE';
BREAK: 'BREAK';
EXIT: 'EXIT';
WHEN: 'WHEN';
INTO: 'INTO';
NOTFOUND: 'NOTFOUND';
IF:  'IF';
ELSEIF: 'ELSEIF';
ENDIF:  'END' WS+ 'IF';
THEN:  'THEN';
FOR:   'FOR';
TO: 'TO';
ELSE:  'ELSE';
RETURN: 'RETURN';
EXCEPTION: 'EXCEPTION';
PASS:  'PASS';
IN: 'in';


Bool
 : 'true'
 | 'false'
 ;

Number
 : Int ( '.' Digit* )?
 ;

Identifier
 : [a-z_] [a-z_0-9.]*
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