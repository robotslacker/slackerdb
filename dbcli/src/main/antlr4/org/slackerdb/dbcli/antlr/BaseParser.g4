parser grammar BaseParser;

// 这里**不要**写 options 块：BaseParser 是被 SQLParser 导入的（import），
// ANTLR 会忽略被导入语法里的 options 并报 warning(109) "options ignored in imported grammar"。
// tokenVocab / caseInsensitive 由真正被生成的 SQLParser.g4（tokenVocab = SQLLexer）负责。

prog: baseCommand EOF;

baseCommand:
      assert
      | host
      | start
      | whenever
      | set
      | exit
      | quit
      | sleep
      | spool
      | load
      | echo
      | ssh
      | job
      | compare
      | help
      ;

// Exit
exit    : EXIT INT? SEMICOLON? CRLF?;

// quit
quit    : QUIT INT? SEMICOLON? CRLF?;

// sleep
sleep   : SLEEP SLEEP_EXPRESSION SLEEP_SEMICOLON? CRLF?;

start   : START (START_EXPRESSION)+ START_SEMICOLON? CRLF?;

// ASSERT 断言：两种外观
//   一：_ASSERT {% 取值器 比较符 字面量 %} [, 断言名]
//   二：_ASSERT 取值器 比较符 字面量 [, 断言名]
// 这里只做"取出文本"，具体语义由 dbcli 的固定形态求值器负责
assert  : ASSERT (ASSERT_BRACED | ASSERT_BODY)
          (ASSERT_COMMA ASSERT_BODY)? (ASSERT_SEMICOLON)? CRLF?;

// 执行主机操作系统命令
host    : HOST HOST_EXPRESSION (HOST_SEMICOLON)? CRLF?;

// 错误处理操作
whenever:   WHENEVER WHENEVER_ERROR (WHENEVER_CONTINUE|(WHENEVER_EXIT WHENEVER_EXITCODE)) WHENEVER_SEMICOLON? CRLF?;

// 输出重定向：`_SPOOL <file>` 开始，`_SPOOL OFF` 停止。
//
// 参数在语法层仍是"可选"的（`(SPOOL_OFF | SPOOL_EXPRESSION)?`），但**引擎不接受空参数**：
// 光写 `_SPOOL` 会得到 `Spool needs a file name (use _SPOOL OFF to stop spooling).`。
// 为什么不把它改成必填：`_SPOOL` 后面紧跟换行时，改成必填会让下一行被当成文件名吞掉
// （SpoolMode 里的 SPOOL_EXPRESSION 能匹配标识符），那比"多一条可读错误"糟得多。
spool   : SPOOL
        (SPOOL_OFF | SPOOL_EXPRESSION)?
        (SPOOL_SEMICOLON)? SPOOL_CRLF?;

// 动态加载：目前只有 `_LOAD JDBCDRIVER <jar>`。
//
// 为什么要 "目标 + 取值" 两段：`_LOAD` 是一条**有目标**的命令，
//   - 目标不认识（`_LOAD MAP x`）要报"不支持的目标"而不是"未知命令"（命令本身是认识的）；
//   - 缺取值（`_LOAD`、`_LOAD JDBCDRIVER`）要报"缺 jar 文件"。
// 这两条都由引擎（LoadCommand）给出可读文案，所以语法层允许**空取值**（同 _SPOOL 的理由：
// 改成必填会在"下一行被吞掉"时表现更差）。
//
// 取值写成 (LOAD_EXPRESSION)+ 而不是一个：未加引号的文件名里有空格时，词法会在空格处拆开，
// 访问器再用单个空格拼回（要保留连续空格就整段加引号）。
load    : LOAD (loadJdbcDriver | loadTarget)? (LOAD_SEMICOLON)? LOAD_CRLF?;

loadJdbcDriver : LOAD_JDBCDRIVER (LOAD_EXPRESSION)*;

loadTarget : LOAD_EXPRESSION+;

// 回显指定的文件
echo
        :   ECHO
            EchoBlock
            (CRLF | EOF)?
        ;

// SET 语句
set     : SET (SET_EXPRESSION)* (SET_SEMICOLON)? CRLF?;

// SSH 远程连接
ssh     :
           SSH
           (
            // 带 PORT 的形态放在前面：ANTLR 的备选按书写顺序决胜，而 "PASSWORD <值> PORT <n>"
            // 与 "PASSWORD <值>" 共享前缀，先写长的那个才能吃下 PORT 子句（见 §3.8）
            (SSH_CONNECT SSH_EXPRESSION SSH_WITH SSH_USER SSH_EXPRESSION
             SSH_PASSWORD SSH_EXPRESSION SSH_PORT SSH_EXPRESSION) |
            (SSH_CONNECT SSH_EXPRESSION SSH_WITH SSH_USER SSH_EXPRESSION
             SSH_KEYFILE SSH_EXPRESSION SSH_PORT SSH_EXPRESSION) |
            (SSH_CONNECT SSH_EXPRESSION SSH_WITH SSH_USER SSH_EXPRESSION
             (SSH_PASSWORD SSH_EXPRESSION)?) |
            (SSH_CONNECT SSH_EXPRESSION SSH_WITH SSH_USER SSH_EXPRESSION SSH_KEYFILE SSH_EXPRESSION) |
            (SSH_SET SSH_EXPRESSION SSH_EQUAL SSH_EXPRESSION) |
            (SSH_EXECUTE (SSH_EXPRESSION)+) |
            (SSH_DISCONNECT) |
            (SSH_SAVE SSH_EXPRESSION) |
            (SSH_RESTORE SSH_EXPRESSION) |
              (
              SFTP (
                     // 数值参数（chmod/mkdir 的权限位、chown 的 uid/gid、truncate 的大小）也写成
                     // SSH_EXPRESSION：词法上 SSH_EXPRESSION 排在 SSH_INT 之前，纯数字（"750"）就是
                     // 一个 SSH_EXPRESSION，写 INT 会让解析器走错误恢复（凭空补一个 INT 且**不挂进语法树**），
                     // 访问器再取 ctx.INT(0) 就会 NPE。是不是合法整数由访问器判定，非法值给可读错误。
                     (SFTP_CHMOD SSH_EXPRESSION SSH_EXPRESSION) |
                     (SFTP_GETCWD) |
                     (SFTP_CHDIR SSH_EXPRESSION) |
                     (SFTP_CHOWN SSH_EXPRESSION SSH_EXPRESSION SSH_EXPRESSION) |
                     (SFTP_MKDIR SSH_EXPRESSION SSH_EXPRESSION) |
                     (SFTP_GET SSH_EXPRESSION SSH_EXPRESSION) |
                     (SFTP_PUT SSH_EXPRESSION SSH_EXPRESSION) |
                     (SFTP_REMOVE SSH_EXPRESSION) |
                     (SFTP_RENAME SSH_EXPRESSION SSH_EXPRESSION) |
                     (SFTP_LISTDIR SSH_EXPRESSION) |
                     (SFTP_TRUNCATE SSH_EXPRESSION SSH_EXPRESSION)
                   )
              )
           )
           (SSH_SEMICOLON)? CRLF?;

// JOB 并发作业控制
//
// JOB_WAIT 允许**多个** k=v：`timeout=秒` 与 `onfail=error|ignore` 是两个独立参数
// （原先只允许一个，于是"带超时又带 onfail"写不出来，见 §4 _JOB WAIT 的说明）。
job      :
           JOB
           (
             (JOB_MANGER (JOB_ON | JOB_OFF)) |
             (JOB_WAIT JOB_EXPRESSION (JOB_EXPRESSION JOB_EQUAL JOB_EXPRESSION)*) |
             (JOB_SHOW JOB_EXPRESSION) |
             (JOB_ABORT JOB_EXPRESSION) |
             (JOB_SHUTDOWN JOB_EXPRESSION) |
             (JOB_TIMER JOB_EXPRESSION) |
             (JOB_START JOB_EXPRESSION) |
             (JOB_SET JOB_EXPRESSION (JOB_EXPRESSION JOB_EQUAL JOB_EXPRESSION)+) |
             (JOB_CREATE JOB_EXPRESSION (JOB_EXPRESSION JOB_EQUAL JOB_EXPRESSION)*)
           )
           (JOB_SEMICOLON)? CRLF?;

// 文本比对
compare   :
           COMPARE
           (
              (COMPARE_EXPRESSION COMPARE_EXPRESSION
                (COMPARE_MASK|COMPARE_NOMASK|COMPARE_CASE|COMPARE_NOCASE|COMPARE_IGBLANK|COMPARE_NOIGBLANK|COMPARE_TRIM|COMPARE_NOTRIM)*)
              | ((COMPARE_SKIPLINE|COMPARE_NOSKIPLINE) COMPARE_EXPRESSION)
              | (COMPARE_MASKLINE COMPARE_EXPRESSION COMPARE_EQUAL COMPARE_EXPRESSION)
              | (COMPARE_NOMASKLINE COMPARE_EXPRESSION)
              | COMPARE_RESET
              | (COMPARE_SET (COMPARE_MASK|COMPARE_NOMASK|COMPARE_CASE|COMPARE_NOCASE|COMPARE_IGBLANK|COMPARE_NOIGBLANK|COMPARE_TRIM|COMPARE_NOTRIM)*
                )
              | (COMPARE_SET COMPARE_OUTPUT (COMPARE_CONSOLE | COMPARE_DIFFFILE | COMPARE_HTMLFILE)+)
              | (COMPARE_SET COMPARE_ALGORITHM (COMPARE_LCS | COMPARE_MYERS | COMPARE_DIFFLIB | COMPARE_AUTO))
              | (COMPARE_SET (COMPARE_WORK|COMPARE_REFERENCE) COMPARE_ENCODING COMPARE_EXPRESSION)
           )
           (COMPARE_SEMICOLON)? CRLF?;

// 帮助信息
help       :
           HELP (HELP_COMMAND)? (HELP_SEMICOLON)? CRLF?;
