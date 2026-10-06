grammar PlSqlBlock;

options { caseInsensitive = true; }

// ============================================================================
// PL/SQL 结构文法
//
// **只描述结构**。内嵌 SQL 由 PlSqlSourcePreparer 遮罩成
// SQL_SEGMENT 占位符后再喂给词法器，因此这里永远不需要理解 SQL 语法
// （CASE..END、$$、字符串里的分号、任意 SQL 关键字都不会干扰结构解析）。
//
// 表达式在 P4 只做**结构识别**（AST 里保留原文 + 位置），真正的类型/运算语义
// 由 P5 的表达式编译器负责；因此这里的表达式规则是完整的分层文法，
// 但 AstBuilder 只记录 expr 的文本区间。
// ============================================================================

// ---------- 词法：占位符与结构关键字 ----------
// 遮罩占位符：`_SQL_<id>` 后跟任意个下划线（用于把占位符补齐到原文本长度，
// 从而让行号/列号与原文保持一致）。必须放在 IDENTIFIER 之前。
SQL_SEGMENT : '_SQL_' [0-9]+ '_'* ;

DECLARE   : 'DECLARE' ;
BEGIN     : 'BEGIN' ;
END       : 'END' ;
IF        : 'IF' ;
THEN      : 'THEN' ;
ELSIF     : 'ELSIF' ;
ELSEIF    : 'ELSEIF' ;
ENDIF     : 'ENDIF' ;
ELSE      : 'ELSE' ;
LOOP      : 'LOOP' ;
WHILE     : 'WHILE' ;
FOR       : 'FOR' ;
IN        : 'IN' ;
REVERSE   : 'REVERSE' ;
TO        : 'TO' ;
EXIT      : 'EXIT' ;
BREAK     : 'BREAK' ;
CONTINUE  : 'CONTINUE' ;
WHEN      : 'WHEN' ;
FETCH     : 'FETCH' ;
INTO      : 'INTO' ;
OPEN      : 'OPEN' ;
CLOSE     : 'CLOSE' ;
CURSOR    : 'CURSOR' ;
IS        : 'IS' ;
RAISE     : 'RAISE' ;
RETURN    : 'RETURN' ;
COMMIT    : 'COMMIT' ;
ROLLBACK  : 'ROLLBACK' ;
EXCEPTION : 'EXCEPTION' ;
OTHERS    : 'OTHERS' ;
NULL      : 'NULL' ;
PASS      : 'PASS' ;
LET       : 'LET' ;
CONSTANT  : 'CONSTANT' ;
DEFAULT   : 'DEFAULT' ;
NOT       : 'NOT' ;
AND       : 'AND' ;
AND2      : '&&' ;
OR        : 'OR' ;
LIKE      : 'LIKE' ;
BETWEEN   : 'BETWEEN' ;
CASE      : 'CASE' ;
FOUND     : 'FOUND' ;
NOTFOUND  : 'NOTFOUND' ;
ROWCOUNT  : 'ROWCOUNT' ;
ISOPEN    : 'ISOPEN' ;
TRUE      : 'TRUE' ;
FALSE     : 'FALSE' ;

// 动态 SQL：EXECUTE IMMEDIATE <sql 文本> [INTO 变量...] [USING 值...]
EXECUTE   : 'EXECUTE' ;
IMMEDIATE : 'IMMEDIATE' ;
USING     : 'USING' ;

// ---------- 词法：运算符与标点 ----------
ASSIGN    : ':=' ;
RANGE     : '..' ;
CONCAT    : '||' ;
POWER     : '**' ;
EQ        : '=' ;
EQ2       : '==' ;
NEQ       : '!=' ;
NEQ2      : '<>' ;
LTE       : '<=' ;
GTE       : '>=' ;
LT        : '<' ;
GT        : '>' ;
PLUS      : '+' ;
MINUS     : '-' ;
STAR      : '*' ;
SLASH     : '/' ;
PERCENT   : '%' ;
EXCL      : '!' ;
LPAREN    : '(' ;
RPAREN    : ')' ;
LBRACKET  : '[' ;
RBRACKET  : ']' ;
COMMA     : ',' ;
SEMICOLON : ';' ;
DOT       : '.' ;
COLON     : ':' ;

// ---------- 词法：字面量与标识符 ----------
// 小数点后必须跟数字：否则 `1..5` 会被贪婪匹配成 NUMBER("1.") + DOT，RANGE 永远匹配不上
NUMBER            : [0-9]+ ('.' [0-9]+)? ;
QUOTED_IDENTIFIER : '"' ('""' | ~["])* '"' ;
STRING            : '\'' ('\'\'' | ~['])* '\'' ;
COLON_IDENTIFIER  : ':' [A-Z_] [A-Z_0-9]* ;
IDENTIFIER        : [A-Z_] [A-Z_0-9$]* ;

WS      : [ \t\r\n\u000C]+ -> channel(HIDDEN) ;
COMMENT : ('--' ~[\r\n]* | '/*' .*? '*/') -> channel(HIDDEN) ;

// ---------- 语法：块结构 ----------
script           : block EOF ;
block            : declare_section? body ;
nested_block     : declare_section? body ;

declare_section  : DECLARE item* ;
item             : cursor_decl | variable_decl ;

variable_decl    : name_ref type_ref (NOT NULL)? ((ASSIGN | DEFAULT) expr)? SEMICOLON ;
type_ref         : IDENTIFIER+ (LPAREN NUMBER (COMMA NUMBER)? RPAREN)? ;
cursor_decl      : CURSOR IDENTIFIER (LPAREN parameter_list? RPAREN)? IS SQL_SEGMENT+ SEMICOLON ;
parameter_list   : parameter (COMMA parameter)* ;
parameter        : name_ref type_ref ;

body             : BEGIN statement* exception_section? END SEMICOLON? ;
// 标准写法是 EXCEPTION + WHEN 处理块；历史写法是 `EXCEPTION:` 后直接跟语句（等价于 WHEN OTHERS）
exception_section: EXCEPTION COLON? (handler+ | statement*) ;
handler          : WHEN exception_name (OR exception_name)* THEN statement* ;
exception_name   : OTHERS | IDENTIFIER ;

// ---------- 语法：语句 ----------
statement
    : sql_stmt
    | select_into_stmt
    | execute_immediate_stmt
    | assignment
    | if_stmt
    | loop_stmt
    | while_stmt
    | for_stmt
    | exit_stmt
    | continue_stmt
    | fetch_stmt
    | open_stmt
    | close_stmt
    | raise_stmt
    | return_stmt
    | commit_stmt
    | rollback_stmt
    | null_stmt
    | nested_block
    ;

sql_stmt         : SQL_SEGMENT+ SEMICOLON ;
select_into_stmt : SQL_SEGMENT+ INTO target_list SQL_SEGMENT* SEMICOLON ;
// 动态 SQL：第一个 expr 是 SQL 文本（字面量或变量），USING 的值按出现顺序绑定到
// 动态 SQL 里的占位符（? / :1 / :name），INTO 为单行结果的目标变量。
execute_immediate_stmt
                 : EXECUTE IMMEDIATE expr (INTO target_list)? (USING expr_list)? SEMICOLON ;
assignment       : name_ref ASSIGN expr SEMICOLON
                 | LET name_ref EQ expr SEMICOLON ;
if_stmt          : IF expr THEN statement* elsif_branch* else_branch? (END IF | ENDIF) SEMICOLON ;
elsif_branch     : (ELSIF | ELSEIF) expr THEN statement* ;
else_branch      : ELSE statement* ;
loop_stmt        : label_decl? LOOP statement* END LOOP IDENTIFIER? SEMICOLON ;
while_stmt       : label_decl? WHILE expr LOOP statement* END LOOP IDENTIFIER? SEMICOLON ;
for_stmt         : label_decl? FOR name_ref IN REVERSE? (expr (RANGE | TO) expr | list) LOOP statement* END LOOP IDENTIFIER? SEMICOLON ;
list             : LBRACKET expr_list? RBRACKET ;
label_decl       : LT LT IDENTIFIER GT GT ;
exit_stmt        : (EXIT | BREAK) IDENTIFIER? (WHEN expr)? SEMICOLON ;
continue_stmt    : CONTINUE IDENTIFIER? (WHEN expr)? SEMICOLON ;
fetch_stmt       : FETCH IDENTIFIER INTO target_list SEMICOLON ;
open_stmt        : OPEN IDENTIFIER (LPAREN expr_list? RPAREN)? SEMICOLON ;
close_stmt       : CLOSE IDENTIFIER SEMICOLON ;
raise_stmt       : RAISE IDENTIFIER? SEMICOLON ;
return_stmt      : RETURN SEMICOLON ;
commit_stmt      : COMMIT SEMICOLON ;
rollback_stmt    : ROLLBACK SEMICOLON ;
null_stmt        : (NULL | PASS) SEMICOLON ;

target_list      : name_ref (COMMA name_ref)* ;
name_ref         : IDENTIFIER | COLON_IDENTIFIER | QUOTED_IDENTIFIER ;

// ---------- 语法：表达式（分层，无歧义） ----------
expr_list : expr (COMMA expr)* ;

expr      : or_expr ;

or_expr   : and_expr (OR and_expr)* ;
and_expr  : not_expr (op+=(AND | AND2) not_expr)* ;
not_expr  : NOT not_expr
          | comparison
          ;
comparison
          : additive comparison_tail? ;
comparison_tail
          : (EQ | EQ2 | NEQ | NEQ2 | LT | LTE | GT | GTE) additive
          | IS NOT? NULL
          | NOT? LIKE additive
          | NOT? IN LPAREN expr_list RPAREN
          | NOT? BETWEEN additive AND additive
          ;
additive  : multiplicative (op+=(PLUS | MINUS | CONCAT) multiplicative)* ;
multiplicative
          : unary (op+=(STAR | SLASH | PERCENT) unary)* ;
unary     : (PLUS | MINUS | NOT | EXCL) unary
          | power ;
power     : primary (POWER unary)? ;
primary   : LPAREN expr RPAREN
          | name_ref PERCENT (FOUND | NOTFOUND | ROWCOUNT | ISOPEN)
          | function_name LPAREN expr_list? RPAREN
          | CASE (WHEN expr THEN expr)+ (ELSE expr)? END
          | NUMBER
          | STRING
          | TRUE
          | FALSE
          | NULL
          | name_ref
          ;

/** 函数名可以是限定名（schema.func / catalog.schema.func）。 */
function_name : IDENTIFIER (DOT IDENTIFIER)* ;
