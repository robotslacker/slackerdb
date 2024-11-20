// Generated from PlSqlParser.g4 by ANTLR 4.13.2
package org.slackerdb.dbserver.plsql;
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
		T__0=1, T__1=2, T__2=3, T__3=4, T__4=5, T__5=6, T__6=7, T__7=8, WS=9, 
		CRLF=10, Null=11, Or=12, And=13, Equals=14, NEquals=15, GTEquals=16, LTEquals=17, 
		Pow=18, Excl=19, GT=20, LT=21, Add=22, Subtract=23, Multiply=24, Divide=25, 
		Modulus=26, OBrace=27, CBrace=28, OBracket=29, CBracket=30, OParen=31, 
		CParen=32, SColon=33, Assign=34, Comma=35, QMark=36, Colon=37, DECLARE=38, 
		BEGIN=39, END=40, CURSOR=41, IS=42, LOOP=43, FETCH=44, OPEN=45, LET=46, 
		CLOSE=47, BREAK=48, EXIT=49, WHEN=50, INTO=51, NOTFOUND=52, IF=53, ELSEIF=54, 
		ENDIF=55, THEN=56, FOR=57, TO=58, ELSE=59, RETURN=60, EXCEPTION=61, PASS=62, 
		IN=63, Bool=64, Number=65, Identifier=66, String=67, Comment=68;
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
		RULE_list = 36;
	private static String[] makeRuleNames() {
		return new String[] {
			"plsql_script", "declare_block", "begin_block", "begin_code_block", "begin_exception_block", 
			"sql_block", "if_block", "elseif_block", "else_block", "exception", "loop_block", 
			"for_block", "variable_declaration", "variable_name", "variable_defaultValue", 
			"let", "sql", "fetchsql", "sql_part", "sql_token", "cursor_open_statement", 
			"cursor_close_statement", "fetchstatement", "exit_when_statement", "fetch_list", 
			"if", "elseif", "else", "breakloop", "endif", "datatype", "exit", "functionCall", 
			"exprList", "expression", "bindIdentifier", "list"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "':='", "'INT'", "'TEXT'", "'BIGINT'", "'DOUBLE'", "'DATE'", "'TIMESTAMP'", 
			"'FLOAT'", null, "'\\n'", "'null'", "'||'", "'&&'", "'=='", "'!='", "'>='", 
			"'<='", "'^'", "'!'", "'>'", "'<'", "'+'", "'-'", "'*'", "'/'", "'%'", 
			"'{'", "'}'", "'['", "']'", "'('", "')'", "';'", "'='", "','", "'?'", 
			"':'", "'DECLARE'", "'BEGIN'", "'END'", "'CURSOR'", "'IS'", "'LOOP'", 
			"'FETCH'", "'OPEN'", "'LET'", "'CLOSE'", "'BREAK'", "'EXIT'", "'WHEN'", 
			"'INTO'", "'NOTFOUND'", "'IF'", "'ELSEIF'", null, "'THEN'", "'FOR'", 
			"'TO'", "'ELSE'", "'RETURN'", "'EXCEPTION'", "'PASS'", "'in'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, null, null, null, null, null, null, null, null, "WS", "CRLF", "Null", 
			"Or", "And", "Equals", "NEquals", "GTEquals", "LTEquals", "Pow", "Excl", 
			"GT", "LT", "Add", "Subtract", "Multiply", "Divide", "Modulus", "OBrace", 
			"CBrace", "OBracket", "CBracket", "OParen", "CParen", "SColon", "Assign", 
			"Comma", "QMark", "Colon", "DECLARE", "BEGIN", "END", "CURSOR", "IS", 
			"LOOP", "FETCH", "OPEN", "LET", "CLOSE", "BREAK", "EXIT", "WHEN", "INTO", 
			"NOTFOUND", "IF", "ELSEIF", "ENDIF", "THEN", "FOR", "TO", "ELSE", "RETURN", 
			"EXCEPTION", "PASS", "IN", "Bool", "Number", "Identifier", "String", 
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
			setState(75);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==DECLARE) {
				{
				setState(74);
				declare_block();
				}
			}

			setState(78);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==BEGIN) {
				{
				setState(77);
				begin_block();
				}
			}

			setState(81);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==CRLF) {
				{
				setState(80);
				match(CRLF);
				}
			}

			setState(83);
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
			setState(85);
			match(DECLARE);
			setState(89);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==CURSOR || _la==Identifier) {
				{
				{
				setState(86);
				variable_declaration();
				}
				}
				setState(91);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(93);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
			case 1:
				{
				setState(92);
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
			setState(95);
			match(BEGIN);
			setState(97);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,5,_ctx) ) {
			case 1:
				{
				setState(96);
				match(CRLF);
				}
				break;
			}
			setState(99);
			begin_code_block();
			setState(103);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==EXCEPTION) {
				{
				setState(100);
				exception();
				setState(101);
				begin_exception_block();
				}
			}

			setState(105);
			match(END);
			setState(106);
			match(SColon);
			setState(108);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,7,_ctx) ) {
			case 1:
				{
				setState(107);
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
			setState(116);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while ((((_la) & ~0x3f) == 0 && ((1L << _la) & -2900319259538227202L) != 0) || ((((_la - 64)) & ~0x3f) == 0 && ((1L << (_la - 64)) & 31L) != 0)) {
				{
				setState(114);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,8,_ctx) ) {
				case 1:
					{
					setState(110);
					if_block();
					}
					break;
				case 2:
					{
					setState(111);
					for_block();
					}
					break;
				case 3:
					{
					setState(112);
					loop_block();
					}
					break;
				case 4:
					{
					setState(113);
					sql_block();
					}
					break;
				}
				}
				setState(118);
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
			setState(125);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while ((((_la) & ~0x3f) == 0 && ((1L << _la) & -2900319259538227202L) != 0) || ((((_la - 64)) & ~0x3f) == 0 && ((1L << (_la - 64)) & 31L) != 0)) {
				{
				setState(123);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,10,_ctx) ) {
				case 1:
					{
					setState(119);
					if_block();
					}
					break;
				case 2:
					{
					setState(120);
					for_block();
					}
					break;
				case 3:
					{
					setState(121);
					loop_block();
					}
					break;
				case 4:
					{
					setState(122);
					sql_block();
					}
					break;
				}
				}
				setState(127);
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
			setState(138);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,12,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(128);
				let();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(129);
				if_block();
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(130);
				begin_block();
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(131);
				fetchsql();
				}
				break;
			case 5:
				enterOuterAlt(_localctx, 5);
				{
				setState(132);
				breakloop();
				}
				break;
			case 6:
				enterOuterAlt(_localctx, 6);
				{
				setState(133);
				fetchstatement();
				}
				break;
			case 7:
				enterOuterAlt(_localctx, 7);
				{
				setState(134);
				cursor_open_statement();
				}
				break;
			case 8:
				enterOuterAlt(_localctx, 8);
				{
				setState(135);
				cursor_close_statement();
				}
				break;
			case 9:
				enterOuterAlt(_localctx, 9);
				{
				setState(136);
				exit();
				}
				break;
			case 10:
				enterOuterAlt(_localctx, 10);
				{
				setState(137);
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
			setState(140);
			if_();
			setState(144);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,13,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(141);
					sql_block();
					}
					} 
				}
				setState(146);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,13,_ctx);
			}
			setState(150);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ELSEIF) {
				{
				{
				setState(147);
				elseif_block();
				}
				}
				setState(152);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(154);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ELSE) {
				{
				setState(153);
				else_block();
				}
			}

			setState(156);
			endif();
			setState(158);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,16,_ctx) ) {
			case 1:
				{
				setState(157);
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
			setState(160);
			elseif();
			setState(164);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,17,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(161);
					sql_block();
					}
					} 
				}
				setState(166);
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
			setState(167);
			else_();
			setState(171);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,18,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(168);
					sql_block();
					}
					} 
				}
				setState(173);
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
			setState(174);
			match(EXCEPTION);
			setState(175);
			match(Colon);
			setState(177);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,19,_ctx) ) {
			case 1:
				{
				setState(176);
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
			setState(179);
			match(LOOP);
			setState(181);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,20,_ctx) ) {
			case 1:
				{
				setState(180);
				match(CRLF);
				}
				break;
			}
			setState(186);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,21,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(183);
					sql_block();
					}
					} 
				}
				setState(188);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,21,_ctx);
			}
			setState(189);
			exit_when_statement();
			setState(193);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while ((((_la) & ~0x3f) == 0 && ((1L << _la) & -3044443243707105282L) != 0) || ((((_la - 64)) & ~0x3f) == 0 && ((1L << (_la - 64)) & 31L) != 0)) {
				{
				{
				setState(190);
				sql_block();
				}
				}
				setState(195);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(196);
			match(END);
			setState(197);
			match(LOOP);
			setState(198);
			match(SColon);
			setState(200);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,23,_ctx) ) {
			case 1:
				{
				setState(199);
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
			setState(202);
			match(FOR);
			setState(203);
			bindIdentifier();
			setState(204);
			match(IN);
			setState(210);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,24,_ctx) ) {
			case 1:
				{
				{
				setState(205);
				expression(0);
				setState(206);
				match(TO);
				setState(207);
				expression(0);
				}
				}
				break;
			case 2:
				{
				setState(209);
				list();
				}
				break;
			}
			setState(212);
			match(LOOP);
			setState(214);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,25,_ctx) ) {
			case 1:
				{
				setState(213);
				match(CRLF);
				}
				break;
			}
			setState(219);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while ((((_la) & ~0x3f) == 0 && ((1L << _la) & -3044443243707105282L) != 0) || ((((_la - 64)) & ~0x3f) == 0 && ((1L << (_la - 64)) & 31L) != 0)) {
				{
				{
				setState(216);
				sql_block();
				}
				}
				setState(221);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(222);
			match(END);
			setState(223);
			match(LOOP);
			setState(224);
			match(SColon);
			setState(226);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,27,_ctx) ) {
			case 1:
				{
				setState(225);
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
			setState(245);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case Identifier:
				enterOuterAlt(_localctx, 1);
				{
				setState(228);
				variable_name();
				setState(229);
				datatype();
				setState(232);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==T__0) {
					{
					setState(230);
					match(T__0);
					setState(231);
					variable_defaultValue();
					}
				}

				setState(234);
				match(SColon);
				setState(236);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,29,_ctx) ) {
				case 1:
					{
					setState(235);
					match(CRLF);
					}
					break;
				}
				}
				break;
			case CURSOR:
				enterOuterAlt(_localctx, 2);
				{
				setState(238);
				match(CURSOR);
				setState(239);
				variable_name();
				setState(240);
				match(IS);
				setState(241);
				sql();
				setState(243);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,30,_ctx) ) {
				case 1:
					{
					setState(242);
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
			setState(247);
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
			setState(251);
			match(LET);
			setState(252);
			match(Identifier);
			setState(253);
			match(Assign);
			setState(254);
			expression(0);
			setState(255);
			match(SColon);
			setState(257);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,32,_ctx) ) {
			case 1:
				{
				setState(256);
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
			setState(261);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case PASS:
				{
				setState(259);
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
			case Bool:
			case Number:
			case Identifier:
			case String:
			case Comment:
				{
				setState(260);
				sql_part();
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
			setState(263);
			match(SColon);
			setState(265);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,34,_ctx) ) {
			case 1:
				{
				setState(264);
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
			setState(267);
			sql_part();
			setState(268);
			fetch_list();
			setState(269);
			sql_part();
			setState(270);
			match(SColon);
			setState(272);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,35,_ctx) ) {
			case 1:
				{
				setState(271);
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
			setState(277);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,36,_ctx);
			while ( _alt!=1 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1+1 ) {
					{
					{
					setState(274);
					sql_token();
					}
					} 
				}
				setState(279);
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
		public TerminalNode IF() { return getToken(PlSqlParserParser.IF, 0); }
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
			setState(282);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,37,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(280);
				bindIdentifier();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				{
				setState(281);
				_la = _input.LA(1);
				if ( _la <= 0 || ((((_la) & ~0x3f) == 0 && ((1L << _la) & 7666174408955789312L) != 0)) ) {
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
			setState(284);
			match(OPEN);
			setState(285);
			match(Identifier);
			setState(286);
			match(SColon);
			setState(288);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,38,_ctx) ) {
			case 1:
				{
				setState(287);
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
			setState(290);
			match(CLOSE);
			setState(291);
			match(Identifier);
			setState(292);
			match(SColon);
			setState(294);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,39,_ctx) ) {
			case 1:
				{
				setState(293);
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
			setState(296);
			match(FETCH);
			setState(297);
			match(Identifier);
			setState(298);
			fetch_list();
			setState(299);
			match(SColon);
			setState(301);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,40,_ctx) ) {
			case 1:
				{
				setState(300);
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
			setState(303);
			match(EXIT);
			setState(304);
			match(WHEN);
			setState(305);
			match(Identifier);
			setState(306);
			match(Modulus);
			setState(307);
			match(NOTFOUND);
			setState(308);
			match(SColon);
			setState(310);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,41,_ctx) ) {
			case 1:
				{
				setState(309);
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
			setState(312);
			match(INTO);
			setState(313);
			bindIdentifier();
			setState(318);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,42,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(314);
					match(Comma);
					setState(315);
					bindIdentifier();
					}
					} 
				}
				setState(320);
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
			setState(321);
			match(IF);
			setState(322);
			expression(0);
			setState(323);
			match(THEN);
			setState(325);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,43,_ctx) ) {
			case 1:
				{
				setState(324);
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
			setState(327);
			match(ELSEIF);
			setState(328);
			expression(0);
			setState(329);
			match(THEN);
			setState(331);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,44,_ctx) ) {
			case 1:
				{
				setState(330);
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
			setState(333);
			match(ELSE);
			setState(335);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,45,_ctx) ) {
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
			setState(337);
			match(BREAK);
			setState(338);
			match(SColon);
			setState(340);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,46,_ctx) ) {
			case 1:
				{
				setState(339);
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
			setState(342);
			match(ENDIF);
			setState(343);
			match(SColon);
			setState(345);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,47,_ctx) ) {
			case 1:
				{
				setState(344);
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
			setState(347);
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
			setState(349);
			match(EXIT);
			setState(350);
			match(SColon);
			setState(352);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,48,_ctx) ) {
			case 1:
				{
				setState(351);
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
			setState(354);
			match(Identifier);
			setState(355);
			match(OParen);
			setState(357);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 11)) & ~0x3f) == 0 && ((1L << (_la - 11)) & 135107988889538817L) != 0)) {
				{
				setState(356);
				exprList();
				}
			}

			setState(359);
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
			setState(361);
			expression(0);
			setState(366);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==Comma) {
				{
				{
				setState(362);
				match(Comma);
				setState(363);
				expression(0);
				}
				}
				setState(368);
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
			setState(386);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,51,_ctx) ) {
			case 1:
				{
				_localctx = new UnaryMinusExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;

				setState(370);
				match(Subtract);
				setState(371);
				expression(20);
				}
				break;
			case 2:
				{
				_localctx = new NotExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(372);
				match(Excl);
				setState(373);
				expression(19);
				}
				break;
			case 3:
				{
				_localctx = new NumberExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(374);
				match(Number);
				}
				break;
			case 4:
				{
				_localctx = new BoolExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(375);
				match(Bool);
				}
				break;
			case 5:
				{
				_localctx = new NullExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(376);
				match(Null);
				}
				break;
			case 6:
				{
				_localctx = new FunctionCallExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(377);
				functionCall();
				}
				break;
			case 7:
				{
				_localctx = new ListExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(378);
				list();
				}
				break;
			case 8:
				{
				_localctx = new IdentifierExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(379);
				match(Identifier);
				}
				break;
			case 9:
				{
				_localctx = new BindIdentifierExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(380);
				bindIdentifier();
				}
				break;
			case 10:
				{
				_localctx = new StringExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(381);
				match(String);
				}
				break;
			case 11:
				{
				_localctx = new ExpressionExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(382);
				match(OParen);
				setState(383);
				expression(0);
				setState(384);
				match(CParen);
				}
				break;
			}
			_ctx.stop = _input.LT(-1);
			setState(420);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,53,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					if ( _parseListeners!=null ) triggerExitRuleEvent();
					_prevctx = _localctx;
					{
					setState(418);
					_errHandler.sync(this);
					switch ( getInterpreter().adaptivePredict(_input,52,_ctx) ) {
					case 1:
						{
						_localctx = new PowerExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(388);
						if (!(precpred(_ctx, 18))) throw new FailedPredicateException(this, "precpred(_ctx, 18)");
						setState(389);
						match(Pow);
						setState(390);
						expression(18);
						}
						break;
					case 2:
						{
						_localctx = new MultExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(391);
						if (!(precpred(_ctx, 17))) throw new FailedPredicateException(this, "precpred(_ctx, 17)");
						setState(392);
						((MultExpressionContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 117440512L) != 0)) ) {
							((MultExpressionContext)_localctx).op = (Token)_errHandler.recoverInline(this);
						}
						else {
							if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
							_errHandler.reportMatch(this);
							consume();
						}
						setState(393);
						expression(18);
						}
						break;
					case 3:
						{
						_localctx = new AddExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(394);
						if (!(precpred(_ctx, 16))) throw new FailedPredicateException(this, "precpred(_ctx, 16)");
						setState(395);
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
						setState(396);
						expression(17);
						}
						break;
					case 4:
						{
						_localctx = new CompExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(397);
						if (!(precpred(_ctx, 15))) throw new FailedPredicateException(this, "precpred(_ctx, 15)");
						setState(398);
						((CompExpressionContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 3342336L) != 0)) ) {
							((CompExpressionContext)_localctx).op = (Token)_errHandler.recoverInline(this);
						}
						else {
							if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
							_errHandler.reportMatch(this);
							consume();
						}
						setState(399);
						expression(16);
						}
						break;
					case 5:
						{
						_localctx = new EqExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(400);
						if (!(precpred(_ctx, 14))) throw new FailedPredicateException(this, "precpred(_ctx, 14)");
						setState(401);
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
						setState(402);
						expression(15);
						}
						break;
					case 6:
						{
						_localctx = new AndExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(403);
						if (!(precpred(_ctx, 13))) throw new FailedPredicateException(this, "precpred(_ctx, 13)");
						setState(404);
						match(And);
						setState(405);
						expression(14);
						}
						break;
					case 7:
						{
						_localctx = new OrExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(406);
						if (!(precpred(_ctx, 12))) throw new FailedPredicateException(this, "precpred(_ctx, 12)");
						setState(407);
						match(Or);
						setState(408);
						expression(13);
						}
						break;
					case 8:
						{
						_localctx = new TernaryExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(409);
						if (!(precpred(_ctx, 11))) throw new FailedPredicateException(this, "precpred(_ctx, 11)");
						setState(410);
						match(QMark);
						setState(411);
						expression(0);
						setState(412);
						match(Colon);
						setState(413);
						expression(12);
						}
						break;
					case 9:
						{
						_localctx = new InExpressionContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(415);
						if (!(precpred(_ctx, 10))) throw new FailedPredicateException(this, "precpred(_ctx, 10)");
						setState(416);
						match(IN);
						setState(417);
						expression(11);
						}
						break;
					}
					} 
				}
				setState(422);
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
			setState(423);
			match(Colon);
			setState(424);
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
		enterRule(_localctx, 72, RULE_list);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(426);
			match(OBracket);
			setState(428);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 11)) & ~0x3f) == 0 && ((1L << (_la - 11)) & 135107988889538817L) != 0)) {
				{
				setState(427);
				exprList();
				}
			}

			setState(430);
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
		"\u0004\u0001D\u01b1\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001\u0002"+
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
		"#\u0007#\u0002$\u0007$\u0001\u0000\u0003\u0000L\b\u0000\u0001\u0000\u0003"+
		"\u0000O\b\u0000\u0001\u0000\u0003\u0000R\b\u0000\u0001\u0000\u0001\u0000"+
		"\u0001\u0001\u0001\u0001\u0005\u0001X\b\u0001\n\u0001\f\u0001[\t\u0001"+
		"\u0001\u0001\u0003\u0001^\b\u0001\u0001\u0002\u0001\u0002\u0003\u0002"+
		"b\b\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0003\u0002"+
		"h\b\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0003\u0002m\b\u0002\u0001"+
		"\u0003\u0001\u0003\u0001\u0003\u0001\u0003\u0005\u0003s\b\u0003\n\u0003"+
		"\f\u0003v\t\u0003\u0001\u0004\u0001\u0004\u0001\u0004\u0001\u0004\u0005"+
		"\u0004|\b\u0004\n\u0004\f\u0004\u007f\t\u0004\u0001\u0005\u0001\u0005"+
		"\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0005"+
		"\u0001\u0005\u0001\u0005\u0003\u0005\u008b\b\u0005\u0001\u0006\u0001\u0006"+
		"\u0005\u0006\u008f\b\u0006\n\u0006\f\u0006\u0092\t\u0006\u0001\u0006\u0005"+
		"\u0006\u0095\b\u0006\n\u0006\f\u0006\u0098\t\u0006\u0001\u0006\u0003\u0006"+
		"\u009b\b\u0006\u0001\u0006\u0001\u0006\u0003\u0006\u009f\b\u0006\u0001"+
		"\u0007\u0001\u0007\u0005\u0007\u00a3\b\u0007\n\u0007\f\u0007\u00a6\t\u0007"+
		"\u0001\b\u0001\b\u0005\b\u00aa\b\b\n\b\f\b\u00ad\t\b\u0001\t\u0001\t\u0001"+
		"\t\u0003\t\u00b2\b\t\u0001\n\u0001\n\u0003\n\u00b6\b\n\u0001\n\u0005\n"+
		"\u00b9\b\n\n\n\f\n\u00bc\t\n\u0001\n\u0001\n\u0005\n\u00c0\b\n\n\n\f\n"+
		"\u00c3\t\n\u0001\n\u0001\n\u0001\n\u0001\n\u0003\n\u00c9\b\n\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0003\u000b\u00d3\b\u000b\u0001\u000b\u0001\u000b\u0003\u000b"+
		"\u00d7\b\u000b\u0001\u000b\u0005\u000b\u00da\b\u000b\n\u000b\f\u000b\u00dd"+
		"\t\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0003\u000b\u00e3"+
		"\b\u000b\u0001\f\u0001\f\u0001\f\u0001\f\u0003\f\u00e9\b\f\u0001\f\u0001"+
		"\f\u0003\f\u00ed\b\f\u0001\f\u0001\f\u0001\f\u0001\f\u0001\f\u0003\f\u00f4"+
		"\b\f\u0003\f\u00f6\b\f\u0001\r\u0001\r\u0001\u000e\u0001\u000e\u0001\u000f"+
		"\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f\u0003\u000f"+
		"\u0102\b\u000f\u0001\u0010\u0001\u0010\u0003\u0010\u0106\b\u0010\u0001"+
		"\u0010\u0001\u0010\u0003\u0010\u010a\b\u0010\u0001\u0011\u0001\u0011\u0001"+
		"\u0011\u0001\u0011\u0001\u0011\u0003\u0011\u0111\b\u0011\u0001\u0012\u0005"+
		"\u0012\u0114\b\u0012\n\u0012\f\u0012\u0117\t\u0012\u0001\u0013\u0001\u0013"+
		"\u0003\u0013\u011b\b\u0013\u0001\u0014\u0001\u0014\u0001\u0014\u0001\u0014"+
		"\u0003\u0014\u0121\b\u0014\u0001\u0015\u0001\u0015\u0001\u0015\u0001\u0015"+
		"\u0003\u0015\u0127\b\u0015\u0001\u0016\u0001\u0016\u0001\u0016\u0001\u0016"+
		"\u0001\u0016\u0003\u0016\u012e\b\u0016\u0001\u0017\u0001\u0017\u0001\u0017"+
		"\u0001\u0017\u0001\u0017\u0001\u0017\u0001\u0017\u0003\u0017\u0137\b\u0017"+
		"\u0001\u0018\u0001\u0018\u0001\u0018\u0001\u0018\u0005\u0018\u013d\b\u0018"+
		"\n\u0018\f\u0018\u0140\t\u0018\u0001\u0019\u0001\u0019\u0001\u0019\u0001"+
		"\u0019\u0003\u0019\u0146\b\u0019\u0001\u001a\u0001\u001a\u0001\u001a\u0001"+
		"\u001a\u0003\u001a\u014c\b\u001a\u0001\u001b\u0001\u001b\u0003\u001b\u0150"+
		"\b\u001b\u0001\u001c\u0001\u001c\u0001\u001c\u0003\u001c\u0155\b\u001c"+
		"\u0001\u001d\u0001\u001d\u0001\u001d\u0003\u001d\u015a\b\u001d\u0001\u001e"+
		"\u0001\u001e\u0001\u001f\u0001\u001f\u0001\u001f\u0003\u001f\u0161\b\u001f"+
		"\u0001 \u0001 \u0001 \u0003 \u0166\b \u0001 \u0001 \u0001!\u0001!\u0001"+
		"!\u0005!\u016d\b!\n!\f!\u0170\t!\u0001\"\u0001\"\u0001\"\u0001\"\u0001"+
		"\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001"+
		"\"\u0001\"\u0001\"\u0001\"\u0003\"\u0183\b\"\u0001\"\u0001\"\u0001\"\u0001"+
		"\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001"+
		"\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001"+
		"\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0001\"\u0005"+
		"\"\u01a3\b\"\n\"\f\"\u01a6\t\"\u0001#\u0001#\u0001#\u0001$\u0001$\u0003"+
		"$\u01ad\b$\u0001$\u0001$\u0001$\u0001\u0115\u0001D%\u0000\u0002\u0004"+
		"\u0006\b\n\f\u000e\u0010\u0012\u0014\u0016\u0018\u001a\u001c\u001e \""+
		"$&(*,.02468:<>@BDFH\u0000\u0006\b\u0000!!((+-/15699;;=>\u0001\u0000\u0002"+
		"\b\u0001\u0000\u0018\u001a\u0001\u0000\u0016\u0017\u0002\u0000\u0010\u0011"+
		"\u0014\u0015\u0001\u0000\u000e\u000f\u01de\u0000K\u0001\u0000\u0000\u0000"+
		"\u0002U\u0001\u0000\u0000\u0000\u0004_\u0001\u0000\u0000\u0000\u0006t"+
		"\u0001\u0000\u0000\u0000\b}\u0001\u0000\u0000\u0000\n\u008a\u0001\u0000"+
		"\u0000\u0000\f\u008c\u0001\u0000\u0000\u0000\u000e\u00a0\u0001\u0000\u0000"+
		"\u0000\u0010\u00a7\u0001\u0000\u0000\u0000\u0012\u00ae\u0001\u0000\u0000"+
		"\u0000\u0014\u00b3\u0001\u0000\u0000\u0000\u0016\u00ca\u0001\u0000\u0000"+
		"\u0000\u0018\u00f5\u0001\u0000\u0000\u0000\u001a\u00f7\u0001\u0000\u0000"+
		"\u0000\u001c\u00f9\u0001\u0000\u0000\u0000\u001e\u00fb\u0001\u0000\u0000"+
		"\u0000 \u0105\u0001\u0000\u0000\u0000\"\u010b\u0001\u0000\u0000\u0000"+
		"$\u0115\u0001\u0000\u0000\u0000&\u011a\u0001\u0000\u0000\u0000(\u011c"+
		"\u0001\u0000\u0000\u0000*\u0122\u0001\u0000\u0000\u0000,\u0128\u0001\u0000"+
		"\u0000\u0000.\u012f\u0001\u0000\u0000\u00000\u0138\u0001\u0000\u0000\u0000"+
		"2\u0141\u0001\u0000\u0000\u00004\u0147\u0001\u0000\u0000\u00006\u014d"+
		"\u0001\u0000\u0000\u00008\u0151\u0001\u0000\u0000\u0000:\u0156\u0001\u0000"+
		"\u0000\u0000<\u015b\u0001\u0000\u0000\u0000>\u015d\u0001\u0000\u0000\u0000"+
		"@\u0162\u0001\u0000\u0000\u0000B\u0169\u0001\u0000\u0000\u0000D\u0182"+
		"\u0001\u0000\u0000\u0000F\u01a7\u0001\u0000\u0000\u0000H\u01aa\u0001\u0000"+
		"\u0000\u0000JL\u0003\u0002\u0001\u0000KJ\u0001\u0000\u0000\u0000KL\u0001"+
		"\u0000\u0000\u0000LN\u0001\u0000\u0000\u0000MO\u0003\u0004\u0002\u0000"+
		"NM\u0001\u0000\u0000\u0000NO\u0001\u0000\u0000\u0000OQ\u0001\u0000\u0000"+
		"\u0000PR\u0005\n\u0000\u0000QP\u0001\u0000\u0000\u0000QR\u0001\u0000\u0000"+
		"\u0000RS\u0001\u0000\u0000\u0000ST\u0005\u0000\u0000\u0001T\u0001\u0001"+
		"\u0000\u0000\u0000UY\u0005&\u0000\u0000VX\u0003\u0018\f\u0000WV\u0001"+
		"\u0000\u0000\u0000X[\u0001\u0000\u0000\u0000YW\u0001\u0000\u0000\u0000"+
		"YZ\u0001\u0000\u0000\u0000Z]\u0001\u0000\u0000\u0000[Y\u0001\u0000\u0000"+
		"\u0000\\^\u0005\n\u0000\u0000]\\\u0001\u0000\u0000\u0000]^\u0001\u0000"+
		"\u0000\u0000^\u0003\u0001\u0000\u0000\u0000_a\u0005\'\u0000\u0000`b\u0005"+
		"\n\u0000\u0000a`\u0001\u0000\u0000\u0000ab\u0001\u0000\u0000\u0000bc\u0001"+
		"\u0000\u0000\u0000cg\u0003\u0006\u0003\u0000de\u0003\u0012\t\u0000ef\u0003"+
		"\b\u0004\u0000fh\u0001\u0000\u0000\u0000gd\u0001\u0000\u0000\u0000gh\u0001"+
		"\u0000\u0000\u0000hi\u0001\u0000\u0000\u0000ij\u0005(\u0000\u0000jl\u0005"+
		"!\u0000\u0000km\u0005\n\u0000\u0000lk\u0001\u0000\u0000\u0000lm\u0001"+
		"\u0000\u0000\u0000m\u0005\u0001\u0000\u0000\u0000ns\u0003\f\u0006\u0000"+
		"os\u0003\u0016\u000b\u0000ps\u0003\u0014\n\u0000qs\u0003\n\u0005\u0000"+
		"rn\u0001\u0000\u0000\u0000ro\u0001\u0000\u0000\u0000rp\u0001\u0000\u0000"+
		"\u0000rq\u0001\u0000\u0000\u0000sv\u0001\u0000\u0000\u0000tr\u0001\u0000"+
		"\u0000\u0000tu\u0001\u0000\u0000\u0000u\u0007\u0001\u0000\u0000\u0000"+
		"vt\u0001\u0000\u0000\u0000w|\u0003\f\u0006\u0000x|\u0003\u0016\u000b\u0000"+
		"y|\u0003\u0014\n\u0000z|\u0003\n\u0005\u0000{w\u0001\u0000\u0000\u0000"+
		"{x\u0001\u0000\u0000\u0000{y\u0001\u0000\u0000\u0000{z\u0001\u0000\u0000"+
		"\u0000|\u007f\u0001\u0000\u0000\u0000}{\u0001\u0000\u0000\u0000}~\u0001"+
		"\u0000\u0000\u0000~\t\u0001\u0000\u0000\u0000\u007f}\u0001\u0000\u0000"+
		"\u0000\u0080\u008b\u0003\u001e\u000f\u0000\u0081\u008b\u0003\f\u0006\u0000"+
		"\u0082\u008b\u0003\u0004\u0002\u0000\u0083\u008b\u0003\"\u0011\u0000\u0084"+
		"\u008b\u00038\u001c\u0000\u0085\u008b\u0003,\u0016\u0000\u0086\u008b\u0003"+
		"(\u0014\u0000\u0087\u008b\u0003*\u0015\u0000\u0088\u008b\u0003>\u001f"+
		"\u0000\u0089\u008b\u0003 \u0010\u0000\u008a\u0080\u0001\u0000\u0000\u0000"+
		"\u008a\u0081\u0001\u0000\u0000\u0000\u008a\u0082\u0001\u0000\u0000\u0000"+
		"\u008a\u0083\u0001\u0000\u0000\u0000\u008a\u0084\u0001\u0000\u0000\u0000"+
		"\u008a\u0085\u0001\u0000\u0000\u0000\u008a\u0086\u0001\u0000\u0000\u0000"+
		"\u008a\u0087\u0001\u0000\u0000\u0000\u008a\u0088\u0001\u0000\u0000\u0000"+
		"\u008a\u0089\u0001\u0000\u0000\u0000\u008b\u000b\u0001\u0000\u0000\u0000"+
		"\u008c\u0090\u00032\u0019\u0000\u008d\u008f\u0003\n\u0005\u0000\u008e"+
		"\u008d\u0001\u0000\u0000\u0000\u008f\u0092\u0001\u0000\u0000\u0000\u0090"+
		"\u008e\u0001\u0000\u0000\u0000\u0090\u0091\u0001\u0000\u0000\u0000\u0091"+
		"\u0096\u0001\u0000\u0000\u0000\u0092\u0090\u0001\u0000\u0000\u0000\u0093"+
		"\u0095\u0003\u000e\u0007\u0000\u0094\u0093\u0001\u0000\u0000\u0000\u0095"+
		"\u0098\u0001\u0000\u0000\u0000\u0096\u0094\u0001\u0000\u0000\u0000\u0096"+
		"\u0097\u0001\u0000\u0000\u0000\u0097\u009a\u0001\u0000\u0000\u0000\u0098"+
		"\u0096\u0001\u0000\u0000\u0000\u0099\u009b\u0003\u0010\b\u0000\u009a\u0099"+
		"\u0001\u0000\u0000\u0000\u009a\u009b\u0001\u0000\u0000\u0000\u009b\u009c"+
		"\u0001\u0000\u0000\u0000\u009c\u009e\u0003:\u001d\u0000\u009d\u009f\u0005"+
		"\n\u0000\u0000\u009e\u009d\u0001\u0000\u0000\u0000\u009e\u009f\u0001\u0000"+
		"\u0000\u0000\u009f\r\u0001\u0000\u0000\u0000\u00a0\u00a4\u00034\u001a"+
		"\u0000\u00a1\u00a3\u0003\n\u0005\u0000\u00a2\u00a1\u0001\u0000\u0000\u0000"+
		"\u00a3\u00a6\u0001\u0000\u0000\u0000\u00a4\u00a2\u0001\u0000\u0000\u0000"+
		"\u00a4\u00a5\u0001\u0000\u0000\u0000\u00a5\u000f\u0001\u0000\u0000\u0000"+
		"\u00a6\u00a4\u0001\u0000\u0000\u0000\u00a7\u00ab\u00036\u001b\u0000\u00a8"+
		"\u00aa\u0003\n\u0005\u0000\u00a9\u00a8\u0001\u0000\u0000\u0000\u00aa\u00ad"+
		"\u0001\u0000\u0000\u0000\u00ab\u00a9\u0001\u0000\u0000\u0000\u00ab\u00ac"+
		"\u0001\u0000\u0000\u0000\u00ac\u0011\u0001\u0000\u0000\u0000\u00ad\u00ab"+
		"\u0001\u0000\u0000\u0000\u00ae\u00af\u0005=\u0000\u0000\u00af\u00b1\u0005"+
		"%\u0000\u0000\u00b0\u00b2\u0005\n\u0000\u0000\u00b1\u00b0\u0001\u0000"+
		"\u0000\u0000\u00b1\u00b2\u0001\u0000\u0000\u0000\u00b2\u0013\u0001\u0000"+
		"\u0000\u0000\u00b3\u00b5\u0005+\u0000\u0000\u00b4\u00b6\u0005\n\u0000"+
		"\u0000\u00b5\u00b4\u0001\u0000\u0000\u0000\u00b5\u00b6\u0001\u0000\u0000"+
		"\u0000\u00b6\u00ba\u0001\u0000\u0000\u0000\u00b7\u00b9\u0003\n\u0005\u0000"+
		"\u00b8\u00b7\u0001\u0000\u0000\u0000\u00b9\u00bc\u0001\u0000\u0000\u0000"+
		"\u00ba\u00b8\u0001\u0000\u0000\u0000\u00ba\u00bb\u0001\u0000\u0000\u0000"+
		"\u00bb\u00bd\u0001\u0000\u0000\u0000\u00bc\u00ba\u0001\u0000\u0000\u0000"+
		"\u00bd\u00c1\u0003.\u0017\u0000\u00be\u00c0\u0003\n\u0005\u0000\u00bf"+
		"\u00be\u0001\u0000\u0000\u0000\u00c0\u00c3\u0001\u0000\u0000\u0000\u00c1"+
		"\u00bf\u0001\u0000\u0000\u0000\u00c1\u00c2\u0001\u0000\u0000\u0000\u00c2"+
		"\u00c4\u0001\u0000\u0000\u0000\u00c3\u00c1\u0001\u0000\u0000\u0000\u00c4"+
		"\u00c5\u0005(\u0000\u0000\u00c5\u00c6\u0005+\u0000\u0000\u00c6\u00c8\u0005"+
		"!\u0000\u0000\u00c7\u00c9\u0005\n\u0000\u0000\u00c8\u00c7\u0001\u0000"+
		"\u0000\u0000\u00c8\u00c9\u0001\u0000\u0000\u0000\u00c9\u0015\u0001\u0000"+
		"\u0000\u0000\u00ca\u00cb\u00059\u0000\u0000\u00cb\u00cc\u0003F#\u0000"+
		"\u00cc\u00d2\u0005?\u0000\u0000\u00cd\u00ce\u0003D\"\u0000\u00ce\u00cf"+
		"\u0005:\u0000\u0000\u00cf\u00d0\u0003D\"\u0000\u00d0\u00d3\u0001\u0000"+
		"\u0000\u0000\u00d1\u00d3\u0003H$\u0000\u00d2\u00cd\u0001\u0000\u0000\u0000"+
		"\u00d2\u00d1\u0001\u0000\u0000\u0000\u00d3\u00d4\u0001\u0000\u0000\u0000"+
		"\u00d4\u00d6\u0005+\u0000\u0000\u00d5\u00d7\u0005\n\u0000\u0000\u00d6"+
		"\u00d5\u0001\u0000\u0000\u0000\u00d6\u00d7\u0001\u0000\u0000\u0000\u00d7"+
		"\u00db\u0001\u0000\u0000\u0000\u00d8\u00da\u0003\n\u0005\u0000\u00d9\u00d8"+
		"\u0001\u0000\u0000\u0000\u00da\u00dd\u0001\u0000\u0000\u0000\u00db\u00d9"+
		"\u0001\u0000\u0000\u0000\u00db\u00dc\u0001\u0000\u0000\u0000\u00dc\u00de"+
		"\u0001\u0000\u0000\u0000\u00dd\u00db\u0001\u0000\u0000\u0000\u00de\u00df"+
		"\u0005(\u0000\u0000\u00df\u00e0\u0005+\u0000\u0000\u00e0\u00e2\u0005!"+
		"\u0000\u0000\u00e1\u00e3\u0005\n\u0000\u0000\u00e2\u00e1\u0001\u0000\u0000"+
		"\u0000\u00e2\u00e3\u0001\u0000\u0000\u0000\u00e3\u0017\u0001\u0000\u0000"+
		"\u0000\u00e4\u00e5\u0003\u001a\r\u0000\u00e5\u00e8\u0003<\u001e\u0000"+
		"\u00e6\u00e7\u0005\u0001\u0000\u0000\u00e7\u00e9\u0003\u001c\u000e\u0000"+
		"\u00e8\u00e6\u0001\u0000\u0000\u0000\u00e8\u00e9\u0001\u0000\u0000\u0000"+
		"\u00e9\u00ea\u0001\u0000\u0000\u0000\u00ea\u00ec\u0005!\u0000\u0000\u00eb"+
		"\u00ed\u0005\n\u0000\u0000\u00ec\u00eb\u0001\u0000\u0000\u0000\u00ec\u00ed"+
		"\u0001\u0000\u0000\u0000\u00ed\u00f6\u0001\u0000\u0000\u0000\u00ee\u00ef"+
		"\u0005)\u0000\u0000\u00ef\u00f0\u0003\u001a\r\u0000\u00f0\u00f1\u0005"+
		"*\u0000\u0000\u00f1\u00f3\u0003 \u0010\u0000\u00f2\u00f4\u0005\n\u0000"+
		"\u0000\u00f3\u00f2\u0001\u0000\u0000\u0000\u00f3\u00f4\u0001\u0000\u0000"+
		"\u0000\u00f4\u00f6\u0001\u0000\u0000\u0000\u00f5\u00e4\u0001\u0000\u0000"+
		"\u0000\u00f5\u00ee\u0001\u0000\u0000\u0000\u00f6\u0019\u0001\u0000\u0000"+
		"\u0000\u00f7\u00f8\u0005B\u0000\u0000\u00f8\u001b\u0001\u0000\u0000\u0000"+
		"\u00f9\u00fa\u0005B\u0000\u0000\u00fa\u001d\u0001\u0000\u0000\u0000\u00fb"+
		"\u00fc\u0005.\u0000\u0000\u00fc\u00fd\u0005B\u0000\u0000\u00fd\u00fe\u0005"+
		"\"\u0000\u0000\u00fe\u00ff\u0003D\"\u0000\u00ff\u0101\u0005!\u0000\u0000"+
		"\u0100\u0102\u0005\n\u0000\u0000\u0101\u0100\u0001\u0000\u0000\u0000\u0101"+
		"\u0102\u0001\u0000\u0000\u0000\u0102\u001f\u0001\u0000\u0000\u0000\u0103"+
		"\u0106\u0005>\u0000\u0000\u0104\u0106\u0003$\u0012\u0000\u0105\u0103\u0001"+
		"\u0000\u0000\u0000\u0105\u0104\u0001\u0000\u0000\u0000\u0106\u0107\u0001"+
		"\u0000\u0000\u0000\u0107\u0109\u0005!\u0000\u0000\u0108\u010a\u0005\n"+
		"\u0000\u0000\u0109\u0108\u0001\u0000\u0000\u0000\u0109\u010a\u0001\u0000"+
		"\u0000\u0000\u010a!\u0001\u0000\u0000\u0000\u010b\u010c\u0003$\u0012\u0000"+
		"\u010c\u010d\u00030\u0018\u0000\u010d\u010e\u0003$\u0012\u0000\u010e\u0110"+
		"\u0005!\u0000\u0000\u010f\u0111\u0005\n\u0000\u0000\u0110\u010f\u0001"+
		"\u0000\u0000\u0000\u0110\u0111\u0001\u0000\u0000\u0000\u0111#\u0001\u0000"+
		"\u0000\u0000\u0112\u0114\u0003&\u0013\u0000\u0113\u0112\u0001\u0000\u0000"+
		"\u0000\u0114\u0117\u0001\u0000\u0000\u0000\u0115\u0116\u0001\u0000\u0000"+
		"\u0000\u0115\u0113\u0001\u0000\u0000\u0000\u0116%\u0001\u0000\u0000\u0000"+
		"\u0117\u0115\u0001\u0000\u0000\u0000\u0118\u011b\u0003F#\u0000\u0119\u011b"+
		"\b\u0000\u0000\u0000\u011a\u0118\u0001\u0000\u0000\u0000\u011a\u0119\u0001"+
		"\u0000\u0000\u0000\u011b\'\u0001\u0000\u0000\u0000\u011c\u011d\u0005-"+
		"\u0000\u0000\u011d\u011e\u0005B\u0000\u0000\u011e\u0120\u0005!\u0000\u0000"+
		"\u011f\u0121\u0005\n\u0000\u0000\u0120\u011f\u0001\u0000\u0000\u0000\u0120"+
		"\u0121\u0001\u0000\u0000\u0000\u0121)\u0001\u0000\u0000\u0000\u0122\u0123"+
		"\u0005/\u0000\u0000\u0123\u0124\u0005B\u0000\u0000\u0124\u0126\u0005!"+
		"\u0000\u0000\u0125\u0127\u0005\n\u0000\u0000\u0126\u0125\u0001\u0000\u0000"+
		"\u0000\u0126\u0127\u0001\u0000\u0000\u0000\u0127+\u0001\u0000\u0000\u0000"+
		"\u0128\u0129\u0005,\u0000\u0000\u0129\u012a\u0005B\u0000\u0000\u012a\u012b"+
		"\u00030\u0018\u0000\u012b\u012d\u0005!\u0000\u0000\u012c\u012e\u0005\n"+
		"\u0000\u0000\u012d\u012c\u0001\u0000\u0000\u0000\u012d\u012e\u0001\u0000"+
		"\u0000\u0000\u012e-\u0001\u0000\u0000\u0000\u012f\u0130\u00051\u0000\u0000"+
		"\u0130\u0131\u00052\u0000\u0000\u0131\u0132\u0005B\u0000\u0000\u0132\u0133"+
		"\u0005\u001a\u0000\u0000\u0133\u0134\u00054\u0000\u0000\u0134\u0136\u0005"+
		"!\u0000\u0000\u0135\u0137\u0005\n\u0000\u0000\u0136\u0135\u0001\u0000"+
		"\u0000\u0000\u0136\u0137\u0001\u0000\u0000\u0000\u0137/\u0001\u0000\u0000"+
		"\u0000\u0138\u0139\u00053\u0000\u0000\u0139\u013e\u0003F#\u0000\u013a"+
		"\u013b\u0005#\u0000\u0000\u013b\u013d\u0003F#\u0000\u013c\u013a\u0001"+
		"\u0000\u0000\u0000\u013d\u0140\u0001\u0000\u0000\u0000\u013e\u013c\u0001"+
		"\u0000\u0000\u0000\u013e\u013f\u0001\u0000\u0000\u0000\u013f1\u0001\u0000"+
		"\u0000\u0000\u0140\u013e\u0001\u0000\u0000\u0000\u0141\u0142\u00055\u0000"+
		"\u0000\u0142\u0143\u0003D\"\u0000\u0143\u0145\u00058\u0000\u0000\u0144"+
		"\u0146\u0005\n\u0000\u0000\u0145\u0144\u0001\u0000\u0000\u0000\u0145\u0146"+
		"\u0001\u0000\u0000\u0000\u01463\u0001\u0000\u0000\u0000\u0147\u0148\u0005"+
		"6\u0000\u0000\u0148\u0149\u0003D\"\u0000\u0149\u014b\u00058\u0000\u0000"+
		"\u014a\u014c\u0005\n\u0000\u0000\u014b\u014a\u0001\u0000\u0000\u0000\u014b"+
		"\u014c\u0001\u0000\u0000\u0000\u014c5\u0001\u0000\u0000\u0000\u014d\u014f"+
		"\u0005;\u0000\u0000\u014e\u0150\u0005\n\u0000\u0000\u014f\u014e\u0001"+
		"\u0000\u0000\u0000\u014f\u0150\u0001\u0000\u0000\u0000\u01507\u0001\u0000"+
		"\u0000\u0000\u0151\u0152\u00050\u0000\u0000\u0152\u0154\u0005!\u0000\u0000"+
		"\u0153\u0155\u0005\n\u0000\u0000\u0154\u0153\u0001\u0000\u0000\u0000\u0154"+
		"\u0155\u0001\u0000\u0000\u0000\u01559\u0001\u0000\u0000\u0000\u0156\u0157"+
		"\u00057\u0000\u0000\u0157\u0159\u0005!\u0000\u0000\u0158\u015a\u0005\n"+
		"\u0000\u0000\u0159\u0158\u0001\u0000\u0000\u0000\u0159\u015a\u0001\u0000"+
		"\u0000\u0000\u015a;\u0001\u0000\u0000\u0000\u015b\u015c\u0007\u0001\u0000"+
		"\u0000\u015c=\u0001\u0000\u0000\u0000\u015d\u015e\u00051\u0000\u0000\u015e"+
		"\u0160\u0005!\u0000\u0000\u015f\u0161\u0005\n\u0000\u0000\u0160\u015f"+
		"\u0001\u0000\u0000\u0000\u0160\u0161\u0001\u0000\u0000\u0000\u0161?\u0001"+
		"\u0000\u0000\u0000\u0162\u0163\u0005B\u0000\u0000\u0163\u0165\u0005\u001f"+
		"\u0000\u0000\u0164\u0166\u0003B!\u0000\u0165\u0164\u0001\u0000\u0000\u0000"+
		"\u0165\u0166\u0001\u0000\u0000\u0000\u0166\u0167\u0001\u0000\u0000\u0000"+
		"\u0167\u0168\u0005 \u0000\u0000\u0168A\u0001\u0000\u0000\u0000\u0169\u016e"+
		"\u0003D\"\u0000\u016a\u016b\u0005#\u0000\u0000\u016b\u016d\u0003D\"\u0000"+
		"\u016c\u016a\u0001\u0000\u0000\u0000\u016d\u0170\u0001\u0000\u0000\u0000"+
		"\u016e\u016c\u0001\u0000\u0000\u0000\u016e\u016f\u0001\u0000\u0000\u0000"+
		"\u016fC\u0001\u0000\u0000\u0000\u0170\u016e\u0001\u0000\u0000\u0000\u0171"+
		"\u0172\u0006\"\uffff\uffff\u0000\u0172\u0173\u0005\u0017\u0000\u0000\u0173"+
		"\u0183\u0003D\"\u0014\u0174\u0175\u0005\u0013\u0000\u0000\u0175\u0183"+
		"\u0003D\"\u0013\u0176\u0183\u0005A\u0000\u0000\u0177\u0183\u0005@\u0000"+
		"\u0000\u0178\u0183\u0005\u000b\u0000\u0000\u0179\u0183\u0003@ \u0000\u017a"+
		"\u0183\u0003H$\u0000\u017b\u0183\u0005B\u0000\u0000\u017c\u0183\u0003"+
		"F#\u0000\u017d\u0183\u0005C\u0000\u0000\u017e\u017f\u0005\u001f\u0000"+
		"\u0000\u017f\u0180\u0003D\"\u0000\u0180\u0181\u0005 \u0000\u0000\u0181"+
		"\u0183\u0001\u0000\u0000\u0000\u0182\u0171\u0001\u0000\u0000\u0000\u0182"+
		"\u0174\u0001\u0000\u0000\u0000\u0182\u0176\u0001\u0000\u0000\u0000\u0182"+
		"\u0177\u0001\u0000\u0000\u0000\u0182\u0178\u0001\u0000\u0000\u0000\u0182"+
		"\u0179\u0001\u0000\u0000\u0000\u0182\u017a\u0001\u0000\u0000\u0000\u0182"+
		"\u017b\u0001\u0000\u0000\u0000\u0182\u017c\u0001\u0000\u0000\u0000\u0182"+
		"\u017d\u0001\u0000\u0000\u0000\u0182\u017e\u0001\u0000\u0000\u0000\u0183"+
		"\u01a4\u0001\u0000\u0000\u0000\u0184\u0185\n\u0012\u0000\u0000\u0185\u0186"+
		"\u0005\u0012\u0000\u0000\u0186\u01a3\u0003D\"\u0012\u0187\u0188\n\u0011"+
		"\u0000\u0000\u0188\u0189\u0007\u0002\u0000\u0000\u0189\u01a3\u0003D\""+
		"\u0012\u018a\u018b\n\u0010\u0000\u0000\u018b\u018c\u0007\u0003\u0000\u0000"+
		"\u018c\u01a3\u0003D\"\u0011\u018d\u018e\n\u000f\u0000\u0000\u018e\u018f"+
		"\u0007\u0004\u0000\u0000\u018f\u01a3\u0003D\"\u0010\u0190\u0191\n\u000e"+
		"\u0000\u0000\u0191\u0192\u0007\u0005\u0000\u0000\u0192\u01a3\u0003D\""+
		"\u000f\u0193\u0194\n\r\u0000\u0000\u0194\u0195\u0005\r\u0000\u0000\u0195"+
		"\u01a3\u0003D\"\u000e\u0196\u0197\n\f\u0000\u0000\u0197\u0198\u0005\f"+
		"\u0000\u0000\u0198\u01a3\u0003D\"\r\u0199\u019a\n\u000b\u0000\u0000\u019a"+
		"\u019b\u0005$\u0000\u0000\u019b\u019c\u0003D\"\u0000\u019c\u019d\u0005"+
		"%\u0000\u0000\u019d\u019e\u0003D\"\f\u019e\u01a3\u0001\u0000\u0000\u0000"+
		"\u019f\u01a0\n\n\u0000\u0000\u01a0\u01a1\u0005?\u0000\u0000\u01a1\u01a3"+
		"\u0003D\"\u000b\u01a2\u0184\u0001\u0000\u0000\u0000\u01a2\u0187\u0001"+
		"\u0000\u0000\u0000\u01a2\u018a\u0001\u0000\u0000\u0000\u01a2\u018d\u0001"+
		"\u0000\u0000\u0000\u01a2\u0190\u0001\u0000\u0000\u0000\u01a2\u0193\u0001"+
		"\u0000\u0000\u0000\u01a2\u0196\u0001\u0000\u0000\u0000\u01a2\u0199\u0001"+
		"\u0000\u0000\u0000\u01a2\u019f\u0001\u0000\u0000\u0000\u01a3\u01a6\u0001"+
		"\u0000\u0000\u0000\u01a4\u01a2\u0001\u0000\u0000\u0000\u01a4\u01a5\u0001"+
		"\u0000\u0000\u0000\u01a5E\u0001\u0000\u0000\u0000\u01a6\u01a4\u0001\u0000"+
		"\u0000\u0000\u01a7\u01a8\u0005%\u0000\u0000\u01a8\u01a9\u0005B\u0000\u0000"+
		"\u01a9G\u0001\u0000\u0000\u0000\u01aa\u01ac\u0005\u001d\u0000\u0000\u01ab"+
		"\u01ad\u0003B!\u0000\u01ac\u01ab\u0001\u0000\u0000\u0000\u01ac\u01ad\u0001"+
		"\u0000\u0000\u0000\u01ad\u01ae\u0001\u0000\u0000\u0000\u01ae\u01af\u0005"+
		"\u001e\u0000\u0000\u01afI\u0001\u0000\u0000\u00007KNQY]aglrt{}\u008a\u0090"+
		"\u0096\u009a\u009e\u00a4\u00ab\u00b1\u00b5\u00ba\u00c1\u00c8\u00d2\u00d6"+
		"\u00db\u00e2\u00e8\u00ec\u00f3\u00f5\u0101\u0105\u0109\u0110\u0115\u011a"+
		"\u0120\u0126\u012d\u0136\u013e\u0145\u014b\u014f\u0154\u0159\u0160\u0165"+
		"\u016e\u0182\u01a2\u01a4\u01ac";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}