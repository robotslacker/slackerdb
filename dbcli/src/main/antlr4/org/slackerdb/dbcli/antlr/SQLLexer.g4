lexer grammar SQLLexer;
import BaseLexer;

options { 
    caseInsensitive = true;
}

channels {SQLSTATEMENT_CHANNEL }

@members {
    /**
     * 前向探查的上限：参数列表 + 返回类型 + AS 子句足够。超限即按"没有美元引号体"处理（= 既有行为）。
     */
    private static final int DOLLAR_LOOKAHEAD_LIMIT = 8192;

    /**
     * 前视判据（只用在 {@code CREATE [OR REPLACE] FUNCTION|PROCEDURE} 这一条上）：
     * **在 Oracle 终结符（行首 '/'）或输入结束之前**，是否存在一对完整的美元引号体
     * （{@code $$…$$}，或 {@code $} + 标签 + {@code $} 的带标签写法）。
     *
     * <p>为什么要它：两种写法光看关键字分不出来——</p>
     * <ol>
     *   <li>Oracle 风格 {@code create procedure p() begin … end;} 用**行首 '/'** 收尾；</li>
     *   <li>PostgreSQL 风格 {@code create function f() … as $$ … $$ language sql;} 用 ';' 收尾，
     *       正文里的 ';' 属于函数体（不能被当成语句终结符）。</li>
     * </ol>
     *
     * <p>找不到成对美元引号 ⇒ 返回 false ⇒ 仍走原来的 Oracle 规则，**既有语义不变**；
     * 只有确实写了成对美元引号体，才按普通 SQL 处理（';' 收尾）。</p>
     */
    private boolean pgDollarBodyAhead() {
        int offset = 1;
        for (int budget = 0; budget < DOLLAR_LOOKAHEAD_LIMIT; budget++, offset++) {
            int character = _input.LA(offset);
            if (character == org.antlr.v4.runtime.IntStream.EOF) {
                return false;
            }
            // 行首 '/'：Oracle 风格的语句终结符，它的出现说明这不是 PG 函数体
            if (character == '\n' && _input.LA(offset + 1) == '/') {
                return false;
            }
            if (character != '$') {
                continue;
            }
            int tagLength = dollarTagLength(offset);
            if (tagLength > 0 && dollarTagCloses(offset + tagLength, tagLength, offset)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 {@code offset} 起的美元引号开标签长度：{@code $$} 返回 2，带标签写法返回整段长度，
     * 不是标签（例如参数占位符 {@code $1}）返回 0。
     */
    private int dollarTagLength(int offset) {
        if (_input.LA(offset + 1) == '$') {
            return 2;
        }
        if (!isTagStart(_input.LA(offset + 1))) {
            return 0;
        }
        int cursor = offset + 2;
        while (isTagPart(_input.LA(cursor))) {
            cursor++;
        }
        return _input.LA(cursor) == '$' ? cursor - offset + 1 : 0;
    }

    /** 开标签之后是否还能找到**文本相同**的闭标签。 */
    private boolean dollarTagCloses(int from, int tagLength, int openOffset) {
        int offset = from;
        for (int budget = 0; budget < DOLLAR_LOOKAHEAD_LIMIT; budget++, offset++) {
            int character = _input.LA(offset);
            if (character == org.antlr.v4.runtime.IntStream.EOF) {
                return false;
            }
            if (character != '$') {
                continue;
            }
            boolean sameTag = true;
            for (int index = 0; index < tagLength; index++) {
                if (_input.LA(offset + index) != _input.LA(openOffset + index)) {
                    sameTag = false;
                    break;
                }
            }
            if (sameTag) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTagStart(int character) {
        return character == '_' || (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    }

    private static boolean isTagPart(int character) {
        return isTagStart(character) || (character >= '0' && character <= '9');
    }
}

// 关键字
CONNECT: '_CONNECT' -> pushMode(ConnectMode);
SESSION: '_SESSION' -> pushMode(SessionMode);

DISCONNECT: '_DISCONNECT';

// --SQL注释
MINUS_MINUS_COMMENT   : '--' .*? (CRLF | EOF) ->channel(HIDDEN);

// SQL创建语句
SQL_CREATE:     'CREATE' -> mode(SQLStatementMode) ;

/*
 * PostgreSQL 风格的 CREATE FUNCTION|PROCEDURE（美元引号体，';' 收尾）。
 *
 * 与下面 Oracle 风格的 SQL_CREATE_PROCEDURE 并存：两条规则匹配的前缀长度相同，
 * 由前视判据 pgDollarBodyAhead() 决定走哪条——判据不成立时本规则整体失配，
 * 仍然由 SQL_CREATE_PROCEDURE 接手（既有行为不变）。
 * 命中时按**普通 SQL**处理（type(SQL_CREATE)），于是 ';' 正常收尾，
 * 函数体里的 ';' 与换行 '/' 都由 SQLStatementMode 的美元引号规则整块保护。
 */
SQL_CREATE_PG_ROUTINE:
        ('CREATE' | 'REPLACE' | 'ALTER' | 'OR' | ' '+)+ ('PROCEDURE' | 'FUNCTION')
        {pgDollarBodyAhead()}? ->type(SQL_CREATE), mode(SQLStatementMode);

/*
 * 事务形式的 `BEGIN;`。
 *
 * 与块形式（`begin … end;` 换行 '/' 收尾，走 SQLProcedureMode）靠"关键字后面是不是 ';'"区分：
 * 本规则把 'BEGIN'、空格与那个 ';' 一起吃掉，因此不会再进入 PL/SQL 收集模式。
 * 如果写成 `BEGIN` 单独一行再换行（块形式的常见写法），本规则失配，仍由 SQL_BEGIN 接手。
 */
SQL_BEGIN_TXN:
        'BEGIN' [ \t]* ';' ->mode(DEFAULT_MODE);

/*
 * 通用 SQL 语句的**起始关键字**。
 *
 * 为什么需要：语句正文由 SQLStatementMode 整段收集，起始关键字只用来"认出这是一条 SQL"。
 * 不在表里的关键字（WITH / TRUNCATE / ALTER …）会被词法当成普通文本，
 * 于是这条语句落进"解析层认不下来"的分支（引擎把原始文本原样送给服务端）。
 *
 * 这里只登记**不带前缀、且不会与既有 `_` 命令或其它模式撞名**的关键字；
 * SET 的 IR 名统一走 "SQL"（不能叫 "SET"，否则会和 `_SET` 命令的 IR 名冲突）。
 * 这些语句的执行语义完全由服务端决定，客户端只负责切分与转发。
 */
SQL_GENERIC:
        ('WITH'
         | 'ALTER'
         | 'TRUNCATE'
         | 'VACUUM'
         | 'ANALYZE'
         | 'EXPLAIN'
         | 'SET'
         | 'SHOW'
         | 'GRANT'
         | 'REVOKE'
         | 'CALL'
         | 'COMMENT'
         | 'REFRESH'
         | 'COPY'
         | 'DO')
        ->mode(SQLStatementMode)
        ;
SQL_INSERT:     'INSERT' -> mode(SQLStatementMode) ;
SQL_UPDATE:     'UPDATE' -> mode(SQLStatementMode) ;
SQL_SELECT:     'SELECT' -> mode(SQLStatementMode) ;
SQL_DELETE:     'DELETE'  -> mode(SQLStatementMode) ;
SQL_REPLACE:    'REPLACE' -> mode(SQLStatementMode);
SQL_DECLARE:    'DECLARE' -> mode(SQLProcedureMode) ;
SQL_BEGIN:      'BEGIN' -> mode(SQLProcedureMode) ;
SQL_DROP:       'DROP' -> mode(SQLStatementMode) ;
SQL_COMMIT:     'COMMIT' -> mode(SQLStatementMode) ;
SQL_ROLLBACK:   'ROLLBACK' -> mode(SQLStatementMode) ;
SQL_CREATE_PROCEDURE: ('CREATE' | 'REPLACE' | 'ALTER'| ' '+ | 'OR')+ ('PROCEDURE'|'FUNCTION'|'CLASS'|'TRIGGER'|'PACKAGE')
                             ->mode(SQLProcedureMode);

/**
 * 数据库连接模式
 */
mode ConnectMode;

CONNECT_SPACE
            : [ \t]+ -> channel (HIDDEN) 
            ;

CONNECT_CRLF
            : '\n' ->type(CRLF), popMode
            ;

CONNECT_AT  
            : '@'
            ;

CONNECT_SLASH
            : '/'
            ;

CONNECT_COLON
            : ':'
            ;

CONNECT_QUESTION
            : '?'
            ;

CONNECT_POUND
            : '#'
            ;

CONNECT_OR
            : '|'
            ;

CONNECT_DASH
            : '//'
            ;

CONNECT_EQUAL
            : '='
            ;

CONNECT_PARA_AND
            : '&'
            ;

CONNECT_SEMICOLON
            : ';'
            ;

JDBC: 'JDBC';

IPV4
            : DIGIT+ '.' DIGIT+ '.' DIGIT+ '.' DIGIT+ 
            ;

IPV6
            : '[' (HEX | ':' | '.') + ']'
            ;

fragment CONNECT_DOUBLEQUOTE
            : '"' (~'"' | '\\' ('\r'? '\n' | .))* '"'
            ;

fragment CONNECT_SINGLEQUOTE
            : '\'' (~'\'' | '\\' ('\r'? '\n' | .))* '\''
            ;

CONNECT_STRING
            : (OBS_TEXT | UNRESERVED | PCTENCODED | CONNECT_DOUBLEQUOTE | CONNECT_SINGLEQUOTE | '{' | '}')+
            ;


/**
 * 注释模式
 */
mode CommentMode;

COMMENT_CRLF
      : '\n' ->type(CRLF), popMode
      ;

CommentString
      : ~('\n')+
      ;


/**
 * 提示模式
 */
mode HintMode;

HINT_SP
      : [ \t]+ -> channel (HIDDEN)
      ;

HINT
      : 'HINT'
      ;

HINT_SQUARE_OPEN
      : '[' ->type(SQUARE_OPEN)
      ;

HINT_SQUARE_CLOSE
      : ']' ->type(SQUARE_CLOSE)
      ;

HINT_CLOSE
      : '\n' -> popMode
      ;

HINT_STRING
      : String
      ;

HintMore
      : . -> type(HINT_STRING)
      ;

mode SessionMode;
SESSION_SPACE
            : [ \t]+ -> channel(SQLSTATEMENT_CHANNEL)
            ;
SESSION_SAVE
        : 'SAVE';
SESSION_RELEASE
        : 'RELEASE';
SESSION_RESTORE
        : 'RESTORE';
SESSION_SAVEURL
        : 'SAVEURL';
SESSION_SHOW
        : 'SHOW';
SESSION_NAME
            : String
            ;
SESSION_END
        : ';'->mode(DEFAULT_MODE)
        ;

/**
 * SQL语句
 */
mode SQLStatementMode;

SQL_CRLF
            : '\n' -> channel(SQLSTATEMENT_CHANNEL)
            ;

SQL_SPACE
            : [ \t]+ -> channel(SQLSTATEMENT_CHANNEL)
            ;

SQL_SLASH_END
            : '\n' '/' ->type(SQL_END), mode(DEFAULT_MODE)
            ;

SQL_END
            : ';' ->mode(DEFAULT_MODE)
            ;

/*
 * 语句正文里的行注释。
 *
 * 为什么要有：本模式把正文当"一堆文本"收集，原先 '--' 只是普通字符，
 * 于是注释里的撇号（`-- don't`）会被下面的"未闭合单引号"规则当成字符串开头，
 * 把后面的 ';' 一起吞掉。整行识别成注释后，注释里的任何字符都不再参与判定。
 * 文本仍然落在 SQLSTATEMENT_CHANNEL 上，因此语句原文（含注释）逐字不变。
 */
SQL_LINE_COMMENT
            : '--' ~('\n')* -> channel(SQLSTATEMENT_CHANNEL)
            ;

/*
 * 美元引号体：整块作为一个 token，内部的 ';' 与「换行 '/'」都不再是语句终结符。
 *
 * 两种写法都认：$$…$$ 与 $tag$…$tag$。
 * 闭标签按"再遇到一个同形状的 $…$"匹配（ANTLR 词法没有反向引用，因此只在文本上做形状匹配，
 * 不做标签一致性校验）——对实际脚本足够，且失配时的表现是"继续读下一行"，不会截断执行。
 *
 * 未闭合的 $$ 由下面的 SQL_DOLLAR_OPEN 承接（见 SqlAnalyzer 的"没写完"判定）。
 */
SQL_DOLLAR_QUOTE
            : '$$' .*? '$$' -> channel(SQLSTATEMENT_CHANNEL)
            ;

// 标签字母表只写大写：本语法开了 caseInsensitive，[A-Z] 同时覆盖大小写
SQL_DOLLAR_TAG_QUOTE
            : '$' [A-Z_] [A-Z0-9_]* '$' .*? '$' [A-Z_] [A-Z0-9_]* '$'
              -> channel(SQLSTATEMENT_CHANNEL)
            ;

/*
 * 落单的 '$$'（= 美元引号体没写完）。
 *
 * 为什么写成 `'$$' ~('$')*` 而不是只写 `'$$'`：词法按最长匹配决胜，只写 '$$' 会被
 * SQL_STRING（它把 "$$abc" 这类串整体匹配）盖掉，根本产不出这个 token 类型。
 * 加上 `~('$')*` 之后，本规则总是比开标签到下一个 '$' 为止更长，
 * 而**成对**的 $$…$$ 又总是比它更长，于是两者互不遮挡。
 * 多吃的那个 ';' 正是我们要的：语句没有终结符 ⇒ 判成"没写完"。
 */
SQL_DOLLAR_OPEN
            : '$$' ~('$')* -> channel(SQLSTATEMENT_CHANNEL)
            ;

SQL_STRING
            : String ->channel(SQLSTATEMENT_CHANNEL)
            ;

SQL_SINGLE
            : SingleQuoteString ->channel(SQLSTATEMENT_CHANNEL)
            ;

/*
 * 未闭合的引号：只在"整段文本里再也找不到闭合引号"时才命中（闭引号一旦存在，
 * 上面的 SQL_STRING / SQL_SINGLE 匹配更长，按最长匹配先赢）。
 *
 * 用途：多行字符串里出现 ';' 时不要把它当语句终结符（`select 'a` 换行 `b';`
 * 应当先要求下一行，而不是把第一行截断执行）。代价是它会把余下文本整段吞掉，
 * 所以前面必须先有行注释规则，否则注释里的撇号会误触发。
 */
SQL_SINGLE_UNCLOSED
            : '\'' ~('\'')* -> channel(SQLSTATEMENT_CHANNEL)
            ;

SQL_DOUBLE_UNCLOSED
            : '"' ~('"')* -> channel(SQLSTATEMENT_CHANNEL)
            ;

SQL_OTHER
            : . -> channel(SQLSTATEMENT_CHANNEL)
            ;

mode SQLProcedureMode;

SQL_PROCEDURE_CRLF
            : '\n' ->channel(SQLSTATEMENT_CHANNEL)
            ;

SQL_SLASH
            : '\n' '/'->mode(DEFAULT_MODE)
            ;

SQL_PROCEDURE_SLASH
            : '/' ->channel(SQLSTATEMENT_CHANNEL)
            ;

SQLProcedureStatement
            : ~('\n'|'/')+ -> channel(SQLSTATEMENT_CHANNEL)
            ;
