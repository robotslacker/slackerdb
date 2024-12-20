// Generated from PlSqlParser.g4 by ANTLR 4.13.2
package org.slackerdb.plsql;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class PlSqlParserParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		T__0=1, T__1=2, T__2=3, T__3=4, T__4=5, T__5=6, T__6=7, T__7=8, T__8=9, 
		WS=10, CRLF=11, Null=12, Or=13, And=14, Equals=15, NEquals=16, GTEquals=17, 
		LTEquals=18, Pow=19, Excl=20, GT=21, LT=22, Add=23, Subtract=24, Multiply=25, 
		Divide=26, Modulus=27, OBrace=28, CBrace=29, OBracket=30, CBracket=31, 
		OParen=32, CParen=33, SColon=34, Assign=35, Comma=36, QMark=37, Colon=38, 
		DECLARE=39, BEGIN=40, END=41, CURSOR=42, IS=43, LOOP=44, FETCH=45, OPEN=46, 
		LET=47, CLOSE=48, BREAK=49, EXIT=50, WHEN=51, INTO=52, NOTFOUND=53, ELSEIF=54, 
		ENDIF=55, THEN=56, FOR=57, TO=58, ELSE=59, RETURN=60, EXCEPTION=61, PASS=62, 
		IN=63, IF=64, Bool=65, Number=66, Identifier=67, String=68, Comment=69;
	public static final int
		RULE_plsql_script = 0, RULE_declare_block = 1, RULE_begin_block = 2, RULE_begin_code_block = 3, 
		RULE_begin_exception_block = 4, RULE_sql_block = 5, RULE_if_block = 6, 
		RULE_elseif_block = 7, RULE_else_block = 8, RULE_exception = 9, RULE_loop_block = 10, 
		RULE_for_block = 11, RULE_variable_declaration = 12, RULE_variable_name = 13, 
		RULE_variable_defaultValue = 14, RULE_let = 15, RULE_sql = 16, RULE_fetchsql = 17, 
		RULE_sql_part = 18, RULE_sql_token = 19, RULE_cursor_open_statement = 20, 
		RULE_cursor_close_statement = 21, RULE_fetchstatement = 22, RULE_exit_when_statement = 23, 
		RULE_fetch_list = 24, RULE_if = 25, RULE_elseif = 26, RULE_else = 27, 
		RULE_breakloop = 28, RULE_endif = 29, RULE_datatype = 30, RULE_exit = 31, 
		RULE_functionCall = 32, RULE_exprList = 33, RULE_expression = 34, RULE_bindIdentifier = 35, 
		RULE_envIdentifier = 36, RULE_list = 37;
	private static String[] makeRuleNames() {
		return new String[] {
			"plsql_script", "declare_block", "begin_block", "begin_code_block", "begin_exception_block", 
			"sql_block", "if_block", "elseif_block", "else_block", "exception", "loop_block", 
			"for_block", "variable_declaration", "variable_name", "variable_defaultValue", 
			"let", "sql", "fetchsql", "sql_part", "sql_token", "cursor_open_statement", 
			"cursor_close_statement", "fetchstatement", "exit_when_statement", "fetch_list", 
			"if", "elseif", "else", "breakloop", "endif", "datatype", "exit", "functionCall", 
			"exprList", "expression", "bindIdentifier", "envIdentifier", "list"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "':='", "'INT'", "'TEXT'", "'BIGINT'", "'DOUBLE'", "'DATE'", "'TIMESTAMP'", 
			"'FLOAT'", "'${'", null, "'\\n'", "'null'", "'||'", "'&&'", "'=='", "'!='", 
			"'>='", "'<='", "'^'", "'!'", "'>'", "'<'", "'+'", "'-'", "'*'", "'/'", 
			"'%'", "'{'", "'}'", "'['", "']'", "'('", "')'", "';'", "'='", "','", 
			"'?'", "':'", "'DECLARE'", "'BEGIN'", "'END'", "'CURSOR'", "'IS'", "'LOOP'", 
			"'FETCH'", "'OPEN'", "'LET'", "'CLOSE'", "'BREAK'", "'EXIT'", "'WHEN'", 
			"'INTO'", "'NOTFOUND'", "'ELSEIF'", null, "'THEN'", "'FOR'", "'TO'", 
			"'ELSE'", "'RETURN'", "'EXCEPTION'", "'PASS'", "'in'", "'IF'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, null, null, null, null, null, null, null, null, null, "WS", "CRLF", 
			"Null", "Or", "And", "Equals", "NEquals", "GTEquals", "LTEquals", "Pow", 
			"Excl", "GT", "LT", "Add", "Subtract", "Multiply", "Divide", "Modulus", 
			"OBrace", "CBrace", "OBracket", "CBracket", "OParen", "CParen", "SColon", 
			"Assign", "Comma", "QMark", "Colon", "DECLARE", "BEGIN", "END", "CURSOR", 
			"IS", "LOOP", "FETCH", "OPEN", "LET", "CLOSE", "BREAK", "EXIT", "WHEN", 
			"INTO", "NOTFOUND", "ELSEIF", "ENDIF", "THEN", "FOR", "TO", "ELSE", "RETURN", 
			"EXCEPTION", "PASS", "IN", "IF", "Bool", "Number", "Identifier", "String", 
			"Comment"
		};
	}
	private static final String[] _SYMBOLIC_NAMES = makeSymbolicNames();
	public static final Vocabulary VOCABULARY = new VocabularyImpl(_LITERAL_NAMES, _SYMBOLIC_NAMES);

	/**
	 * @deprecated Use {@link #VOCABULARY} instead.
	 */
	@Deprecated
	public static final String[] tokenNames;
	static {
		tokenNames = new String[_SYMBOLIC_NAMES.length];
		for (int i = 0; i < tokenNames.length; i++) {
			tokenNames[i] = VOCABULARY.getLiteralName(i);
			if (tokenNames[i] == null) {
				tokenNames[i] = VOCABULARY.getSymbolicName(i);
			}

			if (tokenNames[i] == null) {
				tokenNames[i] = "<INVALID>";
			}
		}
	}

	@Override
	@Deprecated
	public String[] getTokenNames() {
		return tokenNames;
	}

	@Override

	public Vocabulary getVocabulary() {
		return VOCABULARY;
	}

	@Override
	public String getGrammarFileName() { return "PlSqlParser.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public ATN getATN() { return _ATN; }

	public PlSqlParserParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Plsql_scriptContext extends ParserRuleContext {
		public TerminalNode EOF() { return getToken(PlSqlParserParser.EOF, 0); }
		public Declare_blockContext declare_block() {
			return getRuleContext(Declare_blockContext.class,0);
		}
		public Begin_blockContext begin_block() {
			return getRuleContext(Begin_blockContext.class,0);
		}
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public Plsql_scriptContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_plsql_script; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitPlsql_script(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Plsql_scriptContext plsql_script() throws RecognitionException {
		Plsql_scriptContext _localctx = new Plsql_scriptContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_plsql_script);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(77);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==DECLARE) {
				{
				setState(76);
				declare_block();
				}
			}

			setState(80);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==BEGIN) {
				{
				setState(79);
				begin_block();
				}
			}

			setState(83);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==CRLF) {
				{
				setState(82);
				match(CRLF);
				}
			}

			setState(85);
			match(EOF);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Declare_blockContext extends ParserRuleContext {
		public TerminalNode DECLARE() { return getToken(PlSqlParserParser.DECLARE, 0); }
		public List<Variable_declarationContext> variable_declaration() {
			return getRuleContexts(Variable_declarationContext.class);
		}
		public Variable_declarationContext variable_declaration(int i) {
			return getRuleContext(Variable_declarationContext.class,i);
		}
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public Declare_blockContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_declare_block; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitDeclare_block(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Declare_blockContext declare_block() throws RecognitionException {
		Declare_blockContext _localctx = new Declare_blockContext(_ctx, getState());
		enterRule(_localctx, 2, RULE_declare_block);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(87);
			match(DECLARE);
			setState(91);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==CURSOR || _la==Identifier) {
				{
				{
				setState(88);
				variable_declaration();
				}
				}
				setState(93);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(95);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
			case 1:
				{
				setState(94);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Begin_blockContext extends ParserRuleContext {
		public TerminalNode BEGIN() { return getToken(PlSqlParserParser.BEGIN, 0); }
		public Begin_code_blockContext begin_code_block() {
			return getRuleContext(Begin_code_blockContext.class,0);
		}
		public TerminalNode END() { return getToken(PlSqlParserParser.END, 0); }
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public List<TerminalNode> CRLF() { return getTokens(PlSqlParserParser.CRLF); }
		public TerminalNode CRLF(int i) {
			return getToken(PlSqlParserParser.CRLF, i);
		}
		public ExceptionContext exception() {
			return getRuleContext(ExceptionContext.class,0);
		}
		public Begin_exception_blockContext begin_exception_block() {
			return getRuleContext(Begin_exception_blockContext.class,0);
		}
		public Begin_blockContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_begin_block; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitBegin_block(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Begin_blockContext begin_block() throws RecognitionException {
		Begin_blockContext _localctx = new Begin_blockContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_begin_block);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(97);
			match(BEGIN);
			setState(99);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,5,_ctx) ) {
			case 1:
				{
				setState(98);
				match(CRLF);
				}
				break;
			}
			setState(101);
			begin_code_block();
			setState(105);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==EXCEPTION) {
				{
				setState(102);
				exception();
				setState(103);
				begin_exception_block();
				}
			}

			setState(107);
			match(END);
			setState(108);
			match(SColon);
			setState(110);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,7,_ctx) ) {
			case 1:
				{
				setState(109);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Begin_code_blockContext extends ParserRuleContext {
		public List<If_blockContext> if_block() {
			return getRuleContexts(If_blockContext.class);
		}
		public If_blockContext if_block(int i) {
			return getRuleContext(If_blockContext.class,i);
		}
		public List<For_blockContext> for_block() {
			return getRuleContexts(For_blockContext.class);
		}
		public For_blockContext for_block(int i) {
			return getRuleContext(For_blockContext.class,i);
		}
		public List<Loop_blockContext> loop_block() {
			return getRuleContexts(Loop_blockContext.class);
		}
		public Loop_blockContext loop_block(int i) {
			return getRuleContext(Loop_blockContext.class,i);
		}
		public List<Sql_blockContext> sql_block() {
			return getRuleContexts(Sql_blockContext.class);
		}
		public Sql_blockContext sql_block(int i) {
			return getRuleContext(Sql_blockContext.class,i);
		}
		public Begin_code_blockContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_begin_code_block; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitBegin_code_block(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Begin_code_blockContext begin_code_block() throws RecognitionException {
		Begin_code_blockContext _localctx = new Begin_code_blockContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_begin_code_block);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(118);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while ((((_la) & ~0x3f) == 0 && ((1L << _la) & -2900320359049854978L) != 0) || ((((_la - 64)) & ~0x3f) == 0 && ((1L << (_la - 64)) & 63L) != 0)) {
				{
				setState(116);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,8,_ctx) ) {
				case 1:
					{
					setState(112);
					if_block();
					}
					break;
				case 2:
					{
					setState(113);
					for_block();
					}
					break;
				case 3:
					{
					setState(114);
					loop_block();
					}
					break;
				case 4:
					{
					setState(115);
					sql_block();
					}
					break;
				}
				}
				setState(120);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Begin_exception_blockContext extends ParserRuleContext {
		public List<If_blockContext> if_block() {
			return getRuleContexts(If_blockContext.class);
		}
		public If_blockContext if_block(int i) {
			return getRuleContext(If_blockContext.class,i);
		}
		public List<For_blockContext> for_block() {
			return getRuleContexts(For_blockContext.class);
		}
		public For_blockContext for_block(int i) {
			return getRuleContext(For_blockContext.class,i);
		}
		public List<Loop_blockContext> loop_block() {
			return getRuleContexts(Loop_blockContext.class);
		}
		public Loop_blockContext loop_block(int i) {
			return getRuleContext(Loop_blockContext.class,i);
		}
		public List<Sql_blockContext> sql_block() {
			return getRuleContexts(Sql_blockContext.class);
		}
		public Sql_blockContext sql_block(int i) {
			return getRuleContext(Sql_blockContext.class,i);
		}
		public Begin_exception_blockContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_begin_exception_block; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitBegin_exception_block(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Begin_exception_blockContext begin_exception_block() throws RecognitionException {
		Begin_exception_blockContext _localctx = new Begin_exception_blockContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_begin_exception_block);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(127);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while ((((_la) & ~0x3f) == 0 && ((1L << _la) & -2900320359049854978L) != 0) || ((((_la - 64)) & ~0x3f) == 0 && ((1L << (_la - 64)) & 63L) != 0)) {
				{
				setState(125);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,10,_ctx) ) {
				case 1:
					{
					setState(121);
					if_block();
					}
					break;
				case 2:
					{
					setState(122);
					for_block();
					}
					break;
				case 3:
					{
					setState(123);
					loop_block();
					}
					break;
				case 4:
					{
					setState(124);
					sql_block();
					}
					break;
				}
				}
				setState(129);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Sql_blockContext extends ParserRuleContext {
		public LetContext let() {
			return getRuleContext(LetContext.class,0);
		}
		public If_blockContext if_block() {
			return getRuleContext(If_blockContext.class,0);
		}
		public Begin_blockContext begin_block() {
			return getRuleContext(Begin_blockContext.class,0);
		}
		public FetchsqlContext fetchsql() {
			return getRuleContext(FetchsqlContext.class,0);
		}
		public BreakloopContext breakloop() {
			return getRuleContext(BreakloopContext.class,0);
		}
		public FetchstatementContext fetchstatement() {
			return getRuleContext(FetchstatementContext.class,0);
		}
		public Cursor_open_statementContext cursor_open_statement() {
			return getRuleContext(Cursor_open_statementContext.class,0);
		}
		public Cursor_close_statementContext cursor_close_statement() {
			return getRuleContext(Cursor_close_statementContext.class,0);
		}
		public ExitContext exit() {
			return getRuleContext(ExitContext.class,0);
		}
		public SqlContext sql() {
			return getRuleContext(SqlContext.class,0);
		}
		public Sql_blockContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_sql_block; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitSql_block(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Sql_blockContext sql_block() throws RecognitionException {
		Sql_blockContext _localctx = new Sql_blockContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_sql_block);
		try {
			setState(140);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,12,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(130);
				let();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(131);
				if_block();
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(132);
				begin_block();
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(133);
				fetchsql();
				}
				break;
			case 5:
				enterOuterAlt(_localctx, 5);
				{
				setState(134);
				breakloop();
				}
				break;
			case 6:
				enterOuterAlt(_localctx, 6);
				{
				setState(135);
				fetchstatement();
				}
				break;
			case 7:
				enterOuterAlt(_localctx, 7);
				{
				setState(136);
				cursor_open_statement();
				}
				break;
			case 8:
				enterOuterAlt(_localctx, 8);
				{
				setState(137);
				cursor_close_statement();
				}
				break;
			case 9:
				enterOuterAlt(_localctx, 9);
				{
				setState(138);
				exit();
				}
				break;
			case 10:
				enterOuterAlt(_localctx, 10);
				{
				setState(139);
				sql();
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class If_blockContext extends ParserRuleContext {
		public IfContext if_() {
			return getRuleContext(IfContext.class,0);
		}
		public EndifContext endif() {
			return getRuleContext(EndifContext.class,0);
		}
		public List<Sql_blockContext> sql_block() {
			return getRuleContexts(Sql_blockContext.class);
		}
		public Sql_blockContext sql_block(int i) {
			return getRuleContext(Sql_blockContext.class,i);
		}
		public List<Elseif_blockContext> elseif_block() {
			return getRuleContexts(Elseif_blockContext.class);
		}
		public Elseif_blockContext elseif_block(int i) {
			return getRuleContext(Elseif_blockContext.class,i);
		}
		public Else_blockContext else_block() {
			return getRuleContext(Else_blockContext.class,0);
		}
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public If_blockContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_if_block; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitIf_block(this);
			else return visitor.visitChildren(this);
		}
	}

	public final If_blockContext if_block() throws RecognitionException {
		If_blockContext _localctx = new If_blockContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_if_block);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(142);
			if_();
			setState(146);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,13,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(143);
					sql_block();
					}
					} 
				}
				setState(148);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,13,_ctx);
			}
			setState(152);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ELSEIF) {
				{
				{
				setState(149);
				elseif_block();
				}
				}
				setState(154);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(156);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ELSE) {
				{
				setState(155);
				else_block();
				}
			}

			setState(158);
			endif();
			setState(160);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,16,_ctx) ) {
			case 1:
				{
				setState(159);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Elseif_blockContext extends ParserRuleContext {
		public ElseifContext elseif() {
			return getRuleContext(ElseifContext.class,0);
		}
		public List<Sql_blockContext> sql_block() {
			return getRuleContexts(Sql_blockContext.class);
		}
		public Sql_blockContext sql_block(int i) {
			return getRuleContext(Sql_blockContext.class,i);
		}
		public Elseif_blockContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_elseif_block; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitElseif_block(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Elseif_blockContext elseif_block() throws RecognitionException {
		Elseif_blockContext _localctx = new Elseif_blockContext(_ctx, getState());
		enterRule(_localctx, 14, RULE_elseif_block);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(162);
			elseif();
			setState(166);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,17,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(163);
					sql_block();
					}
					} 
				}
				setState(168);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,17,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Else_blockContext extends ParserRuleContext {
		public ElseContext else_() {
			return getRuleContext(ElseContext.class,0);
		}
		public List<Sql_blockContext> sql_block() {
			return getRuleContexts(Sql_blockContext.class);
		}
		public Sql_blockContext sql_block(int i) {
			return getRuleContext(Sql_blockContext.class,i);
		}
		public Else_blockContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_else_block; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitElse_block(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Else_blockContext else_block() throws RecognitionException {
		Else_blockContext _localctx = new Else_blockContext(_ctx, getState());
		enterRule(_localctx, 16, RULE_else_block);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(169);
			else_();
			setState(173);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,18,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(170);
					sql_block();
					}
					} 
				}
				setState(175);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,18,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class ExceptionContext extends ParserRuleContext {
		public TerminalNode EXCEPTION() { return getToken(PlSqlParserParser.EXCEPTION, 0); }
		public TerminalNode Colon() { return getToken(PlSqlParserParser.Colon, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public ExceptionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_exception; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitException(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ExceptionContext exception() throws RecognitionException {
		ExceptionContext _localctx = new ExceptionContext(_ctx, getState());
		enterRule(_localctx, 18, RULE_exception);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(176);
			match(EXCEPTION);
			setState(177);
			match(Colon);
			setState(179);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,19,_ctx) ) {
			case 1:
				{
				setState(178);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Loop_blockContext extends ParserRuleContext {
		public List<TerminalNode> LOOP() { return getTokens(PlSqlParserParser.LOOP); }
		public TerminalNode LOOP(int i) {
			return getToken(PlSqlParserParser.LOOP, i);
		}
		public Exit_when_statementContext exit_when_statement() {
			return getRuleContext(Exit_when_statementContext.class,0);
		}
		public TerminalNode END() { return getToken(PlSqlParserParser.END, 0); }
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public List<TerminalNode> CRLF() { return getTokens(PlSqlParserParser.CRLF); }
		public TerminalNode CRLF(int i) {
			return getToken(PlSqlParserParser.CRLF, i);
		}
		public List<Sql_blockContext> sql_block() {
			return getRuleContexts(Sql_blockContext.class);
		}
		public Sql_blockContext sql_block(int i) {
			return getRuleContext(Sql_blockContext.class,i);
		}
		public Loop_blockContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_loop_block; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitLoop_block(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Loop_blockContext loop_block() throws RecognitionException {
		Loop_blockContext _localctx = new Loop_blockContext(_ctx, getState());
		enterRule(_localctx, 20, RULE_loop_block);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(181);
			match(LOOP);
			setState(183);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,20,_ctx) ) {
			case 1:
				{
				setState(182);
				match(CRLF);
				}
				break;
			}
			setState(188);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,21,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(185);
					sql_block();
					}
					} 
				}
				setState(190);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,21,_ctx);
			}
			setState(191);
			exit_when_statement();
			setState(195);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while ((((_la) & ~0x3f) == 0 && ((1L << _la) & -3044453139311755266L) != 0) || ((((_la - 64)) & ~0x3f) == 0 && ((1L << (_la - 64)) & 63L) != 0)) {
				{
				{
				setState(192);
				sql_block();
				}
				}
				setState(197);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(198);
			match(END);
			setState(199);
			match(LOOP);
			setState(200);
			match(SColon);
			setState(202);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,23,_ctx) ) {
			case 1:
				{
				setState(201);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class For_blockContext extends ParserRuleContext {
		public TerminalNode FOR() { return getToken(PlSqlParserParser.FOR, 0); }
		public BindIdentifierContext bindIdentifier() {
			return getRuleContext(BindIdentifierContext.class,0);
		}
		public TerminalNode IN() { return getToken(PlSqlParserParser.IN, 0); }
		public List<TerminalNode> LOOP() { return getTokens(PlSqlParserParser.LOOP); }
		public TerminalNode LOOP(int i) {
			return getToken(PlSqlParserParser.LOOP, i);
		}
		public TerminalNode END() { return getToken(PlSqlParserParser.END, 0); }
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public ListContext list() {
			return getRuleContext(ListContext.class,0);
		}
		public List<TerminalNode> CRLF() { return getTokens(PlSqlParserParser.CRLF); }
		public TerminalNode CRLF(int i) {
			return getToken(PlSqlParserParser.CRLF, i);
		}
		public List<Sql_blockContext> sql_block() {
			return getRuleContexts(Sql_blockContext.class);
		}
		public Sql_blockContext sql_block(int i) {
			return getRuleContext(Sql_blockContext.class,i);
		}
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode TO() { return getToken(PlSqlParserParser.TO, 0); }
		public For_blockContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_for_block; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitFor_block(this);
			else return visitor.visitChildren(this);
		}
	}

	public final For_blockContext for_block() throws RecognitionException {
		For_blockContext _localctx = new For_blockContext(_ctx, getState());
		enterRule(_localctx, 22, RULE_for_block);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(204);
			match(FOR);
			setState(205);
			bindIdentifier();
			setState(206);
			match(IN);
			setState(212);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,24,_ctx) ) {
			case 1:
				{
				{
				setState(207);
				expression(0);
				setState(208);
				match(TO);
				setState(209);
				expression(0);
				}
				}
				break;
			case 2:
				{
				setState(211);
				list();
				}
				break;
			}
			setState(214);
			match(LOOP);
			setState(216);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,25,_ctx) ) {
			case 1:
				{
				setState(215);
				match(CRLF);
				}
				break;
			}
			setState(221);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while ((((_la) & ~0x3f) == 0 && ((1L << _la) & -3044453139311755266L) != 0) || ((((_la - 64)) & ~0x3f) == 0 && ((1L << (_la - 64)) & 63L) != 0)) {
				{
				{
				setState(218);
				sql_block();
				}
				}
				setState(223);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(224);
			match(END);
			setState(225);
			match(LOOP);
			setState(226);
			match(SColon);
			setState(228);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,27,_ctx) ) {
			case 1:
				{
				setState(227);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Variable_declarationContext extends ParserRuleContext {
		public Variable_nameContext variable_name() {
			return getRuleContext(Variable_nameContext.class,0);
		}
		public DatatypeContext datatype() {
			return getRuleContext(DatatypeContext.class,0);
		}
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public Variable_defaultValueContext variable_defaultValue() {
			return getRuleContext(Variable_defaultValueContext.class,0);
		}
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public TerminalNode CURSOR() { return getToken(PlSqlParserParser.CURSOR, 0); }
		public TerminalNode IS() { return getToken(PlSqlParserParser.IS, 0); }
		public SqlContext sql() {
			return getRuleContext(SqlContext.class,0);
		}
		public Variable_declarationContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_variable_declaration; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitVariable_declaration(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Variable_declarationContext variable_declaration() throws RecognitionException {
		Variable_declarationContext _localctx = new Variable_declarationContext(_ctx, getState());
		enterRule(_localctx, 24, RULE_variable_declaration);
		int _la;
		try {
			setState(247);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case Identifier:
				enterOuterAlt(_localctx, 1);
				{
				setState(230);
				variable_name();
				setState(231);
				datatype();
				setState(234);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==T__0) {
					{
					setState(232);
					match(T__0);
					setState(233);
					variable_defaultValue();
					}
				}

				setState(236);
				match(SColon);
				setState(238);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,29,_ctx) ) {
				case 1:
					{
					setState(237);
					match(CRLF);
					}
					break;
				}
				}
				break;
			case CURSOR:
				enterOuterAlt(_localctx, 2);
				{
				setState(240);
				match(CURSOR);
				setState(241);
				variable_name();
				setState(242);
				match(IS);
				setState(243);
				sql();
				setState(245);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,30,_ctx) ) {
				case 1:
					{
					setState(244);
					match(CRLF);
					}
					break;
				}
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Variable_nameContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(PlSqlParserParser.Identifier, 0); }
		public Variable_nameContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_variable_name; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitVariable_name(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Variable_nameContext variable_name() throws RecognitionException {
		Variable_nameContext _localctx = new Variable_nameContext(_ctx, getState());
		enterRule(_localctx, 26, RULE_variable_name);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(249);
			match(Identifier);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Variable_defaultValueContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(PlSqlParserParser.Identifier, 0); }
		public Variable_defaultValueContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_variable_defaultValue; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitVariable_defaultValue(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Variable_defaultValueContext variable_defaultValue() throws RecognitionException {
		Variable_defaultValueContext _localctx = new Variable_defaultValueContext(_ctx, getState());
		enterRule(_localctx, 28, RULE_variable_defaultValue);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(251);
			match(Identifier);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class LetContext extends ParserRuleContext {
		public TerminalNode LET() { return getToken(PlSqlParserParser.LET, 0); }
		public TerminalNode Identifier() { return getToken(PlSqlParserParser.Identifier, 0); }
		public TerminalNode Assign() { return getToken(PlSqlParserParser.Assign, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public LetContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_let; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitLet(this);
			else return visitor.visitChildren(this);
		}
	}

	public final LetContext let() throws RecognitionException {
		LetContext _localctx = new LetContext(_ctx, getState());
		enterRule(_localctx, 30, RULE_let);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(253);
			match(LET);
			setState(254);
			match(Identifier);
			setState(255);
			match(Assign);
			setState(256);
			expression(0);
			setState(257);
			match(SColon);
			setState(259);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,32,_ctx) ) {
			case 1:
				{
				setState(258);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class SqlContext extends ParserRuleContext {
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public TerminalNode PASS() { return getToken(PlSqlParserParser.PASS, 0); }
		public Sql_partContext sql_part() {
			return getRuleContext(Sql_partContext.class,0);
		}
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public SqlContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_sql; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitSql(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SqlContext sql() throws RecognitionException {
		SqlContext _localctx = new SqlContext(_ctx, getState());
		enterRule(_localctx, 32, RULE_sql);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(263);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case PASS:
				{
				setState(261);
				match(PASS);
				}
				break;
			case T__0:
			case T__1:
			case T__2:
			case T__3:
			case T__4:
			case T__5:
			case T__6:
			case T__7:
			case T__8:
			case WS:
			case CRLF:
			case Null:
			case Or:
			case And:
			case Equals:
			case NEquals:
			case GTEquals:
			case LTEquals:
			case Pow:
			case Excl:
			case GT:
			case LT:
			case Add:
			case Subtract:
			case Multiply:
			case Divide:
			case Modulus:
			case OBrace:
			case CBrace:
			case OBracket:
			case CBracket:
			case OParen:
			case CParen:
			case SColon:
			case Assign:
			case Comma:
			case QMark:
			case Colon:
			case DECLARE:
			case BEGIN:
			case CURSOR:
			case IS:
			case LET:
			case WHEN:
			case INTO:
			case NOTFOUND:
			case ENDIF:
			case THEN:
			case TO:
			case RETURN:
			case IN:
			case IF:
			case Bool:
			case Number:
			case Identifier:
			case String:
			case Comment:
				{
				setState(262);
				sql_part();
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
			setState(265);
			match(SColon);
			setState(267);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,34,_ctx) ) {
			case 1:
				{
				setState(266);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class FetchsqlContext extends ParserRuleContext {
		public List<Sql_partContext> sql_part() {
			return getRuleContexts(Sql_partContext.class);
		}
		public Sql_partContext sql_part(int i) {
			return getRuleContext(Sql_partContext.class,i);
		}
		public Fetch_listContext fetch_list() {
			return getRuleContext(Fetch_listContext.class,0);
		}
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public FetchsqlContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_fetchsql; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitFetchsql(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FetchsqlContext fetchsql() throws RecognitionException {
		FetchsqlContext _localctx = new FetchsqlContext(_ctx, getState());
		enterRule(_localctx, 34, RULE_fetchsql);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(269);
			sql_part();
			setState(270);
			fetch_list();
			setState(271);
			sql_part();
			setState(272);
			match(SColon);
			setState(274);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,35,_ctx) ) {
			case 1:
				{
				setState(273);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Sql_partContext extends ParserRuleContext {
		public List<Sql_tokenContext> sql_token() {
			return getRuleContexts(Sql_tokenContext.class);
		}
		public Sql_tokenContext sql_token(int i) {
			return getRuleContext(Sql_tokenContext.class,i);
		}
		public Sql_partContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_sql_part; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitSql_part(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Sql_partContext sql_part() throws RecognitionException {
		Sql_partContext _localctx = new Sql_partContext(_ctx, getState());
		enterRule(_localctx, 36, RULE_sql_part);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(279);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,36,_ctx);
			while ( _alt!=1 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1+1 ) {
					{
					{
					setState(276);
					sql_token();
					}
					} 
				}
				setState(281);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,36,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Sql_tokenContext extends ParserRuleContext {
		public BindIdentifierContext bindIdentifier() {
			return getRuleContext(BindIdentifierContext.class,0);
		}
		public EnvIdentifierContext envIdentifier() {
			return getRuleContext(EnvIdentifierContext.class,0);
		}
		public TerminalNode IF() { return getToken(PlSqlParserParser.IF, 0); }
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public TerminalNode EXCEPTION() { return getToken(PlSqlParserParser.EXCEPTION, 0); }
		public TerminalNode LOOP() { return getToken(PlSqlParserParser.LOOP, 0); }
		public TerminalNode END() { return getToken(PlSqlParserParser.END, 0); }
		public TerminalNode FOR() { return getToken(PlSqlParserParser.FOR, 0); }
		public TerminalNode FETCH() { return getToken(PlSqlParserParser.FETCH, 0); }
		public TerminalNode EXIT() { return getToken(PlSqlParserParser.EXIT, 0); }
		public TerminalNode OPEN() { return getToken(PlSqlParserParser.OPEN, 0); }
		public TerminalNode CLOSE() { return getToken(PlSqlParserParser.CLOSE, 0); }
		public TerminalNode PASS() { return getToken(PlSqlParserParser.PASS, 0); }
		public TerminalNode ELSE() { return getToken(PlSqlParserParser.ELSE, 0); }
		public TerminalNode ELSEIF() { return getToken(PlSqlParserParser.ELSEIF, 0); }
		public TerminalNode BREAK() { return getToken(PlSqlParserParser.BREAK, 0); }
		public Sql_tokenContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_sql_token; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitSql_token(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Sql_tokenContext sql_token() throws RecognitionException {
		Sql_tokenContext _localctx = new Sql_tokenContext(_ctx, getState());
		enterRule(_localctx, 38, RULE_sql_token);
		int _la;
		try {
			setState(286);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,37,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(282);
				bindIdentifier();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(283);
				envIdentifier();
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(284);
				match(IF);
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				{
				setState(285);
				_la = _input.LA(1);
				if ( _la <= 0 || (((((_la - 34)) & ~0x3f) == 0 && ((1L << (_la - 34)) & 1519508609L) != 0)) ) {
				_errHandler.recoverInline(this);
				}
				else {
					if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
					_errHandler.reportMatch(this);
					consume();
				}
				}
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Cursor_open_statementContext extends ParserRuleContext {
		public TerminalNode OPEN() { return getToken(PlSqlParserParser.OPEN, 0); }
		public TerminalNode Identifier() { return getToken(PlSqlParserParser.Identifier, 0); }
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public Cursor_open_statementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_cursor_open_statement; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitCursor_open_statement(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Cursor_open_statementContext cursor_open_statement() throws RecognitionException {
		Cursor_open_statementContext _localctx = new Cursor_open_statementContext(_ctx, getState());
		enterRule(_localctx, 40, RULE_cursor_open_statement);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(288);
			match(OPEN);
			setState(289);
			match(Identifier);
			setState(290);
			match(SColon);
			setState(292);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,38,_ctx) ) {
			case 1:
				{
				setState(291);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Cursor_close_statementContext extends ParserRuleContext {
		public TerminalNode CLOSE() { return getToken(PlSqlParserParser.CLOSE, 0); }
		public TerminalNode Identifier() { return getToken(PlSqlParserParser.Identifier, 0); }
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public Cursor_close_statementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_cursor_close_statement; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitCursor_close_statement(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Cursor_close_statementContext cursor_close_statement() throws RecognitionException {
		Cursor_close_statementContext _localctx = new Cursor_close_statementContext(_ctx, getState());
		enterRule(_localctx, 42, RULE_cursor_close_statement);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(294);
			match(CLOSE);
			setState(295);
			match(Identifier);
			setState(296);
			match(SColon);
			setState(298);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,39,_ctx) ) {
			case 1:
				{
				setState(297);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class FetchstatementContext extends ParserRuleContext {
		public TerminalNode FETCH() { return getToken(PlSqlParserParser.FETCH, 0); }
		public TerminalNode Identifier() { return getToken(PlSqlParserParser.Identifier, 0); }
		public Fetch_listContext fetch_list() {
			return getRuleContext(Fetch_listContext.class,0);
		}
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public FetchstatementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_fetchstatement; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitFetchstatement(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FetchstatementContext fetchstatement() throws RecognitionException {
		FetchstatementContext _localctx = new FetchstatementContext(_ctx, getState());
		enterRule(_localctx, 44, RULE_fetchstatement);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(300);
			match(FETCH);
			setState(301);
			match(Identifier);
			setState(302);
			fetch_list();
			setState(303);
			match(SColon);
			setState(305);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,40,_ctx) ) {
			case 1:
				{
				setState(304);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Exit_when_statementContext extends ParserRuleContext {
		public TerminalNode EXIT() { return getToken(PlSqlParserParser.EXIT, 0); }
		public TerminalNode WHEN() { return getToken(PlSqlParserParser.WHEN, 0); }
		public TerminalNode Identifier() { return getToken(PlSqlParserParser.Identifier, 0); }
		public TerminalNode Modulus() { return getToken(PlSqlParserParser.Modulus, 0); }
		public TerminalNode NOTFOUND() { return getToken(PlSqlParserParser.NOTFOUND, 0); }
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public Exit_when_statementContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_exit_when_statement; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitExit_when_statement(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Exit_when_statementContext exit_when_statement() throws RecognitionException {
		Exit_when_statementContext _localctx = new Exit_when_statementContext(_ctx, getState());
		enterRule(_localctx, 46, RULE_exit_when_statement);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(307);
			match(EXIT);
			setState(308);
			match(WHEN);
			setState(309);
			match(Identifier);
			setState(310);
			match(Modulus);
			setState(311);
			match(NOTFOUND);
			setState(312);
			match(SColon);
			setState(314);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,41,_ctx) ) {
			case 1:
				{
				setState(313);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class Fetch_listContext extends ParserRuleContext {
		public TerminalNode INTO() { return getToken(PlSqlParserParser.INTO, 0); }
		public List<BindIdentifierContext> bindIdentifier() {
			return getRuleContexts(BindIdentifierContext.class);
		}
		public BindIdentifierContext bindIdentifier(int i) {
			return getRuleContext(BindIdentifierContext.class,i);
		}
		public List<TerminalNode> Comma() { return getTokens(PlSqlParserParser.Comma); }
		public TerminalNode Comma(int i) {
			return getToken(PlSqlParserParser.Comma, i);
		}
		public Fetch_listContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_fetch_list; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitFetch_list(this);
			else return visitor.visitChildren(this);
		}
	}

	public final Fetch_listContext fetch_list() throws RecognitionException {
		Fetch_listContext _localctx = new Fetch_listContext(_ctx, getState());
		enterRule(_localctx, 48, RULE_fetch_list);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(316);
			match(INTO);
			setState(317);
			bindIdentifier();
			setState(322);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,42,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(318);
					match(Comma);
					setState(319);
					bindIdentifier();
					}
					} 
				}
				setState(324);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,42,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class IfContext extends ParserRuleContext {
		public TerminalNode IF() { return getToken(PlSqlParserParser.IF, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode THEN() { return getToken(PlSqlParserParser.THEN, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public IfContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_if; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitIf(this);
			else return visitor.visitChildren(this);
		}
	}

	public final IfContext if_() throws RecognitionException {
		IfContext _localctx = new IfContext(_ctx, getState());
		enterRule(_localctx, 50, RULE_if);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(325);
			match(IF);
			setState(326);
			expression(0);
			setState(327);
			match(THEN);
			setState(329);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,43,_ctx) ) {
			case 1:
				{
				setState(328);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class ElseifContext extends ParserRuleContext {
		public TerminalNode ELSEIF() { return getToken(PlSqlParserParser.ELSEIF, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode THEN() { return getToken(PlSqlParserParser.THEN, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public ElseifContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_elseif; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitElseif(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ElseifContext elseif() throws RecognitionException {
		ElseifContext _localctx = new ElseifContext(_ctx, getState());
		enterRule(_localctx, 52, RULE_elseif);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(331);
			match(ELSEIF);
			setState(332);
			expression(0);
			setState(333);
			match(THEN);
			setState(335);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,44,_ctx) ) {
			case 1:
				{
				setState(334);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class ElseContext extends ParserRuleContext {
		public TerminalNode ELSE() { return getToken(PlSqlParserParser.ELSE, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public ElseContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_else; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitElse(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ElseContext else_() throws RecognitionException {
		ElseContext _localctx = new ElseContext(_ctx, getState());
		enterRule(_localctx, 54, RULE_else);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(337);
			match(ELSE);
			setState(339);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,45,_ctx) ) {
			case 1:
				{
				setState(338);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class BreakloopContext extends ParserRuleContext {
		public TerminalNode BREAK() { return getToken(PlSqlParserParser.BREAK, 0); }
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public BreakloopContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_breakloop; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitBreakloop(this);
			else return visitor.visitChildren(this);
		}
	}

	public final BreakloopContext breakloop() throws RecognitionException {
		BreakloopContext _localctx = new BreakloopContext(_ctx, getState());
		enterRule(_localctx, 56, RULE_breakloop);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(341);
			match(BREAK);
			setState(342);
			match(SColon);
			setState(344);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,46,_ctx) ) {
			case 1:
				{
				setState(343);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class EndifContext extends ParserRuleContext {
		public TerminalNode ENDIF() { return getToken(PlSqlParserParser.ENDIF, 0); }
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public EndifContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_endif; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitEndif(this);
			else return visitor.visitChildren(this);
		}
	}

	public final EndifContext endif() throws RecognitionException {
		EndifContext _localctx = new EndifContext(_ctx, getState());
		enterRule(_localctx, 58, RULE_endif);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(346);
			match(ENDIF);
			setState(347);
			match(SColon);
			setState(349);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,47,_ctx) ) {
			case 1:
				{
				setState(348);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class DatatypeContext extends ParserRuleContext {
		public DatatypeContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_datatype; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitDatatype(this);
			else return visitor.visitChildren(this);
		}
	}

	public final DatatypeContext datatype() throws RecognitionException {
		DatatypeContext _localctx = new DatatypeContext(_ctx, getState());
		enterRule(_localctx, 60, RULE_datatype);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(351);
			_la = _input.LA(1);
			if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 508L) != 0)) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class ExitContext extends ParserRuleContext {
		public TerminalNode EXIT() { return getToken(PlSqlParserParser.EXIT, 0); }
		public TerminalNode SColon() { return getToken(PlSqlParserParser.SColon, 0); }
		public TerminalNode CRLF() { return getToken(PlSqlParserParser.CRLF, 0); }
		public ExitContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_exit; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitExit(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ExitContext exit() throws RecognitionException {
		ExitContext _localctx = new ExitContext(_ctx, getState());
		enterRule(_localctx, 62, RULE_exit);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(353);
			match(EXIT);
			setState(354);
			match(SColon);
			setState(356);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,48,_ctx) ) {
			case 1:
				{
				setState(355);
				match(CRLF);
				}
				break;
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class FunctionCallContext extends ParserRuleContext {
		public FunctionCallContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_functionCall; }
	 
		public FunctionCallContext() { }
		public void copyFrom(FunctionCallContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class IdentifierFunctionCallContext extends FunctionCallContext {
		public TerminalNode Identifier() { return getToken(PlSqlParserParser.Identifier, 0); }
		public TerminalNode OParen() { return getToken(PlSqlParserParser.OParen, 0); }
		public TerminalNode CParen() { return getToken(PlSqlParserParser.CParen, 0); }
		public ExprListContext exprList() {
			return getRuleContext(ExprListContext.class,0);
		}
		public IdentifierFunctionCallContext(FunctionCallContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitIdentifierFunctionCall(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FunctionCallContext functionCall() throws RecognitionException {
		FunctionCallContext _localctx = new FunctionCallContext(_ctx, getState());
		enterRule(_localctx, 64, RULE_functionCall);
		int _la;
		try {
			_localctx = new IdentifierFunctionCallContext(_localctx);
			enterOuterAlt(_localctx, 1);
			{
			setState(358);
			match(Identifier);
			setState(359);
			match(OParen);
			setState(361);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 12)) & ~0x3f) == 0 && ((1L << (_la - 12)) & 135107988889538817L) != 0)) {
				{
				setState(360);
				exprList();
				}
			}

			setState(363);
			match(CParen);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class ExprListContext extends ParserRuleContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<TerminalNode> Comma() { return getTokens(PlSqlParserParser.Comma); }
		public TerminalNode Comma(int i) {
			return getToken(PlSqlParserParser.Comma, i);
		}
		public ExprListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_exprList; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitExprList(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ExprListContext exprList() throws RecognitionException {
		ExprListContext _localctx = new ExprListContext(_ctx, getState());
		enterRule(_localctx, 66, RULE_exprList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(365);
			expression(0);
			setState(370);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==Comma) {
				{
				{
				setState(366);
				match(Comma);
				setState(367);
				expression(0);
				}
				}
				setState(372);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class ExpressionContext extends ParserRuleContext {
		public ExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_expression; }
	 
		public ExpressionContext() { }
		public void copyFrom(ExpressionContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class BoolExpressionContext extends ExpressionContext {
		public TerminalNode Bool() { return getToken(PlSqlParserParser.Bool, 0); }
		public BoolExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitBoolExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NumberExpressionContext extends ExpressionContext {
		public TerminalNode Number() { return getToken(PlSqlParserParser.Number, 0); }
		public NumberExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitNumberExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class IdentifierExpressionContext extends ExpressionContext {
		public TerminalNode Identifier() { return getToken(PlSqlParserParser.Identifier, 0); }
		public IdentifierExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitIdentifierExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NotExpressionContext extends ExpressionContext {
		public TerminalNode Excl() { return getToken(PlSqlParserParser.Excl, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public NotExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitNotExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class BindIdentifierExpressionContext extends ExpressionContext {
		public BindIdentifierContext bindIdentifier() {
			return getRuleContext(BindIdentifierContext.class,0);
		}
		public BindIdentifierExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitBindIdentifierExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class OrExpressionContext extends ExpressionContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode Or() { return getToken(PlSqlParserParser.Or, 0); }
		public OrExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitOrExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class UnaryMinusExpressionContext extends ExpressionContext {
		public TerminalNode Subtract() { return getToken(PlSqlParserParser.Subtract, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public UnaryMinusExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitUnaryMinusExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class PowerExpressionContext extends ExpressionContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode Pow() { return getToken(PlSqlParserParser.Pow, 0); }
		public PowerExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitPowerExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class EqExpressionContext extends ExpressionContext {
		public Token op;
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode Equals() { return getToken(PlSqlParserParser.Equals, 0); }
		public TerminalNode NEquals() { return getToken(PlSqlParserParser.NEquals, 0); }
		public EqExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitEqExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class AndExpressionContext extends ExpressionContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode And() { return getToken(PlSqlParserParser.And, 0); }
		public AndExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitAndExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class InExpressionContext extends ExpressionContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode IN() { return getToken(PlSqlParserParser.IN, 0); }
		public InExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitInExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class StringExpressionContext extends ExpressionContext {
		public TerminalNode String() { return getToken(PlSqlParserParser.String, 0); }
		public StringExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitStringExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ExpressionExpressionContext extends ExpressionContext {
		public TerminalNode OParen() { return getToken(PlSqlParserParser.OParen, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode CParen() { return getToken(PlSqlParserParser.CParen, 0); }
		public ExpressionExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitExpressionExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class AddExpressionContext extends ExpressionContext {
		public Token op;
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode Add() { return getToken(PlSqlParserParser.Add, 0); }
		public TerminalNode Subtract() { return getToken(PlSqlParserParser.Subtract, 0); }
		public AddExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitAddExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class CompExpressionContext extends ExpressionContext {
		public Token op;
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode GTEquals() { return getToken(PlSqlParserParser.GTEquals, 0); }
		public TerminalNode LTEquals() { return getToken(PlSqlParserParser.LTEquals, 0); }
		public TerminalNode GT() { return getToken(PlSqlParserParser.GT, 0); }
		public TerminalNode LT() { return getToken(PlSqlParserParser.LT, 0); }
		public CompExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitCompExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NullExpressionContext extends ExpressionContext {
		public TerminalNode Null() { return getToken(PlSqlParserParser.Null, 0); }
		public NullExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitNullExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class FunctionCallExpressionContext extends ExpressionContext {
		public FunctionCallContext functionCall() {
			return getRuleContext(FunctionCallContext.class,0);
		}
		public FunctionCallExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitFunctionCallExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class MultExpressionContext extends ExpressionContext {
		public Token op;
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode Multiply() { return getToken(PlSqlParserParser.Multiply, 0); }
		public TerminalNode Divide() { return getToken(PlSqlParserParser.Divide, 0); }
		public TerminalNode Modulus() { return getToken(PlSqlParserParser.Modulus, 0); }
		public MultExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitMultExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ListExpressionContext extends ExpressionContext {
		public ListContext list() {
			return getRuleContext(ListContext.class,0);
		}
		public ListExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitListExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class TernaryExpressionContext extends ExpressionContext {
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode QMark() { return getToken(PlSqlParserParser.QMark, 0); }
		public TerminalNode Colon() { return getToken(PlSqlParserParser.Colon, 0); }
		public TernaryExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitTernaryExpression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ExpressionContext expression() throws RecognitionException {
		return expression(0);
	}

	private ExpressionContext expression(int _p) throws RecognitionException {
		ParserRuleContext _parentctx = _ctx;
		int _parentState = getState();
		ExpressionContext _localctx = new ExpressionContext(_ctx, _parentState);
		ExpressionContext _prevctx = _localctx;
		int _startState = 68;
		enterRecursionRule(_localctx, 68, RULE_expression, _p);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(390);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,51,_ctx) ) {
			case 1:
				{
				_localctx = new UnaryMinusExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;

				setState(374);
				match(Subtract);
				setState(375);
				expression(20);
				}
				break;
			case 2:
				{
				_localctx = new NotExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(376);
				match(Excl);
				setState(377);
				expression(19);
				}
				break;
			case 3:
				{
				_localctx = new NumberExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(378);
				match(Number);
				}
				break;
			case 4:
				{
				_localctx = new BoolExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(379);
				match(Bool);
				}
				break;
			case 5:
				{
				_localctx = new NullExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(380);
				match(Null);
				}
				break;
			case 6:
				{
				_localctx = new FunctionCallExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(381);
				functionCall();
				}
				break;
			case 7:
				{
				_localctx = new ListExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(382);
				list();
				}
				break;
			case 8:
				{
				_localctx = new IdentifierExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(383);
				match(Identifier);
				}
				break;
			case 9:
				{
				_localctx = new BindIdentifierExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(384);
				bindIdentifier();
				}
				break;
			case 10:
				{
				_localctx = new StringExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(385);
				match(String);
				}
				break;
			case 11:
				{
				_localctx = new ExpressionExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(386);
				match(OParen);
				setState(387);
				expression(0);
				setState(388);
				match(CParen);
				}
				break;
			}
			_ctx.stop = _input.LT(-1);
			setState(424);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,53,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					if ( _parseListeners!=null ) triggerExitRuleEvent();
					_prevctx = _localctx;
					{
					setState(422);
					_errHandler.sync(this);
					switch ( getInterpreter().adaptivePredict(_input,52,_ctx) ) {
					case 1:
						{
						_localctx = new PowerExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(392);
						if (!(precpred(_ctx, 18))) throw new FailedPredicateException(this, "precpred(_ctx, 18)");
						setState(393);
						match(Pow);
						setState(394);
						expression(18);
						}
						break;
					case 2:
						{
						_localctx = new MultExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(395);
						if (!(precpred(_ctx, 17))) throw new FailedPredicateException(this, "precpred(_ctx, 17)");
						setState(396);
						((MultExpressionContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 234881024L) != 0)) ) {
							((MultExpressionContext)_localctx).op = (Token)_errHandler.recoverInline(this);
						}
						else {
							if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
							_errHandler.reportMatch(this);
							consume();
						}
						setState(397);
						expression(18);
						}
						break;
					case 3:
						{
						_localctx = new AddExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(398);
						if (!(precpred(_ctx, 16))) throw new FailedPredicateException(this, "precpred(_ctx, 16)");
						setState(399);
						((AddExpressionContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !(_la==Add || _la==Subtract) ) {
							((AddExpressionContext)_localctx).op = (Token)_errHandler.recoverInline(this);
						}
						else {
							if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
							_errHandler.reportMatch(this);
							consume();
						}
						setState(400);
						expression(17);
						}
						break;
					case 4:
						{
						_localctx = new CompExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(401);
						if (!(precpred(_ctx, 15))) throw new FailedPredicateException(this, "precpred(_ctx, 15)");
						setState(402);
						((CompExpressionContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 6684672L) != 0)) ) {
							((CompExpressionContext)_localctx).op = (Token)_errHandler.recoverInline(this);
						}
						else {
							if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
							_errHandler.reportMatch(this);
							consume();
						}
						setState(403);
						expression(16);
						}
						break;
					case 5:
						{
						_localctx = new EqExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(404);
						if (!(precpred(_ctx, 14))) throw new FailedPredicateException(this, "precpred(_ctx, 14)");
						setState(405);
						((EqExpressionContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !(_la==Equals || _la==NEquals) ) {
							((EqExpressionContext)_localctx).op = (Token)_errHandler.recoverInline(this);
						}
						else {
							if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
							_errHandler.reportMatch(this);
							consume();
						}
						setState(406);
						expression(15);
						}
						break;
					case 6:
						{
						_localctx = new AndExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(407);
						if (!(precpred(_ctx, 13))) throw new FailedPredicateException(this, "precpred(_ctx, 13)");
						setState(408);
						match(And);
						setState(409);
						expression(14);
						}
						break;
					case 7:
						{
						_localctx = new OrExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(410);
						if (!(precpred(_ctx, 12))) throw new FailedPredicateException(this, "precpred(_ctx, 12)");
						setState(411);
						match(Or);
						setState(412);
						expression(13);
						}
						break;
					case 8:
						{
						_localctx = new TernaryExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(413);
						if (!(precpred(_ctx, 11))) throw new FailedPredicateException(this, "precpred(_ctx, 11)");
						setState(414);
						match(QMark);
						setState(415);
						expression(0);
						setState(416);
						match(Colon);
						setState(417);
						expression(12);
						}
						break;
					case 9:
						{
						_localctx = new InExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(419);
						if (!(precpred(_ctx, 10))) throw new FailedPredicateException(this, "precpred(_ctx, 10)");
						setState(420);
						match(IN);
						setState(421);
						expression(11);
						}
						break;
					}
					} 
				}
				setState(426);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,53,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			unrollRecursionContexts(_parentctx);
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class BindIdentifierContext extends ParserRuleContext {
		public TerminalNode Colon() { return getToken(PlSqlParserParser.Colon, 0); }
		public TerminalNode Identifier() { return getToken(PlSqlParserParser.Identifier, 0); }
		public BindIdentifierContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_bindIdentifier; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitBindIdentifier(this);
			else return visitor.visitChildren(this);
		}
	}

	public final BindIdentifierContext bindIdentifier() throws RecognitionException {
		BindIdentifierContext _localctx = new BindIdentifierContext(_ctx, getState());
		enterRule(_localctx, 70, RULE_bindIdentifier);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(427);
			match(Colon);
			setState(428);
			match(Identifier);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class EnvIdentifierContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(PlSqlParserParser.Identifier, 0); }
		public TerminalNode CBrace() { return getToken(PlSqlParserParser.CBrace, 0); }
		public EnvIdentifierContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_envIdentifier; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitEnvIdentifier(this);
			else return visitor.visitChildren(this);
		}
	}

	public final EnvIdentifierContext envIdentifier() throws RecognitionException {
		EnvIdentifierContext _localctx = new EnvIdentifierContext(_ctx, getState());
		enterRule(_localctx, 72, RULE_envIdentifier);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(430);
			match(T__8);
			setState(431);
			match(Identifier);
			setState(432);
			match(CBrace);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class ListContext extends ParserRuleContext {
		public TerminalNode OBracket() { return getToken(PlSqlParserParser.OBracket, 0); }
		public TerminalNode CBracket() { return getToken(PlSqlParserParser.CBracket, 0); }
		public ExprListContext exprList() {
			return getRuleContext(ExprListContext.class,0);
		}
		public ListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_list; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PlSqlParserVisitor ) return ((PlSqlParserVisitor<? extends T>)visitor).visitList(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ListContext list() throws RecognitionException {
		ListContext _localctx = new ListContext(_ctx, getState());
		enterRule(_localctx, 74, RULE_list);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(434);
			match(OBracket);
			setState(436);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 12)) & ~0x3f) == 0 && ((1L << (_la - 12)) & 135107988889538817L) != 0)) {
				{
				setState(435);
				exprList();
				}
			}

			setState(438);
			match(CBracket);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public boolean sempred(RuleContext _localctx, int ruleIndex, int predIndex) {
		switch (ruleIndex) {
		case 34:
			return expression_sempred((ExpressionContext)_localctx, predIndex);
		}
		return true;
	}
	private boolean expression_sempred(ExpressionContext _localctx, int predIndex) {
		switch (predIndex) {
		case 0:
			return precpred(_ctx, 18);
		case 1:
			return precpred(_ctx, 17);
		case 2:
			return precpred(_ctx, 16);
		case 3:
			return precpred(_ctx, 15);
		case 4:
			return precpred(_ctx, 14);
		case 5:
			return precpred(_ctx, 13);
		case 6:
			return precpred(_ctx, 12);
		case 7:
			return precpred(_ctx, 11);
		case 8:
			return precpred(_ctx, 10);
		}
		return true;
	}

	public static final String _serializedATN =
		"\u0004\u0001E\u01b9\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001\u0002"+
		"\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004\u0007\u0004\u0002"+
		"\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002\u0007\u0007\u0007\u0002"+
		"\b\u0007\b\u0002\t\u0007\t\u0002\n\u0007\n\u0002\u000b\u0007\u000b\u0002"+
		"\f\u0007\f\u0002\r\u0007\r\u0002\u000e\u0007\u000e\u0002\u000f\u0007\u000f"+
		"\u0002\u0010\u0007\u0010\u0002\u0011\u0007\u0011\u0002\u0012\u0007\u0012"+
		"\u0002\u0013\u0007\u0013\u0002\u0014\u0007\u0014\u0002\u0015\u0007\u0015"+
		"\u0002\u0016\u0007\u0016\u0002\u0017\u0007\u0017\u0002\u0018\u0007\u0018"+
		"\u0002\u0019\u0007\u0019\u0002\u001a\u0007\u001a\u0002\u001b\u0007\u001b"+
		"\u0002\u001c\u0007\u001c\u0002\u001d\u0007\u001d\u0002\u001e\u0007\u001e"+
		"\u0002\u001f\u0007\u001f\u0002 \u0007 \u0002!\u0007!\u0002\"\u0007\"\u0002"+
		"#\u0007#\u0002$\u0007$\u0002%\u0007%\u0001\u0000\u0003\u0000N\b\u0000"+
		"\u0001\u0000\u0003\u0000Q\b\u0000\u0001\u0000\u0003\u0000T\b\u0000\u0001"+
		"\u0000\u0001\u0000\u0001\u0001\u0001\u0001\u0005\u0001Z\b\u0001\n\u0001"+
		"\f\u0001]\t\u0001\u0001\u0001\u0003\u0001`\b\u0001\u0001\u0002\u0001\u0002"+
		"\u0003\u0002d\b\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002"+
		"\u0003\u0002j\b\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0003\u0002"+
		"o\b\u0002\u0001\u0003\u0001\u0003\u0001\u0003\u0001\u0003\u0005\u0003"+
		"u\b\u0003\n\u0003\f\u0003x\t\u0003\u0001\u0004\u0001\u0004\u0001\u0004"+
		"\u0001\u0004\u0005\u0004~\b\u0004\n\u0004\f\u0004\u0081\t\u0004\u0001"+
		"\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0001"+
		"\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0003\u0005\u008d\b\u0005\u0001"+
		"\u0006\u0001\u0006\u0005\u0006\u0091\b\u0006\n\u0006\f\u0006\u0094\t\u0006"+
		"\u0001\u0006\u0005\u0006\u0097\b\u0006\n\u0006\f\u0006\u009a\t\u0006\u0001"+
		"\u0006\u0003\u0006\u009d\b\u0006\u0001\u0006\u0001\u0006\u0003\u0006\u00a1"+
		"\b\u0006\u0001\u0007\u0001\u0007\u0005\u0007\u00a5\b\u0007\n\u0007\f\u0007"+
		"\u00a8\t\u0007\u0001\b\u0001\b\u0005\b\u00ac\b\b\n\b\f\b\u00af\t\b\u0001"+
		"\t\u0001\t\u0001\t\u0003\t\u00b4\b\t\u0001\n\u0001\n\u0003\n\u00b8\b\n"+
		"\u0001\n\u0005\n\u00bb\b\n\n\n\f\n\u00be\t\n\u0001\n\u0001\n\u0005\n\u00c2"+
		"\b\n\n\n\f\n\u00c5\t\n\u0001\n\u0001\n\u0001\n\u0001\n\u0003\n\u00cb\b"+
		"\n\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0003\u000b\u00d5\b\u000b\u0001\u000b\u0001\u000b"+
		"\u0003\u000b\u00d9\b\u000b\u0001\u000b\u0005\u000b\u00dc\b\u000b\n\u000b"+
		"\f\u000b\u00df\t\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0003\u000b\u00e5\b\u000b\u0001\f\u0001\f\u0001\f\u0001\f\u0003\f\u00eb"+
		"\b\f\u0001\f\u0001\f\u0003\f\u00ef\b\f\u0001\f\u0001\f\u0001\f\u0001\f"+
		"\u0001\f\u0003\f\u00f6\b\f\u0003\f\u00f8\b\f\u0001\r\u0001\r\u0001\u000e"+
		"\u0001\u000e\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f"+
		"\u0001\u000f\u0003\u000f\u0104\b\u000f\u0001\u0010\u0001\u0010\u0003\u0010"+
		"\u0108\b\u0010\u0001\u0010\u0001\u0010\u0003\u0010\u010c\b\u0010\u0001"+
		"\u0011\u0001\u0011\u0001\u0011\u0001\u0011\u0001\u0011\u0003\u0011\u0113"+
		"\b\u0011\u0001\u0012\u0005\u0012\u0116\b\u0012\n\u0012\f\u0012\u0119\t"+
		"\u0012\u0001\u0013\u0001\u0013\u0001\u0013\u0001\u0013\u0003\u0013\u011f"+
		"\b\u0013\u0001\u0014\u0001\u0014\u0001\u0014\u0001\u0014\u0003\u0014\u0125"+
		"\b\u0014\u0001\u0015\u0001\u0015\u0001\u0015\u0001\u0015\u0003\u0015\u012b"+
		"\b\u0015\u0001\u0016\u0001\u0016\u0001\u0016\u0001\u0016\u0001\u0016\u0003"+
		"\u0016\u0132\b\u0016\u0001\u0017\u0001\u0017\u0001\u0017\u0001\u0017\u0001"+
		"\u0017\u0001\u0017\u0001\u0017\u0003\u0017\u013b\b\u0017\u0001\u0018\u0001"+
		"\u0018\u0001\u0018\u0001\u0018\u0005\u0018\u0141\b\u0018\n\u0018\f\u0018"+
		"\u0144\t\u0018\u0001\u0019\u0001\u0019\u0001\u0019\u0001\u0019\u0003\u0019"+
		"\u014a\b\u0019\u0001\u001a\u0001\u001a\u0001\u001a\u0001\u001a\u0003\u001a"+
		"\u0150\b\u001a\u0001\u001b\u0001\u001b\u0003\u001b\u0154\b\u001b\u0001"+
		"\u001c\u0001\u001c\u0001\u001c\u0003\u001c\u0159\b\u001c\u0001\u001d\u0001"+
		"\u001d\u0001\u001d\u0003\u001d\u015e\b\u001d\u0001\u001e\u0001\u001e\u0001"+
		"\u001f\u0001\u001f\u0001\u001f\u0003\u001f\u0165\b\u001f\u0001 \u0001"+
		" \u0001 \u0003 \u016a\b \u0001 \u0001 \u0001!\u0001!\u0001!\u0005!\u0171"+
		"\b!\n!\f!\u0174\t!\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001"+
		"\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001"+
		"\"\u0001\"\u0003\"\u0187\b\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001"+
		"\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001"+
		"\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001"+
		"\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0005\"\u01a7\b\"\n"+
		"\"\f\"\u01aa\t\"\u0001#\u0001#\u0001#\u0001$\u0001$\u0001$\u0001$\u0001"+
		"%\u0001%\u0003%\u01b5\b%\u0001%\u0001%\u0001%\u0001\u0117\u0001D&\u0000"+
		"\u0002\u0004\u0006\b\n\f\u000e\u0010\u0012\u0014\u0016\u0018\u001a\u001c"+
		"\u001e \"$&(*,.02468:<>@BDFHJ\u0000\u0006\t\u0000\"\")),.026699;;=>@@"+
		"\u0001\u0000\u0002\b\u0001\u0000\u0019\u001b\u0001\u0000\u0017\u0018\u0002"+
		"\u0000\u0011\u0012\u0015\u0016\u0001\u0000\u000f\u0010\u01e7\u0000M\u0001"+
		"\u0000\u0000\u0000\u0002W\u0001\u0000\u0000\u0000\u0004a\u0001\u0000\u0000"+
		"\u0000\u0006v\u0001\u0000\u0000\u0000\b\u007f\u0001\u0000\u0000\u0000"+
		"\n\u008c\u0001\u0000\u0000\u0000\f\u008e\u0001\u0000\u0000\u0000\u000e"+
		"\u00a2\u0001\u0000\u0000\u0000\u0010\u00a9\u0001\u0000\u0000\u0000\u0012"+
		"\u00b0\u0001\u0000\u0000\u0000\u0014\u00b5\u0001\u0000\u0000\u0000\u0016"+
		"\u00cc\u0001\u0000\u0000\u0000\u0018\u00f7\u0001\u0000\u0000\u0000\u001a"+
		"\u00f9\u0001\u0000\u0000\u0000\u001c\u00fb\u0001\u0000\u0000\u0000\u001e"+
		"\u00fd\u0001\u0000\u0000\u0000 \u0107\u0001\u0000\u0000\u0000\"\u010d"+
		"\u0001\u0000\u0000\u0000$\u0117\u0001\u0000\u0000\u0000&\u011e\u0001\u0000"+
		"\u0000\u0000(\u0120\u0001\u0000\u0000\u0000*\u0126\u0001\u0000\u0000\u0000"+
		",\u012c\u0001\u0000\u0000\u0000.\u0133\u0001\u0000\u0000\u00000\u013c"+
		"\u0001\u0000\u0000\u00002\u0145\u0001\u0000\u0000\u00004\u014b\u0001\u0000"+
		"\u0000\u00006\u0151\u0001\u0000\u0000\u00008\u0155\u0001\u0000\u0000\u0000"+
		":\u015a\u0001\u0000\u0000\u0000<\u015f\u0001\u0000\u0000\u0000>\u0161"+
		"\u0001\u0000\u0000\u0000@\u0166\u0001\u0000\u0000\u0000B\u016d\u0001\u0000"+
		"\u0000\u0000D\u0186\u0001\u0000\u0000\u0000F\u01ab\u0001\u0000\u0000\u0000"+
		"H\u01ae\u0001\u0000\u0000\u0000J\u01b2\u0001\u0000\u0000\u0000LN\u0003"+
		"\u0002\u0001\u0000ML\u0001\u0000\u0000\u0000MN\u0001\u0000\u0000\u0000"+
		"NP\u0001\u0000\u0000\u0000OQ\u0003\u0004\u0002\u0000PO\u0001\u0000\u0000"+
		"\u0000PQ\u0001\u0000\u0000\u0000QS\u0001\u0000\u0000\u0000RT\u0005\u000b"+
		"\u0000\u0000SR\u0001\u0000\u0000\u0000ST\u0001\u0000\u0000\u0000TU\u0001"+
		"\u0000\u0000\u0000UV\u0005\u0000\u0000\u0001V\u0001\u0001\u0000\u0000"+
		"\u0000W[\u0005\'\u0000\u0000XZ\u0003\u0018\f\u0000YX\u0001\u0000\u0000"+
		"\u0000Z]\u0001\u0000\u0000\u0000[Y\u0001\u0000\u0000\u0000[\\\u0001\u0000"+
		"\u0000\u0000\\_\u0001\u0000\u0000\u0000][\u0001\u0000\u0000\u0000^`\u0005"+
		"\u000b\u0000\u0000_^\u0001\u0000\u0000\u0000_`\u0001\u0000\u0000\u0000"+
		"`\u0003\u0001\u0000\u0000\u0000ac\u0005(\u0000\u0000bd\u0005\u000b\u0000"+
		"\u0000cb\u0001\u0000\u0000\u0000cd\u0001\u0000\u0000\u0000de\u0001\u0000"+
		"\u0000\u0000ei\u0003\u0006\u0003\u0000fg\u0003\u0012\t\u0000gh\u0003\b"+
		"\u0004\u0000hj\u0001\u0000\u0000\u0000if\u0001\u0000\u0000\u0000ij\u0001"+
		"\u0000\u0000\u0000jk\u0001\u0000\u0000\u0000kl\u0005)\u0000\u0000ln\u0005"+
		"\"\u0000\u0000mo\u0005\u000b\u0000\u0000nm\u0001\u0000\u0000\u0000no\u0001"+
		"\u0000\u0000\u0000o\u0005\u0001\u0000\u0000\u0000pu\u0003\f\u0006\u0000"+
		"qu\u0003\u0016\u000b\u0000ru\u0003\u0014\n\u0000su\u0003\n\u0005\u0000"+
		"tp\u0001\u0000\u0000\u0000tq\u0001\u0000\u0000\u0000tr\u0001\u0000\u0000"+
		"\u0000ts\u0001\u0000\u0000\u0000ux\u0001\u0000\u0000\u0000vt\u0001\u0000"+
		"\u0000\u0000vw\u0001\u0000\u0000\u0000w\u0007\u0001\u0000\u0000\u0000"+
		"xv\u0001\u0000\u0000\u0000y~\u0003\f\u0006\u0000z~\u0003\u0016\u000b\u0000"+
		"{~\u0003\u0014\n\u0000|~\u0003\n\u0005\u0000}y\u0001\u0000\u0000\u0000"+
		"}z\u0001\u0000\u0000\u0000}{\u0001\u0000\u0000\u0000}|\u0001\u0000\u0000"+
		"\u0000~\u0081\u0001\u0000\u0000\u0000\u007f}\u0001\u0000\u0000\u0000\u007f"+
		"\u0080\u0001\u0000\u0000\u0000\u0080\t\u0001\u0000\u0000\u0000\u0081\u007f"+
		"\u0001\u0000\u0000\u0000\u0082\u008d\u0003\u001e\u000f\u0000\u0083\u008d"+
		"\u0003\f\u0006\u0000\u0084\u008d\u0003\u0004\u0002\u0000\u0085\u008d\u0003"+
		"\"\u0011\u0000\u0086\u008d\u00038\u001c\u0000\u0087\u008d\u0003,\u0016"+
		"\u0000\u0088\u008d\u0003(\u0014\u0000\u0089\u008d\u0003*\u0015\u0000\u008a"+
		"\u008d\u0003>\u001f\u0000\u008b\u008d\u0003 \u0010\u0000\u008c\u0082\u0001"+
		"\u0000\u0000\u0000\u008c\u0083\u0001\u0000\u0000\u0000\u008c\u0084\u0001"+
		"\u0000\u0000\u0000\u008c\u0085\u0001\u0000\u0000\u0000\u008c\u0086\u0001"+
		"\u0000\u0000\u0000\u008c\u0087\u0001\u0000\u0000\u0000\u008c\u0088\u0001"+
		"\u0000\u0000\u0000\u008c\u0089\u0001\u0000\u0000\u0000\u008c\u008a\u0001"+
		"\u0000\u0000\u0000\u008c\u008b\u0001\u0000\u0000\u0000\u008d\u000b\u0001"+
		"\u0000\u0000\u0000\u008e\u0092\u00032\u0019\u0000\u008f\u0091\u0003\n"+
		"\u0005\u0000\u0090\u008f\u0001\u0000\u0000\u0000\u0091\u0094\u0001\u0000"+
		"\u0000\u0000\u0092\u0090\u0001\u0000\u0000\u0000\u0092\u0093\u0001\u0000"+
		"\u0000\u0000\u0093\u0098\u0001\u0000\u0000\u0000\u0094\u0092\u0001\u0000"+
		"\u0000\u0000\u0095\u0097\u0003\u000e\u0007\u0000\u0096\u0095\u0001\u0000"+
		"\u0000\u0000\u0097\u009a\u0001\u0000\u0000\u0000\u0098\u0096\u0001\u0000"+
		"\u0000\u0000\u0098\u0099\u0001\u0000\u0000\u0000\u0099\u009c\u0001\u0000"+
		"\u0000\u0000\u009a\u0098\u0001\u0000\u0000\u0000\u009b\u009d\u0003\u0010"+
		"\b\u0000\u009c\u009b\u0001\u0000\u0000\u0000\u009c\u009d\u0001\u0000\u0000"+
		"\u0000\u009d\u009e\u0001\u0000\u0000\u0000\u009e\u00a0\u0003:\u001d\u0000"+
		"\u009f\u00a1\u0005\u000b\u0000\u0000\u00a0\u009f\u0001\u0000\u0000\u0000"+
		"\u00a0\u00a1\u0001\u0000\u0000\u0000\u00a1\r\u0001\u0000\u0000\u0000\u00a2"+
		"\u00a6\u00034\u001a\u0000\u00a3\u00a5\u0003\n\u0005\u0000\u00a4\u00a3"+
		"\u0001\u0000\u0000\u0000\u00a5\u00a8\u0001\u0000\u0000\u0000\u00a6\u00a4"+
		"\u0001\u0000\u0000\u0000\u00a6\u00a7\u0001\u0000\u0000\u0000\u00a7\u000f"+
		"\u0001\u0000\u0000\u0000\u00a8\u00a6\u0001\u0000\u0000\u0000\u00a9\u00ad"+
		"\u00036\u001b\u0000\u00aa\u00ac\u0003\n\u0005\u0000\u00ab\u00aa\u0001"+
		"\u0000\u0000\u0000\u00ac\u00af\u0001\u0000\u0000\u0000\u00ad\u00ab\u0001"+
		"\u0000\u0000\u0000\u00ad\u00ae\u0001\u0000\u0000\u0000\u00ae\u0011\u0001"+
		"\u0000\u0000\u0000\u00af\u00ad\u0001\u0000\u0000\u0000\u00b0\u00b1\u0005"+
		"=\u0000\u0000\u00b1\u00b3\u0005&\u0000\u0000\u00b2\u00b4\u0005\u000b\u0000"+
		"\u0000\u00b3\u00b2\u0001\u0000\u0000\u0000\u00b3\u00b4\u0001\u0000\u0000"+
		"\u0000\u00b4\u0013\u0001\u0000\u0000\u0000\u00b5\u00b7\u0005,\u0000\u0000"+
		"\u00b6\u00b8\u0005\u000b\u0000\u0000\u00b7\u00b6\u0001\u0000\u0000\u0000"+
		"\u00b7\u00b8\u0001\u0000\u0000\u0000\u00b8\u00bc\u0001\u0000\u0000\u0000"+
		"\u00b9\u00bb\u0003\n\u0005\u0000\u00ba\u00b9\u0001\u0000\u0000\u0000\u00bb"+
		"\u00be\u0001\u0000\u0000\u0000\u00bc\u00ba\u0001\u0000\u0000\u0000\u00bc"+
		"\u00bd\u0001\u0000\u0000\u0000\u00bd\u00bf\u0001\u0000\u0000\u0000\u00be"+
		"\u00bc\u0001\u0000\u0000\u0000\u00bf\u00c3\u0003.\u0017\u0000\u00c0\u00c2"+
		"\u0003\n\u0005\u0000\u00c1\u00c0\u0001\u0000\u0000\u0000\u00c2\u00c5\u0001"+
		"\u0000\u0000\u0000\u00c3\u00c1\u0001\u0000\u0000\u0000\u00c3\u00c4\u0001"+
		"\u0000\u0000\u0000\u00c4\u00c6\u0001\u0000\u0000\u0000\u00c5\u00c3\u0001"+
		"\u0000\u0000\u0000\u00c6\u00c7\u0005)\u0000\u0000\u00c7\u00c8\u0005,\u0000"+
		"\u0000\u00c8\u00ca\u0005\"\u0000\u0000\u00c9\u00cb\u0005\u000b\u0000\u0000"+
		"\u00ca\u00c9\u0001\u0000\u0000\u0000\u00ca\u00cb\u0001\u0000\u0000\u0000"+
		"\u00cb\u0015\u0001\u0000\u0000\u0000\u00cc\u00cd\u00059\u0000\u0000\u00cd"+
		"\u00ce\u0003F#\u0000\u00ce\u00d4\u0005?\u0000\u0000\u00cf\u00d0\u0003"+
		"D\"\u0000\u00d0\u00d1\u0005:\u0000\u0000\u00d1\u00d2\u0003D\"\u0000\u00d2"+
		"\u00d5\u0001\u0000\u0000\u0000\u00d3\u00d5\u0003J%\u0000\u00d4\u00cf\u0001"+
		"\u0000\u0000\u0000\u00d4\u00d3\u0001\u0000\u0000\u0000\u00d5\u00d6\u0001"+
		"\u0000\u0000\u0000\u00d6\u00d8\u0005,\u0000\u0000\u00d7\u00d9\u0005\u000b"+
		"\u0000\u0000\u00d8\u00d7\u0001\u0000\u0000\u0000\u00d8\u00d9\u0001\u0000"+
		"\u0000\u0000\u00d9\u00dd\u0001\u0000\u0000\u0000\u00da\u00dc\u0003\n\u0005"+
		"\u0000\u00db\u00da\u0001\u0000\u0000\u0000\u00dc\u00df\u0001\u0000\u0000"+
		"\u0000\u00dd\u00db\u0001\u0000\u0000\u0000\u00dd\u00de\u0001\u0000\u0000"+
		"\u0000\u00de\u00e0\u0001\u0000\u0000\u0000\u00df\u00dd\u0001\u0000\u0000"+
		"\u0000\u00e0\u00e1\u0005)\u0000\u0000\u00e1\u00e2\u0005,\u0000\u0000\u00e2"+
		"\u00e4\u0005\"\u0000\u0000\u00e3\u00e5\u0005\u000b\u0000\u0000\u00e4\u00e3"+
		"\u0001\u0000\u0000\u0000\u00e4\u00e5\u0001\u0000\u0000\u0000\u00e5\u0017"+
		"\u0001\u0000\u0000\u0000\u00e6\u00e7\u0003\u001a\r\u0000\u00e7\u00ea\u0003"+
		"<\u001e\u0000\u00e8\u00e9\u0005\u0001\u0000\u0000\u00e9\u00eb\u0003\u001c"+
		"\u000e\u0000\u00ea\u00e8\u0001\u0000\u0000\u0000\u00ea\u00eb\u0001\u0000"+
		"\u0000\u0000\u00eb\u00ec\u0001\u0000\u0000\u0000\u00ec\u00ee\u0005\"\u0000"+
		"\u0000\u00ed\u00ef\u0005\u000b\u0000\u0000\u00ee\u00ed\u0001\u0000\u0000"+
		"\u0000\u00ee\u00ef\u0001\u0000\u0000\u0000\u00ef\u00f8\u0001\u0000\u0000"+
		"\u0000\u00f0\u00f1\u0005*\u0000\u0000\u00f1\u00f2\u0003\u001a\r\u0000"+
		"\u00f2\u00f3\u0005+\u0000\u0000\u00f3\u00f5\u0003 \u0010\u0000\u00f4\u00f6"+
		"\u0005\u000b\u0000\u0000\u00f5\u00f4\u0001\u0000\u0000\u0000\u00f5\u00f6"+
		"\u0001\u0000\u0000\u0000\u00f6\u00f8\u0001\u0000\u0000\u0000\u00f7\u00e6"+
		"\u0001\u0000\u0000\u0000\u00f7\u00f0\u0001\u0000\u0000\u0000\u00f8\u0019"+
		"\u0001\u0000\u0000\u0000\u00f9\u00fa\u0005C\u0000\u0000\u00fa\u001b\u0001"+
		"\u0000\u0000\u0000\u00fb\u00fc\u0005C\u0000\u0000\u00fc\u001d\u0001\u0000"+
		"\u0000\u0000\u00fd\u00fe\u0005/\u0000\u0000\u00fe\u00ff\u0005C\u0000\u0000"+
		"\u00ff\u0100\u0005#\u0000\u0000\u0100\u0101\u0003D\"\u0000\u0101\u0103"+
		"\u0005\"\u0000\u0000\u0102\u0104\u0005\u000b\u0000\u0000\u0103\u0102\u0001"+
		"\u0000\u0000\u0000\u0103\u0104\u0001\u0000\u0000\u0000\u0104\u001f\u0001"+
		"\u0000\u0000\u0000\u0105\u0108\u0005>\u0000\u0000\u0106\u0108\u0003$\u0012"+
		"\u0000\u0107\u0105\u0001\u0000\u0000\u0000\u0107\u0106\u0001\u0000\u0000"+
		"\u0000\u0108\u0109\u0001\u0000\u0000\u0000\u0109\u010b\u0005\"\u0000\u0000"+
		"\u010a\u010c\u0005\u000b\u0000\u0000\u010b\u010a\u0001\u0000\u0000\u0000"+
		"\u010b\u010c\u0001\u0000\u0000\u0000\u010c!\u0001\u0000\u0000\u0000\u010d"+
		"\u010e\u0003$\u0012\u0000\u010e\u010f\u00030\u0018\u0000\u010f\u0110\u0003"+
		"$\u0012\u0000\u0110\u0112\u0005\"\u0000\u0000\u0111\u0113\u0005\u000b"+
		"\u0000\u0000\u0112\u0111\u0001\u0000\u0000\u0000\u0112\u0113\u0001\u0000"+
		"\u0000\u0000\u0113#\u0001\u0000\u0000\u0000\u0114\u0116\u0003&\u0013\u0000"+
		"\u0115\u0114\u0001\u0000\u0000\u0000\u0116\u0119\u0001\u0000\u0000\u0000"+
		"\u0117\u0118\u0001\u0000\u0000\u0000\u0117\u0115\u0001\u0000\u0000\u0000"+
		"\u0118%\u0001\u0000\u0000\u0000\u0119\u0117\u0001\u0000\u0000\u0000\u011a"+
		"\u011f\u0003F#\u0000\u011b\u011f\u0003H$\u0000\u011c\u011f\u0005@\u0000"+
		"\u0000\u011d\u011f\b\u0000\u0000\u0000\u011e\u011a\u0001\u0000\u0000\u0000"+
		"\u011e\u011b\u0001\u0000\u0000\u0000\u011e\u011c\u0001\u0000\u0000\u0000"+
		"\u011e\u011d\u0001\u0000\u0000\u0000\u011f\'\u0001\u0000\u0000\u0000\u0120"+
		"\u0121\u0005.\u0000\u0000\u0121\u0122\u0005C\u0000\u0000\u0122\u0124\u0005"+
		"\"\u0000\u0000\u0123\u0125\u0005\u000b\u0000\u0000\u0124\u0123\u0001\u0000"+
		"\u0000\u0000\u0124\u0125\u0001\u0000\u0000\u0000\u0125)\u0001\u0000\u0000"+
		"\u0000\u0126\u0127\u00050\u0000\u0000\u0127\u0128\u0005C\u0000\u0000\u0128"+
		"\u012a\u0005\"\u0000\u0000\u0129\u012b\u0005\u000b\u0000\u0000\u012a\u0129"+
		"\u0001\u0000\u0000\u0000\u012a\u012b\u0001\u0000\u0000\u0000\u012b+\u0001"+
		"\u0000\u0000\u0000\u012c\u012d\u0005-\u0000\u0000\u012d\u012e\u0005C\u0000"+
		"\u0000\u012e\u012f\u00030\u0018\u0000\u012f\u0131\u0005\"\u0000\u0000"+
		"\u0130\u0132\u0005\u000b\u0000\u0000\u0131\u0130\u0001\u0000\u0000\u0000"+
		"\u0131\u0132\u0001\u0000\u0000\u0000\u0132-\u0001\u0000\u0000\u0000\u0133"+
		"\u0134\u00052\u0000\u0000\u0134\u0135\u00053\u0000\u0000\u0135\u0136\u0005"+
		"C\u0000\u0000\u0136\u0137\u0005\u001b\u0000\u0000\u0137\u0138\u00055\u0000"+
		"\u0000\u0138\u013a\u0005\"\u0000\u0000\u0139\u013b\u0005\u000b\u0000\u0000"+
		"\u013a\u0139\u0001\u0000\u0000\u0000\u013a\u013b\u0001\u0000\u0000\u0000"+
		"\u013b/\u0001\u0000\u0000\u0000\u013c\u013d\u00054\u0000\u0000\u013d\u0142"+
		"\u0003F#\u0000\u013e\u013f\u0005$\u0000\u0000\u013f\u0141\u0003F#\u0000"+
		"\u0140\u013e\u0001\u0000\u0000\u0000\u0141\u0144\u0001\u0000\u0000\u0000"+
		"\u0142\u0140\u0001\u0000\u0000\u0000\u0142\u0143\u0001\u0000\u0000\u0000"+
		"\u01431\u0001\u0000\u0000\u0000\u0144\u0142\u0001\u0000\u0000\u0000\u0145"+
		"\u0146\u0005@\u0000\u0000\u0146\u0147\u0003D\"\u0000\u0147\u0149\u0005"+
		"8\u0000\u0000\u0148\u014a\u0005\u000b\u0000\u0000\u0149\u0148\u0001\u0000"+
		"\u0000\u0000\u0149\u014a\u0001\u0000\u0000\u0000\u014a3\u0001\u0000\u0000"+
		"\u0000\u014b\u014c\u00056\u0000\u0000\u014c\u014d\u0003D\"\u0000\u014d"+
		"\u014f\u00058\u0000\u0000\u014e\u0150\u0005\u000b\u0000\u0000\u014f\u014e"+
		"\u0001\u0000\u0000\u0000\u014f\u0150\u0001\u0000\u0000\u0000\u01505\u0001"+
		"\u0000\u0000\u0000\u0151\u0153\u0005;\u0000\u0000\u0152\u0154\u0005\u000b"+
		"\u0000\u0000\u0153\u0152\u0001\u0000\u0000\u0000\u0153\u0154\u0001\u0000"+
		"\u0000\u0000\u01547\u0001\u0000\u0000\u0000\u0155\u0156\u00051\u0000\u0000"+
		"\u0156\u0158\u0005\"\u0000\u0000\u0157\u0159\u0005\u000b\u0000\u0000\u0158"+
		"\u0157\u0001\u0000\u0000\u0000\u0158\u0159\u0001\u0000\u0000\u0000\u0159"+
		"9\u0001\u0000\u0000\u0000\u015a\u015b\u00057\u0000\u0000\u015b\u015d\u0005"+
		"\"\u0000\u0000\u015c\u015e\u0005\u000b\u0000\u0000\u015d\u015c\u0001\u0000"+
		"\u0000\u0000\u015d\u015e\u0001\u0000\u0000\u0000\u015e;\u0001\u0000\u0000"+
		"\u0000\u015f\u0160\u0007\u0001\u0000\u0000\u0160=\u0001\u0000\u0000\u0000"+
		"\u0161\u0162\u00052\u0000\u0000\u0162\u0164\u0005\"\u0000\u0000\u0163"+
		"\u0165\u0005\u000b\u0000\u0000\u0164\u0163\u0001\u0000\u0000\u0000\u0164"+
		"\u0165\u0001\u0000\u0000\u0000\u0165?\u0001\u0000\u0000\u0000\u0166\u0167"+
		"\u0005C\u0000\u0000\u0167\u0169\u0005 \u0000\u0000\u0168\u016a\u0003B"+
		"!\u0000\u0169\u0168\u0001\u0000\u0000\u0000\u0169\u016a\u0001\u0000\u0000"+
		"\u0000\u016a\u016b\u0001\u0000\u0000\u0000\u016b\u016c\u0005!\u0000\u0000"+
		"\u016cA\u0001\u0000\u0000\u0000\u016d\u0172\u0003D\"\u0000\u016e\u016f"+
		"\u0005$\u0000\u0000\u016f\u0171\u0003D\"\u0000\u0170\u016e\u0001\u0000"+
		"\u0000\u0000\u0171\u0174\u0001\u0000\u0000\u0000\u0172\u0170\u0001\u0000"+
		"\u0000\u0000\u0172\u0173\u0001\u0000\u0000\u0000\u0173C\u0001\u0000\u0000"+
		"\u0000\u0174\u0172\u0001\u0000\u0000\u0000\u0175\u0176\u0006\"\uffff\uffff"+
		"\u0000\u0176\u0177\u0005\u0018\u0000\u0000\u0177\u0187\u0003D\"\u0014"+
		"\u0178\u0179\u0005\u0014\u0000\u0000\u0179\u0187\u0003D\"\u0013\u017a"+
		"\u0187\u0005B\u0000\u0000\u017b\u0187\u0005A\u0000\u0000\u017c\u0187\u0005"+
		"\f\u0000\u0000\u017d\u0187\u0003@ \u0000\u017e\u0187\u0003J%\u0000\u017f"+
		"\u0187\u0005C\u0000\u0000\u0180\u0187\u0003F#\u0000\u0181\u0187\u0005"+
		"D\u0000\u0000\u0182\u0183\u0005 \u0000\u0000\u0183\u0184\u0003D\"\u0000"+
		"\u0184\u0185\u0005!\u0000\u0000\u0185\u0187\u0001\u0000\u0000\u0000\u0186"+
		"\u0175\u0001\u0000\u0000\u0000\u0186\u0178\u0001\u0000\u0000\u0000\u0186"+
		"\u017a\u0001\u0000\u0000\u0000\u0186\u017b\u0001\u0000\u0000\u0000\u0186"+
		"\u017c\u0001\u0000\u0000\u0000\u0186\u017d\u0001\u0000\u0000\u0000\u0186"+
		"\u017e\u0001\u0000\u0000\u0000\u0186\u017f\u0001\u0000\u0000\u0000\u0186"+
		"\u0180\u0001\u0000\u0000\u0000\u0186\u0181\u0001\u0000\u0000\u0000\u0186"+
		"\u0182\u0001\u0000\u0000\u0000\u0187\u01a8\u0001\u0000\u0000\u0000\u0188"+
		"\u0189\n\u0012\u0000\u0000\u0189\u018a\u0005\u0013\u0000\u0000\u018a\u01a7"+
		"\u0003D\"\u0012\u018b\u018c\n\u0011\u0000\u0000\u018c\u018d\u0007\u0002"+
		"\u0000\u0000\u018d\u01a7\u0003D\"\u0012\u018e\u018f\n\u0010\u0000\u0000"+
		"\u018f\u0190\u0007\u0003\u0000\u0000\u0190\u01a7\u0003D\"\u0011\u0191"+
		"\u0192\n\u000f\u0000\u0000\u0192\u0193\u0007\u0004\u0000\u0000\u0193\u01a7"+
		"\u0003D\"\u0010\u0194\u0195\n\u000e\u0000\u0000\u0195\u0196\u0007\u0005"+
		"\u0000\u0000\u0196\u01a7\u0003D\"\u000f\u0197\u0198\n\r\u0000\u0000\u0198"+
		"\u0199\u0005\u000e\u0000\u0000\u0199\u01a7\u0003D\"\u000e\u019a\u019b"+
		"\n\f\u0000\u0000\u019b\u019c\u0005\r\u0000\u0000\u019c\u01a7\u0003D\""+
		"\r\u019d\u019e\n\u000b\u0000\u0000\u019e\u019f\u0005%\u0000\u0000\u019f"+
		"\u01a0\u0003D\"\u0000\u01a0\u01a1\u0005&\u0000\u0000\u01a1\u01a2\u0003"+
		"D\"\f\u01a2\u01a7\u0001\u0000\u0000\u0000\u01a3\u01a4\n\n\u0000\u0000"+
		"\u01a4\u01a5\u0005?\u0000\u0000\u01a5\u01a7\u0003D\"\u000b\u01a6\u0188"+
		"\u0001\u0000\u0000\u0000\u01a6\u018b\u0001\u0000\u0000\u0000\u01a6\u018e"+
		"\u0001\u0000\u0000\u0000\u01a6\u0191\u0001\u0000\u0000\u0000\u01a6\u0194"+
		"\u0001\u0000\u0000\u0000\u01a6\u0197\u0001\u0000\u0000\u0000\u01a6\u019a"+
		"\u0001\u0000\u0000\u0000\u01a6\u019d\u0001\u0000\u0000\u0000\u01a6\u01a3"+
		"\u0001\u0000\u0000\u0000\u01a7\u01aa\u0001\u0000\u0000\u0000\u01a8\u01a6"+
		"\u0001\u0000\u0000\u0000\u01a8\u01a9\u0001\u0000\u0000\u0000\u01a9E\u0001"+
		"\u0000\u0000\u0000\u01aa\u01a8\u0001\u0000\u0000\u0000\u01ab\u01ac\u0005"+
		"&\u0000\u0000\u01ac\u01ad\u0005C\u0000\u0000\u01adG\u0001\u0000\u0000"+
		"\u0000\u01ae\u01af\u0005\t\u0000\u0000\u01af\u01b0\u0005C\u0000\u0000"+
		"\u01b0\u01b1\u0005\u001d\u0000\u0000\u01b1I\u0001\u0000\u0000\u0000\u01b2"+
		"\u01b4\u0005\u001e\u0000\u0000\u01b3\u01b5\u0003B!\u0000\u01b4\u01b3\u0001"+
		"\u0000\u0000\u0000\u01b4\u01b5\u0001\u0000\u0000\u0000\u01b5\u01b6\u0001"+
		"\u0000\u0000\u0000\u01b6\u01b7\u0005\u001f\u0000\u0000\u01b7K\u0001\u0000"+
		"\u0000\u00007MPS[_cintv}\u007f\u008c\u0092\u0098\u009c\u00a0\u00a6\u00ad"+
		"\u00b3\u00b7\u00bc\u00c3\u00ca\u00d4\u00d8\u00dd\u00e4\u00ea\u00ee\u00f5"+
		"\u00f7\u0103\u0107\u010b\u0112\u0117\u011e\u0124\u012a\u0131\u013a\u0142"+
		"\u0149\u014f\u0153\u0158\u015d\u0164\u0169\u0172\u0186\u01a6\u01a8\u01b4";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}