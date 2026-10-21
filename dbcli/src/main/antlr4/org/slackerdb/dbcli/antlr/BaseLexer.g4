// 基础词法
lexer grammar BaseLexer;

options {
    caseInsensitive = true;
}

// 分割符号
CRLF                : '\n';
COMMA               : ',';
SEMICOLON           : ';';
COLON               : ':';
AT                  : '@';
DOT                 : '.';
SLASH               : '/';
BRACKET_OPEN        : '(';
BRACKET_CLOSE       : ')';
SQUARE_OPEN         : '[';
SQUARE_CLOSE        : ']';
DOUBLE_QUOTE        : '"';
SINGLE_QUOTE        : '\'';
ESCAPE              : '\\';
SPACE               : [ \t]+ ->channel(HIDDEN);

EXIT                : '_EXIT';
QUIT                : '_QUIT';
SLEEP               : '_SLEEP' -> pushMode(SleepMode);

// 回显随后的脚本
ECHO                : '_ECHO' .*? (CRLF | EOF) ->pushMode(EchoMode);

// 表达式判断，用来验证执行结果
ASSERT              :  '_ASSERT' -> pushMode(AssertMode);

// 执行脚本
START               : '_START' -> pushMode(StartMode);

// 执行主机操作系统命令
HOST                : '_HOST' -> pushMode(HostMode);

// 指定帮助命令
HELP                : '_HELP' -> pushMode(HelpMode);

// 设置系统参数
SET                 : '_SET' -> pushMode(SetMode);

// 输出到指定文件
SPOOL               : '_SPOOL' -> pushMode(SpoolMode);

// 动态加载资源（目前只有 JDBC 驱动 jar）
LOAD                : '_LOAD' -> pushMode(LoadMode);

// 一旦错误，将会执行的操作
WHENEVER            : '_WHENEVER' -> pushMode(WheneverMode);

// 执行远程操作系统命令
SSH                 : '_SSH' ->pushMode(SshMode);

// 执行并行任务的控制
JOB                 : '_JOB' ->pushMode(JobMode);

// 比对文件的一致性
COMPARE             : '_COMPARE' ->pushMode(CompareMode);

INT                 : DIGIT+ ;
DECIMAL             : DIGIT+ '.' DIGIT+ ;
String              : (OBS_TEXT | UNRESERVED | SUBDELIMS | PCTENCODED | DoubleQuoteString | SingleQuoteString)+;
fragment UNRESERVED : ALPHA | DIGIT | '-' | '.' | '_' | '~';
fragment SUBDELIMS  : '!' | '$' | '&' | '(' | ')' | '*' | '+' | '=';
fragment PCTENCODED : '%' HEX HEX;
fragment DIGIT      : [0-9];
fragment ALPHA      : [A-Z];
fragment HEX        : [0-9A-F];
fragment OBS_TEXT   : '\u00ff' ..'\uffff';
fragment DoubleQuoteString
                    : '"' (~'"' | '\\' ('\n' | .))* '"';
fragment SingleQuoteString
                    : '\'' (~'\'' | '\\' ('\r'? '\n' | .))* '\'';

/**
 * 回显模式
 */
mode EchoMode;
EchoBlock           :.*? 'ECHO' (' ' | '\t')+ 'OFF' -> popMode;

mode AssertMode;
// 外观一：{% ... %}，便于书写与阅读常量断言。
// ★ 必须把前导空白一起吃掉：[ \t]* 让本规则比 ASSERT_BODY 更长从而以"最长匹配"胜出；
//   否则 `_ASSERT {% ... %}` 里那个空格会被 ASSERT_BODY 单独匹配成一个 token，
//   整条断言就落进"解析层认不下来"的分支。
ASSERT_BRACED       : [ \t]* '{%' .*? '%}';
// 外观二：裸固定形态，例如 _ASSERT RESULT.ROWS[0][0] = 10
// "取值器 比较符 字面量"整体作为一个 token 取出（含内部空格），到 , / ; / 行尾结束。
// 不含 { 与 }，所以与上面的外观不会互相干扰。
ASSERT_BODY         : (OBS_TEXT | UNRESERVED | PCTENCODED | DoubleQuoteString | SingleQuoteString
                      | ':' | '/' | '\\' | ' ' | '\t' | '[' | ']' | '=' | '<' | '>' | '!' | '*' | '?' | '|')+;
ASSERT_COMMA        : ',';
ASSERT_SEMICOLON    : ';';
ASSERT_CRLF         : CRLF -> popMode;

mode StartMode;
START_SPACE        : [ \t]+ -> channel (HIDDEN);
START_EXPRESSION   :
    (OBS_TEXT | UNRESERVED | SUBDELIMS | PCTENCODED | DoubleQuoteString | SingleQuoteString | ':' | '/' | '\\' )+;
START_CRLF         : CRLF -> popMode;

mode HostMode;
HOST_SPACE         : [ \t]+ -> channel (HIDDEN);
HOST_EXPRESSION    :
    (OBS_TEXT | UNRESERVED | SUBDELIMS | PCTENCODED | DoubleQuoteString | SingleQuoteString | ':' | '/' | '\\' |' ')+;
HOST_CRLF          : CRLF -> popMode;

mode WheneverMode;
WHENEVER_SPACE      : [ \t]+ -> channel (HIDDEN);
WHENEVER_EXITCODE   : INT;
WHENEVER_ERROR      : 'ERROR';
WHENEVER_SEMICOLON  : ';';
WHENEVER_CONTINUE   : 'CONTINUE';
WHENEVER_EXIT       : 'EXIT';
WHENEVER_CRLF       : CRLF -> popMode;

mode SpoolMode;
SPOOL_SPACE           : [ \t]+ -> channel (HIDDEN);
// SPOOL_OFF 必须排在 SPOOL_EXPRESSION **之前**：两者都能匹配 "OFF" 且长度相同，
// ANTLR 在等长时"先声明者胜"。排在后面时它永远是死代码，`_spool off` 只能靠引擎里
// 把文本 "off" 当保留字来兜（历史做法）；排到前面之后语法层就直接认得这个动作。
SPOOL_OFF             : 'OFF';
SPOOL_EXPRESSION      : (OBS_TEXT | UNRESERVED | SUBDELIMS | PCTENCODED | DoubleQuoteString | SingleQuoteString | ':' | '/' | '\\' | '@' | '{' | '}' | '%')+;
SPOOL_SEMICOLON       : ';';
SPOOL_CRLF            : CRLF -> popMode;

mode SetMode;
SET_SPACE           : [ \t]+ -> channel (HIDDEN);
SET_EXPRESSION      : (OBS_TEXT | UNRESERVED | SUBDELIMS | PCTENCODED | DoubleQuoteString | SingleQuoteString | ':' | '/' | '\\' | '@' | '{' | '}' | '%')+;
SET_SEMICOLON       : ';';
SET_CRLF            : CRLF -> popMode;

mode LoadMode;
LOAD_SPACE          : [ \t]+ -> channel (HIDDEN);
// LOAD_JDBCDRIVER 必须排在 LOAD_EXPRESSION **之前**：两者都能匹配 "JDBCDRIVER" 且长度相同，
// ANTLR 等长时"先声明者胜"。排在后面时它永远是死代码，`_load jdbcdriver` 会被当成一个
// "不认识的目标"，语法层也就形同虚设。
LOAD_JDBCDRIVER     : 'JDBCDRIVER';
// 取值：与 SPOOL 同一套字符集（不含空白）。未加引号的文件名里有空格时，词法会拆成多个
// LOAD_EXPRESSION，解析规则再按单个空格拼回；要保留连续空格就给整段加引号。
LOAD_EXPRESSION     : (OBS_TEXT | UNRESERVED | SUBDELIMS | PCTENCODED | DoubleQuoteString | SingleQuoteString | ':' | '/' | '\\' | '@' | '{' | '}' | '%')+;
LOAD_SEMICOLON      : ';';
LOAD_CRLF           : CRLF -> popMode;

mode SshMode;
SSH_CONNECT         : 'CONNECT';
SSH_WITH            : 'WITH';
SSH_USER            : 'USER';
SSH_SET             : 'SET';
SSH_KEYFILE         : 'KEYFILE';
SSH_PASSWORD        : 'PASSWORD';
SSH_PORT            : 'PORT';
SSH_EXECUTE         : 'EXECUTE';
SSH_DISCONNECT      : 'DISCONNECT';
SSH_SAVE            : 'SAVE';
SSH_RESTORE         : 'RESTORE';
SFTP                : 'SFTP';
SFTP_CHMOD          : 'CHMOD';
SFTP_GETCWD         : 'GETCWD';
SFTP_CHDIR          : 'CHDIR';
SFTP_CHOWN          : 'CHOWN';
SFTP_MKDIR          : 'MKDIR';
SFTP_GET            : 'GET';
SFTP_PUT            : 'PUT';
SFTP_REMOVE         : 'REMOVE';
SFTP_RENAME         : 'RENAME';
SFTP_LISTDIR        : 'LISTDIR';
SFTP_TRUNCATE       : 'TRUNCATE';
SSH_EQUAL           : '=';
SSH_SPACE           : [ \t]+ -> channel (HIDDEN);
SSH_SEMICOLON       : ';';
SSH_CRLF            : CRLF -> popMode;
// SSH_EXPRESSION 必须排在 SSH_INT 之前：两者都能匹配纯数字（SSH_EXPRESSION 经 UNRESERVED 的 DIGIT
// 覆盖数字），长度相同时**先写的规则赢**。让表达式赢有三个必须的理由：
//   1) `password 123456` 这类纯数字口令必须仍是一整个 SSH_EXPRESSION，否则 connect 备选会被拆散；
//   2) PORT 后面的端口号也是一个"值"，按同一条规则取（访问器按 10 进制解析）；
//   3) SFTP 的数值参数（chmod/mkdir 的权限位、chown 的 uid/gid、truncate 的大小）同样按值取
//      （见 BaseParser 的 ssh 规则）——这些位置若写 INT，纯数字会被错误恢复吞掉且不挂进语法树，
//      访问器就会取到 null（NPE）。
// SSH_INT 因此不再被语法引用（SshMode 下 SSH_EXPRESSION 总能吃掉纯数字），保留它只为不改动
// 既有 token 序列；"是不是合法整数"由访问器判定（SqlIrVisitor.sftpNumber）。
SSH_EXPRESSION      :
    (OBS_TEXT | UNRESERVED | SUBDELIMS | PCTENCODED | DoubleQuoteString | SingleQuoteString | ':' | '/' | '\\' | '@' | '{' | '}' | '|'| ',')+;
SSH_INT             : [0-9]+;

mode JobMode;
JOB_SPACE           : [ \t]+ -> channel (HIDDEN);
JOB_SEMICOLON       : ';';
JOB_EQUAL           : '=';
JOB_CRLF            : CRLF -> popMode;
JOB_CREATE          : 'CREATE';
JOB_SHOW            : 'SHOW';
JOB_SET             : 'SET';
JOB_START           : 'START';
JOB_WAIT            : 'WAIT';
JOB_SHUTDOWN        : 'SHUTDOWN';
JOB_ABORT           : 'ABORT';
JOB_TIMER           : 'TIMER';
JOB_MANGER          : 'JOBMANAGER';
JOB_ON              : 'ON';
JOB_OFF             : 'OFF';
JOB_EXPRESSION      :
    (OBS_TEXT | UNRESERVED | PCTENCODED | DoubleQuoteString | SingleQuoteString | ':' | '/' | '\\' | '{' | '}')+;

mode CompareMode;
COMPARE_SPACE       : [ \t]+ -> channel (HIDDEN);
COMPARE_SEMICOLON   : ';';
COMPARE_EQUAL       : '=>';
COMPARE_CRLF        : CRLF -> popMode;
COMPARE_SET         : 'SET';
COMPARE_UNSET       : 'UNSET';
COMPARE_MASK        : 'MASK';
COMPARE_NOMASK      : 'NOMASK';
COMPARE_MASKLINE    : 'MASKLINE';
COMPARE_NOMASKLINE  : 'NOMASKLINE';
COMPARE_CASE        : 'CASE';
COMPARE_NOCASE      : 'NOCASE';
COMPARE_IGBLANK     : 'IGBLANK';
COMPARE_NOIGBLANK   : 'NOIGBLANK';
COMPARE_TRIM        : 'TRIM';
COMPARE_NOTRIM      : 'NOTRIM';
COMPARE_OUTPUT      : 'OUTPUT';
COMPARE_CONSOLE     : 'CONSOLE';
COMPARE_DIFFFILE    : 'DIFFFILE';
COMPARE_HTMLFILE    : 'HTMLFILE';
COMPARE_SKIPLINE    : 'SKIPLINE';
COMPARE_NOSKIPLINE  : 'NOSKIPLINE';
COMPARE_RESET       : 'RESET';
COMPARE_ALGORITHM   : 'ALGORITHM';
COMPARE_ENCODING    : 'ENCODING';
COMPARE_WORK        : 'WORK';
COMPARE_REFERENCE   : 'REFERENCE';
COMPARE_LCS         : 'LCS';
COMPARE_MYERS       : 'MYERS';
COMPARE_DIFFLIB     : 'DIFFLIB';
COMPARE_AUTO        : 'AUTO';
COMPARE_EXPRESSION  :
    (OBS_TEXT | UNRESERVED | PCTENCODED | DoubleQuoteString | SingleQuoteString | ':' | '/' | '\\' | '{' |'}')+;

mode HelpMode;
HELP_SPACE           : [ \t]+ -> channel (HIDDEN);
HELP_COMMAND         : [0-9A-Z]+;
HELP_CRLF            : CRLF -> popMode;
HELP_SEMICOLON       : ';';

mode SleepMode;
SLEEP_SPACE         : [ \t]+ -> channel (HIDDEN);
SLEEP_EXPRESSION    :
    (OBS_TEXT | UNRESERVED | PCTENCODED | DoubleQuoteString | SingleQuoteString | ':' | '/' | '\\' | '{' | '}')+;
SLEEP_CRLF          : CRLF -> popMode;
SLEEP_SEMICOLON     : ';';
