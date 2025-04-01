// Generated from CopyStatement.g4 by ANTLR 4.13.2
package org.slackerdb.dbserver.sql.antlr;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class CopyStatementParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		T__0=1, T__1=2, T__2=3, T__3=4, COPY=5, TO=6, FROM=7, WITH=8, SColon=9, 
		STDIN=10, STDOUT=11, BOOLEAN=12, IDENTIFIER=13, STRING_LITERAL=14, NUMBER=15, 
		NULL_CHAR=16, WS=17, Comment=18;
	public static final int
		RULE_copyStatment = 0, RULE_tableName = 1, RULE_query = 2, RULE_column = 3, 
		RULE_columns = 4, RULE_filePath = 5, RULE_option = 6, RULE_options = 7, 
		RULE_literal = 8;
	private static String[] makeRuleNames() {
		return new String[] {
			"copyStatment", "tableName", "query", "column", "columns", "filePath", 
			"option", "options", "literal"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'('", "')'", "','", "'='", "'COPY'", "'TO'", "'FROM'", "'WITH'", 
			"';'", "'STDIN'", "'STDOUT'", null, null, null, null, "'\\u0000'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, null, null, null, null, "COPY", "TO", "FROM", "WITH", "SColon", 
			"STDIN", "STDOUT", "BOOLEAN", "IDENTIFIER", "STRING_LITERAL", "NUMBER", 
			"NULL_CHAR", "WS", "Comment"
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
	public String getGrammarFileName() { return "CopyStatement.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public ATN getATN() { return _ATN; }

	public CopyStatementParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@SuppressWarnings("CheckReturnValue")
	public static class CopyStatmentContext extends ParserRuleContext {
		public TerminalNode COPY() { return getToken(CopyStatementParser.COPY, 0); }
		public FilePathContext filePath() {
			return getRuleContext(FilePathContext.class,0);
		}
		public TableNameContext tableName() {
			return getRuleContext(TableNameContext.class,0);
		}
		public QueryContext query() {
			return getRuleContext(QueryContext.class,0);
		}
		public OptionsContext options() {
			return getRuleContext(OptionsContext.class,0);
		}
		public TerminalNode SColon() { return getToken(CopyStatementParser.SColon, 0); }
		public TerminalNode NULL_CHAR() { return getToken(CopyStatementParser.NULL_CHAR, 0); }
		public TerminalNode TO() { return getToken(CopyStatementParser.TO, 0); }
		public TerminalNode FROM() { return getToken(CopyStatementParser.FROM, 0); }
		public ColumnsContext columns() {
			return getRuleContext(ColumnsContext.class,0);
		}
		public TerminalNode WITH() { return getToken(CopyStatementParser.WITH, 0); }
		public CopyStatmentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_copyStatment; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof CopyStatementVisitor ) return ((CopyStatementVisitor<? extends T>)visitor).visitCopyStatment(this);
			else return visitor.visitChildren(this);
		}
	}

	public final CopyStatmentContext copyStatment() throws RecognitionException {
		CopyStatmentContext _localctx = new CopyStatmentContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_copyStatment);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(18);
			match(COPY);
			setState(24);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case IDENTIFIER:
			case STRING_LITERAL:
				{
				setState(19);
				tableName();
				setState(21);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==T__0) {
					{
					setState(20);
					columns();
					}
				}

				}
				break;
			case T__0:
				{
				setState(23);
				query();
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
			setState(27);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==TO || _la==FROM) {
				{
				setState(26);
				_la = _input.LA(1);
				if ( !(_la==TO || _la==FROM) ) {
				_errHandler.recoverInline(this);
				}
				else {
					if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
					_errHandler.reportMatch(this);
					consume();
				}
				}
			}

			setState(29);
			filePath();
			setState(34);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==T__0 || _la==WITH) {
				{
				setState(31);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==WITH) {
					{
					setState(30);
					match(WITH);
					}
				}

				setState(33);
				options();
				}
			}

			setState(37);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==SColon) {
				{
				setState(36);
				match(SColon);
				}
			}

			setState(40);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==NULL_CHAR) {
				{
				setState(39);
				match(NULL_CHAR);
				}
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
	public static class TableNameContext extends ParserRuleContext {
		public TerminalNode IDENTIFIER() { return getToken(CopyStatementParser.IDENTIFIER, 0); }
		public TerminalNode STRING_LITERAL() { return getToken(CopyStatementParser.STRING_LITERAL, 0); }
		public TableNameContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_tableName; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof CopyStatementVisitor ) return ((CopyStatementVisitor<? extends T>)visitor).visitTableName(this);
			else return visitor.visitChildren(this);
		}
	}

	public final TableNameContext tableName() throws RecognitionException {
		TableNameContext _localctx = new TableNameContext(_ctx, getState());
		enterRule(_localctx, 2, RULE_tableName);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(42);
			_la = _input.LA(1);
			if ( !(_la==IDENTIFIER || _la==STRING_LITERAL) ) {
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
	public static class QueryContext extends ParserRuleContext {
		public QueryContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_query; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof CopyStatementVisitor ) return ((CopyStatementVisitor<? extends T>)visitor).visitQuery(this);
			else return visitor.visitChildren(this);
		}
	}

	public final QueryContext query() throws RecognitionException {
		QueryContext _localctx = new QueryContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_query);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(44);
			match(T__0);
			setState(48);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,7,_ctx);
			while ( _alt!=1 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1+1 ) {
					{
					{
					setState(45);
					matchWildcard();
					}
					} 
				}
				setState(50);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,7,_ctx);
			}
			setState(51);
			match(T__1);
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
	public static class ColumnContext extends ParserRuleContext {
		public LiteralContext literal() {
			return getRuleContext(LiteralContext.class,0);
		}
		public ColumnContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_column; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof CopyStatementVisitor ) return ((CopyStatementVisitor<? extends T>)visitor).visitColumn(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ColumnContext column() throws RecognitionException {
		ColumnContext _localctx = new ColumnContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_column);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(53);
			literal();
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
	public static class ColumnsContext extends ParserRuleContext {
		public List<ColumnContext> column() {
			return getRuleContexts(ColumnContext.class);
		}
		public ColumnContext column(int i) {
			return getRuleContext(ColumnContext.class,i);
		}
		public ColumnsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_columns; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof CopyStatementVisitor ) return ((CopyStatementVisitor<? extends T>)visitor).visitColumns(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ColumnsContext columns() throws RecognitionException {
		ColumnsContext _localctx = new ColumnsContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_columns);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(55);
			match(T__0);
			setState(56);
			column();
			setState(61);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__2) {
				{
				{
				setState(57);
				match(T__2);
				setState(58);
				column();
				}
				}
				setState(63);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(64);
			match(T__1);
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
	public static class FilePathContext extends ParserRuleContext {
		public TerminalNode STRING_LITERAL() { return getToken(CopyStatementParser.STRING_LITERAL, 0); }
		public TerminalNode STDIN() { return getToken(CopyStatementParser.STDIN, 0); }
		public TerminalNode STDOUT() { return getToken(CopyStatementParser.STDOUT, 0); }
		public FilePathContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_filePath; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof CopyStatementVisitor ) return ((CopyStatementVisitor<? extends T>)visitor).visitFilePath(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FilePathContext filePath() throws RecognitionException {
		FilePathContext _localctx = new FilePathContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_filePath);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(66);
			_la = _input.LA(1);
			if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 19456L) != 0)) ) {
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
	public static class OptionContext extends ParserRuleContext {
		public Token key;
		public LiteralContext value;
		public TerminalNode IDENTIFIER() { return getToken(CopyStatementParser.IDENTIFIER, 0); }
		public LiteralContext literal() {
			return getRuleContext(LiteralContext.class,0);
		}
		public OptionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_option; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof CopyStatementVisitor ) return ((CopyStatementVisitor<? extends T>)visitor).visitOption(this);
			else return visitor.visitChildren(this);
		}
	}

	public final OptionContext option() throws RecognitionException {
		OptionContext _localctx = new OptionContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_option);
		try {
			setState(73);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,9,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(68);
				((OptionContext)_localctx).key = match(IDENTIFIER);
				setState(69);
				match(T__3);
				setState(70);
				((OptionContext)_localctx).value = literal();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(71);
				((OptionContext)_localctx).key = match(IDENTIFIER);
				setState(72);
				((OptionContext)_localctx).value = literal();
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
	public static class OptionsContext extends ParserRuleContext {
		public List<OptionContext> option() {
			return getRuleContexts(OptionContext.class);
		}
		public OptionContext option(int i) {
			return getRuleContext(OptionContext.class,i);
		}
		public OptionsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_options; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof CopyStatementVisitor ) return ((CopyStatementVisitor<? extends T>)visitor).visitOptions(this);
			else return visitor.visitChildren(this);
		}
	}

	public final OptionsContext options() throws RecognitionException {
		OptionsContext _localctx = new OptionsContext(_ctx, getState());
		enterRule(_localctx, 14, RULE_options);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(75);
			match(T__0);
			setState(76);
			option();
			setState(81);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__2) {
				{
				{
				setState(77);
				match(T__2);
				setState(78);
				option();
				}
				}
				setState(83);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(84);
			match(T__1);
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
	public static class LiteralContext extends ParserRuleContext {
		public TerminalNode STRING_LITERAL() { return getToken(CopyStatementParser.STRING_LITERAL, 0); }
		public TerminalNode NUMBER() { return getToken(CopyStatementParser.NUMBER, 0); }
		public TerminalNode BOOLEAN() { return getToken(CopyStatementParser.BOOLEAN, 0); }
		public TerminalNode IDENTIFIER() { return getToken(CopyStatementParser.IDENTIFIER, 0); }
		public LiteralContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_literal; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof CopyStatementVisitor ) return ((CopyStatementVisitor<? extends T>)visitor).visitLiteral(this);
			else return visitor.visitChildren(this);
		}
	}

	public final LiteralContext literal() throws RecognitionException {
		LiteralContext _localctx = new LiteralContext(_ctx, getState());
		enterRule(_localctx, 16, RULE_literal);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(86);
			_la = _input.LA(1);
			if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 61440L) != 0)) ) {
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

	public static final String _serializedATN =
		"\u0004\u0001\u0012Y\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001\u0002"+
		"\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004\u0007\u0004\u0002"+
		"\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002\u0007\u0007\u0007\u0002"+
		"\b\u0007\b\u0001\u0000\u0001\u0000\u0001\u0000\u0003\u0000\u0016\b\u0000"+
		"\u0001\u0000\u0003\u0000\u0019\b\u0000\u0001\u0000\u0003\u0000\u001c\b"+
		"\u0000\u0001\u0000\u0001\u0000\u0003\u0000 \b\u0000\u0001\u0000\u0003"+
		"\u0000#\b\u0000\u0001\u0000\u0003\u0000&\b\u0000\u0001\u0000\u0003\u0000"+
		")\b\u0000\u0001\u0001\u0001\u0001\u0001\u0002\u0001\u0002\u0005\u0002"+
		"/\b\u0002\n\u0002\f\u00022\t\u0002\u0001\u0002\u0001\u0002\u0001\u0003"+
		"\u0001\u0003\u0001\u0004\u0001\u0004\u0001\u0004\u0001\u0004\u0005\u0004"+
		"<\b\u0004\n\u0004\f\u0004?\t\u0004\u0001\u0004\u0001\u0004\u0001\u0005"+
		"\u0001\u0005\u0001\u0006\u0001\u0006\u0001\u0006\u0001\u0006\u0001\u0006"+
		"\u0003\u0006J\b\u0006\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007"+
		"\u0005\u0007P\b\u0007\n\u0007\f\u0007S\t\u0007\u0001\u0007\u0001\u0007"+
		"\u0001\b\u0001\b\u0001\b\u00010\u0000\t\u0000\u0002\u0004\u0006\b\n\f"+
		"\u000e\u0010\u0000\u0004\u0001\u0000\u0006\u0007\u0001\u0000\r\u000e\u0002"+
		"\u0000\n\u000b\u000e\u000e\u0001\u0000\f\u000fZ\u0000\u0012\u0001\u0000"+
		"\u0000\u0000\u0002*\u0001\u0000\u0000\u0000\u0004,\u0001\u0000\u0000\u0000"+
		"\u00065\u0001\u0000\u0000\u0000\b7\u0001\u0000\u0000\u0000\nB\u0001\u0000"+
		"\u0000\u0000\fI\u0001\u0000\u0000\u0000\u000eK\u0001\u0000\u0000\u0000"+
		"\u0010V\u0001\u0000\u0000\u0000\u0012\u0018\u0005\u0005\u0000\u0000\u0013"+
		"\u0015\u0003\u0002\u0001\u0000\u0014\u0016\u0003\b\u0004\u0000\u0015\u0014"+
		"\u0001\u0000\u0000\u0000\u0015\u0016\u0001\u0000\u0000\u0000\u0016\u0019"+
		"\u0001\u0000\u0000\u0000\u0017\u0019\u0003\u0004\u0002\u0000\u0018\u0013"+
		"\u0001\u0000\u0000\u0000\u0018\u0017\u0001\u0000\u0000\u0000\u0019\u001b"+
		"\u0001\u0000\u0000\u0000\u001a\u001c\u0007\u0000\u0000\u0000\u001b\u001a"+
		"\u0001\u0000\u0000\u0000\u001b\u001c\u0001\u0000\u0000\u0000\u001c\u001d"+
		"\u0001\u0000\u0000\u0000\u001d\"\u0003\n\u0005\u0000\u001e \u0005\b\u0000"+
		"\u0000\u001f\u001e\u0001\u0000\u0000\u0000\u001f \u0001\u0000\u0000\u0000"+
		" !\u0001\u0000\u0000\u0000!#\u0003\u000e\u0007\u0000\"\u001f\u0001\u0000"+
		"\u0000\u0000\"#\u0001\u0000\u0000\u0000#%\u0001\u0000\u0000\u0000$&\u0005"+
		"\t\u0000\u0000%$\u0001\u0000\u0000\u0000%&\u0001\u0000\u0000\u0000&(\u0001"+
		"\u0000\u0000\u0000\')\u0005\u0010\u0000\u0000(\'\u0001\u0000\u0000\u0000"+
		"()\u0001\u0000\u0000\u0000)\u0001\u0001\u0000\u0000\u0000*+\u0007\u0001"+
		"\u0000\u0000+\u0003\u0001\u0000\u0000\u0000,0\u0005\u0001\u0000\u0000"+
		"-/\t\u0000\u0000\u0000.-\u0001\u0000\u0000\u0000/2\u0001\u0000\u0000\u0000"+
		"01\u0001\u0000\u0000\u00000.\u0001\u0000\u0000\u000013\u0001\u0000\u0000"+
		"\u000020\u0001\u0000\u0000\u000034\u0005\u0002\u0000\u00004\u0005\u0001"+
		"\u0000\u0000\u000056\u0003\u0010\b\u00006\u0007\u0001\u0000\u0000\u0000"+
		"78\u0005\u0001\u0000\u00008=\u0003\u0006\u0003\u00009:\u0005\u0003\u0000"+
		"\u0000:<\u0003\u0006\u0003\u0000;9\u0001\u0000\u0000\u0000<?\u0001\u0000"+
		"\u0000\u0000=;\u0001\u0000\u0000\u0000=>\u0001\u0000\u0000\u0000>@\u0001"+
		"\u0000\u0000\u0000?=\u0001\u0000\u0000\u0000@A\u0005\u0002\u0000\u0000"+
		"A\t\u0001\u0000\u0000\u0000BC\u0007\u0002\u0000\u0000C\u000b\u0001\u0000"+
		"\u0000\u0000DE\u0005\r\u0000\u0000EF\u0005\u0004\u0000\u0000FJ\u0003\u0010"+
		"\b\u0000GH\u0005\r\u0000\u0000HJ\u0003\u0010\b\u0000ID\u0001\u0000\u0000"+
		"\u0000IG\u0001\u0000\u0000\u0000J\r\u0001\u0000\u0000\u0000KL\u0005\u0001"+
		"\u0000\u0000LQ\u0003\f\u0006\u0000MN\u0005\u0003\u0000\u0000NP\u0003\f"+
		"\u0006\u0000OM\u0001\u0000\u0000\u0000PS\u0001\u0000\u0000\u0000QO\u0001"+
		"\u0000\u0000\u0000QR\u0001\u0000\u0000\u0000RT\u0001\u0000\u0000\u0000"+
		"SQ\u0001\u0000\u0000\u0000TU\u0005\u0002\u0000\u0000U\u000f\u0001\u0000"+
		"\u0000\u0000VW\u0007\u0003\u0000\u0000W\u0011\u0001\u0000\u0000\u0000"+
		"\u000b\u0015\u0018\u001b\u001f\"%(0=IQ";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}