parser grammar SQLParser;
import BaseParser;

options { 
    tokenVocab = SQLLexer;
    caseInsensitive = false;
}

prog: command EOF;


command:
      baseCommand
      | disconnect
      | session
      | connect
      | sql
      | EOF
      ;

// connect
// 连接语法只描述**本客户端要自己解释**的两种写法：
//   简写：[<user>[/<password>]@]<host>[:<port>][/<service>][?k=v&...]
//   全写：[<user>[/<password>]@]jdbc:slackerdb://<host>:<port>[/<database>][?k=v&...]
//
// 其它驱动的 URL（jdbc:sqlite:…、jdbc:mysql://… 等）**不在这里**：它们是驱动自己的语法，
// URL 里可以有任意字符，由分析器入口按原文识别并原样透传（见 script/JdbcUrlConnect）。
//
// 备选顺序就是语义顺序，两处顺序都是刻意的：
//   1) 带 scheme 的写法排在前面，这样 `jdbc:slackerdb://…` 一定落进它，不会先被
//      "以主机名开头的目标"那条备选拆成 host=jdbc；
//   2) "目标"（host[:port][/service]）排在"只有用户信息"之前：不带 @ 的 `a/b` 因此是
//      host=a、service=b（现行口径），而不是 user=a、password=b。要表达用户名必须写 `a@b`。
//   3) 空备选留给"连参数都没写完"的输入（`_connect`）：它解析得出来，于是引擎能报同一条
//      无效连接串错误，而不是一句 ANTLR 语法错误。
connect
     : connectjdbc (CONNECT_SEMICOLON)?
     ;

connectjdbc
        : CONNECT
          (
            ((connectUserInfo CONNECT_AT)? connectDriver CONNECT_COLON connectDriverSchema CONNECT_COLON
             CONNECT_DASH
             (connectHost (CONNECT_COLON connectPort)?)? ((CONNECT_SLASH | CONNECT_COLON) (connectService)?)? )
            |
            (connectUserInfo CONNECT_AT (connectHost (CONNECT_COLON connectPort)?)?
             ((CONNECT_SLASH | CONNECT_COLON) (connectService)?)?)
            |
            ((connectHost (CONNECT_COLON connectPort)?) ((CONNECT_SLASH | CONNECT_COLON) (connectService)?)?)
            |
          )
          (CONNECT_QUESTION connectParameters)?
        ;

connectUserInfo
        :  connectUser (CONNECT_SLASH connectPassword)?
        ;

connectDriver
       :  JDBC
       ;

connectDriverSchema
       : CONNECT_STRING
       ;

connectHost:
        (IPV4 | IPV6 | CONNECT_STRING)
        ;

connectUser
        : CONNECT_STRING
        ;

connectPassword
        : CONNECT_STRING
        ;

connectPort
        : CONNECT_STRING
        ;

connectService
        : CONNECT_STRING (CONNECT_COLON CONNECT_STRING)?
        ;

connectParameterName
        : CONNECT_STRING
        ;

connectParameterValue
        : CONNECT_STRING
        ;

connectParameter
        : connectParameterName CONNECT_EQUAL connectParameterValue
        ;

connectParameters
        : connectParameter ((CONNECT_PARA_AND connectParameter)+)?
        ;
        
// disconnect
disconnect
        : DISCONNECT (expression|INT)* CRLF?
        ;

session
        : SESSION
        (
               SESSION_SAVE|
               SESSION_RELEASE|
               SESSION_RESTORE|
               SESSION_SAVEURL|
               SESSION_SHOW
        ) SESSION_NAME? SESSION_END? CRLF?
        ;


expression
        : (String
        | DOT
        | COLON
        | SLASH
        | BRACKET_OPEN
        | BRACKET_CLOSE
        | ESCAPE
        | SQUARE_OPEN
        | SQUARE_CLOSE
        | DOUBLE_QUOTE
        | SINGLE_QUOTE )+
        ;

//
sql 
        : sqlCreate
        | sqlReplace
        | sqlInsert
        | sqlUpdate
        | sqlDelete
        | sqlSelect
        | sqlDeclare
        | sqlDrop
        | sqlCreateProcedure
        | sqlCommit
        | sqlRollback
        | sqlGeneric
        | sqlBeginTxn
        | CRLF
        ;

// 通用 SQL 语句：WITH / ALTER / TRUNCATE / VACUUM / ANALYZE / EXPLAIN / SET /
// SHOW / GRANT / REVOKE / CALL / COMMENT / REFRESH / COPY / DO。
//
// 客户端不解释这些语句的语义，只负责"认出它是一条 SQL、等它写完、原样转发"：
// IR 与其它 SQL 语句同构 {name: 'SQL', statement: <关键字到终结符之前的原样文本>}，
// 词法已把正文整段收在 SQLSTATEMENT_CHANNEL 上（含内部换行与注释）。
sqlGeneric
        : SQL_GENERIC SQL_END CRLF?
        ;

// 事务形式的 `BEGIN;`：终结符是那个 ';'（词法把 'BEGIN' 与 ';' 一起收成一个 token）。
// 块形式 `begin … end;` 换行 '/' 收尾，走 sqlDeclare，两者互不影响。
sqlBeginTxn
        : SQL_BEGIN_TXN CRLF?
        ;

// COMMIT / ROLLBACK：标准 SQL 命令行语句，必须支持。
// IR 形状与其它 SQL 语句同构：
//   {name: 'COMMIT'|'ROLLBACK', statement: <关键字到终结符之前的原样文本>}
// 终结符既接受 ';'，也接受行首 '/'（词法把 '\n/' 归为 SQL_END 类型）。
sqlCommit
        : SQL_COMMIT SQL_END CRLF?
        ;

sqlRollback
        : SQL_ROLLBACK SQL_END CRLF?
        ;

sqlCreate
        : SQL_CREATE SQL_END CRLF?
        ;

sqlReplace
        : SQL_REPLACE SQL_END CRLF?
        ;

sqlInsert
        : SQL_INSERT SQL_END CRLF?
        ;

sqlUpdate
        : SQL_UPDATE SQL_END CRLF?
        ;

sqlDelete
        : SQL_DELETE SQL_END CRLF?
        ;

sqlSelect
        : SQL_SELECT SQL_END CRLF?
        ;

sqlDrop
        : SQL_DROP SQL_END CRLF?
        ;

sqlDeclare
        : (SQL_DECLARE | SQL_BEGIN)
          SQL_SLASH CRLF?
        ;

sqlCreateProcedure
        : SQL_CREATE_PROCEDURE 
          SQL_SLASH CRLF?
        ;
