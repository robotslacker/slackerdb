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
		IDENTIFIER=10, STRING_LITERAL=11, NUMBER=12, BOOLEAN=13, WS=14, Comment=15;
	public static final int
		RULE_copyStatment = 0, RULE_tableName = 1, RULE_query = 2, RULE_filePath = 3, 
		RULE_option = 4, RULE_options = 5, RULE_literal = 6;
	private static String[] makeRuleNames() {
		return new String[] {
			"copyStatment", "tableName", "query", "filePath", "option", "options", 
			"literal"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'('", "')'", "'='", "','", "'COPY'", "'TO'", "'FROM'", "'WITH'", 
			"';'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, null, null, null, null, "COPY", "TO", "FROM", "WITH", "SColon", 
			"IDENTIFIER", "STRING_LITERAL", "NUMBER", "BOOLEAN", "WS", "Comment"
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
		public TerminalNode TO() { return getToken(CopyStatementParser.TO, 0); }
		public TerminalNode FROM() { return getToken(CopyStatementParser.FROM, 0); }
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
			setState(14);
			match(COPY);
			setState(17);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case IDENTIFIER:
			case STRING_LITERAL:
				{
				setState(15);
				tableName();
				}
				break;
			case T__0:
				{
				setState(16);
				query();
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
			setState(20);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==TO || _la==FROM) {
				{
				setState(19);
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

			setState(22);
			filePath();
			setState(27);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==T__0 || _la==WITH) {
				{
				setState(24);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==WITH) {
					{
					setState(23);
					match(WITH);
					}
				}

				setState(26);
				options();
				}
			}

			setState(30);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==SColon) {
				{
				setState(29);
				match(SColon);
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
			setState(32);
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
			setState(34);
			match(T__0);
			setState(38);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,5,_ctx);
			while ( _alt!=1 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1+1 ) {
					{
					{
					setState(35);
					matchWildcard();
					}
					} 
				}
				setState(40);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,5,_ctx);
			}
			setState(41);
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
		enterRule(_localctx, 6, RULE_filePath);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(43);
			match(STRING_LITERAL);
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
		enterRule(_localctx, 8, RULE_option);
		try {
			setState(50);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,6,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(45);
				((OptionContext)_localctx).key = match(IDENTIFIER);
				setState(46);
				match(T__2);
				setState(47);
				((OptionContext)_localctx).value = literal();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(48);
				((OptionContext)_localctx).key = match(IDENTIFIER);
				setState(49);
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
		enterRule(_localctx, 10, RULE_options);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(52);
			match(T__0);
			setState(53);
			option();
			setState(58);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__3) {
				{
				{
				setState(54);
				match(T__3);
				setState(55);
				option();
				}
				}
				setState(60);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(61);
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
		enterRule(_localctx, 12, RULE_literal);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(63);
			_la = _input.LA(1);
			if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 15360L) != 0)) ) {
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
		"\u0004\u0001\u000fB\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001\u0002"+
		"\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004\u0007\u0004\u0002"+
		"\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0001\u0000\u0001\u0000\u0001"+
		"\u0000\u0003\u0000\u0012\b\u0000\u0001\u0000\u0003\u0000\u0015\b\u0000"+
		"\u0001\u0000\u0001\u0000\u0003\u0000\u0019\b\u0000\u0001\u0000\u0003\u0000"+
		"\u001c\b\u0000\u0001\u0000\u0003\u0000\u001f\b\u0000\u0001\u0001\u0001"+
		"\u0001\u0001\u0002\u0001\u0002\u0005\u0002%\b\u0002\n\u0002\f\u0002(\t"+
		"\u0002\u0001\u0002\u0001\u0002\u0001\u0003\u0001\u0003\u0001\u0004\u0001"+
		"\u0004\u0001\u0004\u0001\u0004\u0001\u0004\u0003\u00043\b\u0004\u0001"+
		"\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0005\u00059\b\u0005\n\u0005"+
		"\f\u0005<\t\u0005\u0001\u0005\u0001\u0005\u0001\u0006\u0001\u0006\u0001"+
		"\u0006\u0001&\u0000\u0007\u0000\u0002\u0004\u0006\b\n\f\u0000\u0003\u0001"+
		"\u0000\u0006\u0007\u0001\u0000\n\u000b\u0001\u0000\n\rB\u0000\u000e\u0001"+
		"\u0000\u0000\u0000\u0002 \u0001\u0000\u0000\u0000\u0004\"\u0001\u0000"+
		"\u0000\u0000\u0006+\u0001\u0000\u0000\u0000\b2\u0001\u0000\u0000\u0000"+
		"\n4\u0001\u0000\u0000\u0000\f?\u0001\u0000\u0000\u0000\u000e\u0011\u0005"+
		"\u0005\u0000\u0000\u000f\u0012\u0003\u0002\u0001\u0000\u0010\u0012\u0003"+
		"\u0004\u0002\u0000\u0011\u000f\u0001\u0000\u0000\u0000\u0011\u0010\u0001"+
		"\u0000\u0000\u0000\u0012\u0014\u0001\u0000\u0000\u0000\u0013\u0015\u0007"+
		"\u0000\u0000\u0000\u0014\u0013\u0001\u0000\u0000\u0000\u0014\u0015\u0001"+
		"\u0000\u0000\u0000\u0015\u0016\u0001\u0000\u0000\u0000\u0016\u001b\u0003"+
		"\u0006\u0003\u0000\u0017\u0019\u0005\b\u0000\u0000\u0018\u0017\u0001\u0000"+
		"\u0000\u0000\u0018\u0019\u0001\u0000\u0000\u0000\u0019\u001a\u0001\u0000"+
		"\u0000\u0000\u001a\u001c\u0003\n\u0005\u0000\u001b\u0018\u0001\u0000\u0000"+
		"\u0000\u001b\u001c\u0001\u0000\u0000\u0000\u001c\u001e\u0001\u0000\u0000"+
		"\u0000\u001d\u001f\u0005\t\u0000\u0000\u001e\u001d\u0001\u0000\u0000\u0000"+
		"\u001e\u001f\u0001\u0000\u0000\u0000\u001f\u0001\u0001\u0000\u0000\u0000"+
		" !\u0007\u0001\u0000\u0000!\u0003\u0001\u0000\u0000\u0000\"&\u0005\u0001"+
		"\u0000\u0000#%\t\u0000\u0000\u0000$#\u0001\u0000\u0000\u0000%(\u0001\u0000"+
		"\u0000\u0000&\'\u0001\u0000\u0000\u0000&$\u0001\u0000\u0000\u0000\')\u0001"+
		"\u0000\u0000\u0000(&\u0001\u0000\u0000\u0000)*\u0005\u0002\u0000\u0000"+
		"*\u0005\u0001\u0000\u0000\u0000+,\u0005\u000b\u0000\u0000,\u0007\u0001"+
		"\u0000\u0000\u0000-.\u0005\n\u0000\u0000./\u0005\u0003\u0000\u0000/3\u0003"+
		"\f\u0006\u000001\u0005\n\u0000\u000013\u0003\f\u0006\u00002-\u0001\u0000"+
		"\u0000\u000020\u0001\u0000\u0000\u00003\t\u0001\u0000\u0000\u000045\u0005"+
		"\u0001\u0000\u00005:\u0003\b\u0004\u000067\u0005\u0004\u0000\u000079\u0003"+
		"\b\u0004\u000086\u0001\u0000\u0000\u00009<\u0001\u0000\u0000\u0000:8\u0001"+
		"\u0000\u0000\u0000:;\u0001\u0000\u0000\u0000;=\u0001\u0000\u0000\u0000"+
		"<:\u0001\u0000\u0000\u0000=>\u0005\u0002\u0000\u0000>\u000b\u0001\u0000"+
		"\u0000\u0000?@\u0007\u0002\u0000\u0000@\r\u0001\u0000\u0000\u0000\b\u0011"+
		"\u0014\u0018\u001b\u001e&2:";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}