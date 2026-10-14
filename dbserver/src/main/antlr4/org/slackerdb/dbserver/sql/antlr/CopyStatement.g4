grammar CopyStatement;

options {
    caseInsensitive = true;
}

// 主入口
copyStatment
    :
        COPY
        (tableName columns? | query)   // 表名或子查询
        (TO | FROM)?          // 方向（可选）
        filePath              // 文件路径
        (WITH? options)?      // 格式选项
        (SColon)?
        (NULL_CHAR)?
    ;

// 表名（不含括号的标识符）
tableName: IDENTIFIER | STRING_LITERAL;

// 子查询：捕获括号内的任意内容（包括嵌套括号）
query: LPAREN .*? RPAREN;       // 非贪婪匹配括号内所有文本

column:  literal;

columns: LPAREN column (COMMA column)* RPAREN;

// 文件路径和格式选项（保持原有定义）
filePath: STRING_LITERAL | STDIN | STDOUT;
option:
    key=IDENTIFIER EQUAL? value=literal  // 带值的键值对（= 可省略，如 FORMAT csv / FORMAT = 'csv'）
    | key=IDENTIFIER                   // 裸开关（如 HEADER，等价于 HEADER true）
    ;
options: LPAREN option (COMMA option)* RPAREN;
literal: STRING_LITERAL | NUMBER | BOOLEAN | IDENTIFIER    ;

// 公共词法规则（保持原有定义）
COPY: 'COPY';
TO: 'TO';
FROM: 'FROM';
WITH: 'WITH';
SColon   : ';';
// 标点符号：这是 combined grammar，而 parser 规则里直接写 '(' / ',' / '=' / ')'
// 会触发 error(126)（不能在 parser 里为字面量创建隐式 token），必须显式声明为 token。
LPAREN: '(';
RPAREN: ')';
COMMA: ',';
EQUAL: '=';
// STDIN/STDOUT/BOOLEAN 用**片段 + 字符类**显式声明大小写不敏感。
//
// 为什么不能只写 'STDIN'：实测 `caseInsensitive = true` 在 ANTLR 4.13.2 里只对
// **隐式字面量**（COPY/TO/FROM/WITH）和字符**区间**生效，对显式词法规则里的整串字面量
// 不生效。后果是 `copy t from stdin` 被判定为"不是 COPY FROM STDIN"，返回 0A000，
// 而错误信息偏偏是 "Only support COPY .. FROM STDIN" —— 自相矛盾且无从排查。
// 字符类与该选项对区间的展开方式是同一套语义，最直白也最可预测。
// （`format csv` 这类选项名/值不受影响：它们走 IDENTIFIER，取值处统一 toUpperCase。）
STDIN: S_ T_ D_ I_ N_;
STDOUT: S_ T_ D_ O_ U_ T_;
BOOLEAN: T_ R_ U_ E_ | F_ A_ L_ S_ E_;
// 注意：IDENTIFIER 保持原来的 [a-z_] 区间。这是**纯词法语法**（lexer grammar），
// 在 parser 规则里引用 IDENTIFIER 会触发 error(126)（不能为字面量创建隐式 token），
// 而 `caseInsensitive = true` 会把区间展开成大小写两种情形，所以大写标识符本来就能识别
// （实测 `COPY CX FROM STDIN` 通过）。不要把区间改成 [a-zA-Z_]。
IDENTIFIER  : ([a-z_] [a-z_0-9.]* );
STRING_LITERAL: '\'' ( ~'\'' | '\'\'')* '\'' | '"' ( ~'"' | '""' )* '"';
NUMBER: [0-9]+ ( '.' [0-9]+ )?;

fragment A_: [aA];
fragment D_: [dD];
fragment E_: [eE];
fragment F_: [fF];
fragment I_: [iI];
fragment L_: [lL];
fragment N_: [nN];
fragment O_: [oO];
fragment R_: [rR];
fragment S_: [sS];
fragment T_: [tT];
fragment U_: [uU];

// 忽略空格和注释
NULL_CHAR: '\u0000';
WS: [ \t\r\n]+ -> skip;
Comment: ( '--' ~[\r\n]* | '/*' .*? '*/' ) -> channel(HIDDEN);

// 兜底 token：吞掉任何上面规则都不认识的可打印字符。
//
// 为什么需要：`COPY ( <任意查询> ) TO STDOUT` 里的查询是**不透明文本**，
// 可能包含 `*`、各种运算符、引号等（实测 `select count(*) ...` 会报
// "token recognition error at: '*'" 直接解析失败）。子查询原文由
// CopyVisitor.queryTextFromSource() 按字符扫描配对括号取出，**不依赖词法分析**，
// 所以这里只要"别因为不认识的字符整条语句解析失败"就够了。
// 必须放在最后：只有其他规则都不匹配时才会用到它（ANTLR 最长匹配优先）。
UNKNOWN_CHAR: .;
