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
query: '(' .*? ')';       // 非贪婪匹配括号内所有文本

column:  literal;

columns: '(' column (',' column)* ')';

// 文件路径和格式选项（保持原有定义）
filePath: STRING_LITERAL | STDIN | STDOUT;
option:
    key=IDENTIFIER '=' value=literal   // 带等号的键值对（如 FORMAT = 'csv'）
    | key=IDENTIFIER value=literal     // 省略等号的键值对（如 FORMAT csv）
    ;
options: '(' option (',' option)* ')';
literal: STRING_LITERAL | NUMBER | BOOLEAN | IDENTIFIER    ;

// 公共词法规则（保持原有定义）
COPY: 'COPY';
TO: 'TO';
FROM: 'FROM';
WITH: 'WITH';
SColon   : ';';
STDIN: 'STDIN';
STDOUT: 'STDOUT';
BOOLEAN: 'TRUE' | 'FALSE';
IDENTIFIER  : ([a-z_] [a-z_0-9.]* );
STRING_LITERAL: '\'' ( ~'\'' | '\'\'')* '\'' | '"' ( ~'"' | '""' )* '"';
NUMBER: [0-9]+ ( '.' [0-9]+ )?;

// 忽略空格和注释
NULL_CHAR: '\u0000';
WS: [ \t\r\n]+ -> skip;
Comment: ( '--' ~[\r\n]* | '/*' .*? '*/' ) -> channel(HIDDEN);
