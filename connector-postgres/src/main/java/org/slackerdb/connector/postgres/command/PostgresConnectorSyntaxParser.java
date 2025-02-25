// Generated from PostgresConnectorSyntax.g4 by ANTLR 4.13.2
package org.slackerdb.connector.postgres.command;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class PostgresConnectorSyntaxParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		T__0=1, T__1=2, T__2=3, WS=4, CRLF=5, SColon=6, Assign=7, Comma=8, QMark=9, 
		Colon=10, DROP=11, CREATE=12, ALTER=13, START=14, SHUTDOWN=15, CONNECTOR=16, 
		CONNECT=17, TO=18, ADD=19, REMOVE=20, TASK=21, RESYNC=22, TABLE=23, FULL=24, 
		SHOW=25, STATUS=26, TEMPORARY=27, Bool=28, Number=29, Identifier=30, String=31, 
		Comment=32;
	public static final int
		RULE_command = 0, RULE_showConnector = 1, RULE_alterConnector = 2, RULE_dropConnector = 3, 
		RULE_shutdownConnector = 4, RULE_startConnector = 5, RULE_createConnector = 6, 
		RULE_connectorName = 7, RULE_taskName = 8, RULE_connectorOptions = 9, 
		RULE_taskOptions = 10, RULE_tableName = 11, RULE_ifnotexists = 12;
	private static String[] makeRuleNames() {
		return new String[] {
			"command", "showConnector", "alterConnector", "dropConnector", "shutdownConnector", 
			"startConnector", "createConnector", "connectorName", "taskName", "connectorOptions", 
			"taskOptions", "tableName", "ifnotexists"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'IF'", "'NOT'", "'EXISTS'", null, "'\\n'", "';'", "'='", "','", 
			"'?'", "':'", "'DROP'", "'CREATE'", "'ALTER'", "'START'", "'SHUTDOWN'", 
			"'CONNECTOR'", "'CONNECT'", "'TO'", "'ADD'", "'REMOVE'", "'TASK'", "'RESYNC'", 
			"'TABLE'", "'FULL'", "'SHOW'", "'STATUS'", "'TEMPORARY'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, null, null, null, "WS", "CRLF", "SColon", "Assign", "Comma", "QMark", 
			"Colon", "DROP", "CREATE", "ALTER", "START", "SHUTDOWN", "CONNECTOR", 
			"CONNECT", "TO", "ADD", "REMOVE", "TASK", "RESYNC", "TABLE", "FULL", 
			"SHOW", "STATUS", "TEMPORARY", "Bool", "Number", "Identifier", "String", 
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
	public String getGrammarFileName() { return "PostgresConnectorSyntax.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public ATN getATN() { return _ATN; }

	public PostgresConnectorSyntaxParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@SuppressWarnings("CheckReturnValue")
	public static class CommandContext extends ParserRuleContext {
		public TerminalNode EOF() { return getToken(PostgresConnectorSyntaxParser.EOF, 0); }
		public CreateConnectorContext createConnector() {
			return getRuleContext(CreateConnectorContext.class,0);
		}
		public StartConnectorContext startConnector() {
			return getRuleContext(StartConnectorContext.class,0);
		}
		public ShutdownConnectorContext shutdownConnector() {
			return getRuleContext(ShutdownConnectorContext.class,0);
		}
		public DropConnectorContext dropConnector() {
			return getRuleContext(DropConnectorContext.class,0);
		}
		public AlterConnectorContext alterConnector() {
			return getRuleContext(AlterConnectorContext.class,0);
		}
		public ShowConnectorContext showConnector() {
			return getRuleContext(ShowConnectorContext.class,0);
		}
		public TerminalNode SColon() { return getToken(PostgresConnectorSyntaxParser.SColon, 0); }
		public CommandContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_command; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitCommand(this);
			else return visitor.visitChildren(this);
		}
	}

	public final CommandContext command() throws RecognitionException {
		CommandContext _localctx = new CommandContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_command);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(32);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case CREATE:
				{
				setState(26);
				createConnector();
				}
				break;
			case START:
				{
				setState(27);
				startConnector();
				}
				break;
			case SHUTDOWN:
				{
				setState(28);
				shutdownConnector();
				}
				break;
			case DROP:
				{
				setState(29);
				dropConnector();
				}
				break;
			case ALTER:
				{
				setState(30);
				alterConnector();
				}
				break;
			case SHOW:
				{
				setState(31);
				showConnector();
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
			setState(35);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==SColon) {
				{
				setState(34);
				match(SColon);
				}
			}

			setState(37);
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
	public static class ShowConnectorContext extends ParserRuleContext {
		public TerminalNode SHOW() { return getToken(PostgresConnectorSyntaxParser.SHOW, 0); }
		public TerminalNode CONNECTOR() { return getToken(PostgresConnectorSyntaxParser.CONNECTOR, 0); }
		public ConnectorNameContext connectorName() {
			return getRuleContext(ConnectorNameContext.class,0);
		}
		public TerminalNode STATUS() { return getToken(PostgresConnectorSyntaxParser.STATUS, 0); }
		public TerminalNode TASK() { return getToken(PostgresConnectorSyntaxParser.TASK, 0); }
		public TaskNameContext taskName() {
			return getRuleContext(TaskNameContext.class,0);
		}
		public ShowConnectorContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_showConnector; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitShowConnector(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ShowConnectorContext showConnector() throws RecognitionException {
		ShowConnectorContext _localctx = new ShowConnectorContext(_ctx, getState());
		enterRule(_localctx, 2, RULE_showConnector);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(39);
			match(SHOW);
			setState(40);
			match(CONNECTOR);
			setState(41);
			connectorName();
			setState(44);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==TASK) {
				{
				setState(42);
				match(TASK);
				setState(43);
				taskName();
				}
			}

			setState(46);
			match(STATUS);
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
	public static class AlterConnectorContext extends ParserRuleContext {
		public TerminalNode ALTER() { return getToken(PostgresConnectorSyntaxParser.ALTER, 0); }
		public TerminalNode CONNECTOR() { return getToken(PostgresConnectorSyntaxParser.CONNECTOR, 0); }
		public ConnectorNameContext connectorName() {
			return getRuleContext(ConnectorNameContext.class,0);
		}
		public TerminalNode ADD() { return getToken(PostgresConnectorSyntaxParser.ADD, 0); }
		public TerminalNode TASK() { return getToken(PostgresConnectorSyntaxParser.TASK, 0); }
		public TaskNameContext taskName() {
			return getRuleContext(TaskNameContext.class,0);
		}
		public TaskOptionsContext taskOptions() {
			return getRuleContext(TaskOptionsContext.class,0);
		}
		public TerminalNode REMOVE() { return getToken(PostgresConnectorSyntaxParser.REMOVE, 0); }
		public TerminalNode START() { return getToken(PostgresConnectorSyntaxParser.START, 0); }
		public TerminalNode SHUTDOWN() { return getToken(PostgresConnectorSyntaxParser.SHUTDOWN, 0); }
		public TerminalNode RESYNC() { return getToken(PostgresConnectorSyntaxParser.RESYNC, 0); }
		public TerminalNode FULL() { return getToken(PostgresConnectorSyntaxParser.FULL, 0); }
		public TerminalNode TABLE() { return getToken(PostgresConnectorSyntaxParser.TABLE, 0); }
		public TableNameContext tableName() {
			return getRuleContext(TableNameContext.class,0);
		}
		public IfnotexistsContext ifnotexists() {
			return getRuleContext(IfnotexistsContext.class,0);
		}
		public AlterConnectorContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_alterConnector; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitAlterConnector(this);
			else return visitor.visitChildren(this);
		}
	}

	public final AlterConnectorContext alterConnector() throws RecognitionException {
		AlterConnectorContext _localctx = new AlterConnectorContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_alterConnector);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(48);
			match(ALTER);
			setState(49);
			match(CONNECTOR);
			setState(50);
			connectorName();
			setState(76);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
			case 1:
				{
				{
				setState(51);
				match(ADD);
				setState(52);
				match(TASK);
				setState(54);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==T__0) {
					{
					setState(53);
					ifnotexists();
					}
				}

				setState(56);
				taskName();
				setState(57);
				taskOptions();
				}
				}
				break;
			case 2:
				{
				{
				setState(59);
				match(REMOVE);
				setState(60);
				match(TASK);
				setState(61);
				taskName();
				}
				}
				break;
			case 3:
				{
				{
				setState(62);
				match(START);
				setState(63);
				match(TASK);
				setState(64);
				taskName();
				}
				}
				break;
			case 4:
				{
				{
				setState(65);
				match(SHUTDOWN);
				setState(66);
				match(TASK);
				setState(67);
				taskName();
				}
				}
				break;
			case 5:
				{
				{
				setState(68);
				match(RESYNC);
				setState(69);
				match(TASK);
				setState(70);
				taskName();
				}
				}
				break;
			case 6:
				{
				{
				setState(71);
				match(RESYNC);
				setState(72);
				match(FULL);
				}
				}
				break;
			case 7:
				{
				{
				setState(73);
				match(RESYNC);
				setState(74);
				match(TABLE);
				setState(75);
				tableName();
				}
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
	public static class DropConnectorContext extends ParserRuleContext {
		public TerminalNode DROP() { return getToken(PostgresConnectorSyntaxParser.DROP, 0); }
		public TerminalNode CONNECTOR() { return getToken(PostgresConnectorSyntaxParser.CONNECTOR, 0); }
		public ConnectorNameContext connectorName() {
			return getRuleContext(ConnectorNameContext.class,0);
		}
		public DropConnectorContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_dropConnector; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitDropConnector(this);
			else return visitor.visitChildren(this);
		}
	}

	public final DropConnectorContext dropConnector() throws RecognitionException {
		DropConnectorContext _localctx = new DropConnectorContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_dropConnector);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(78);
			match(DROP);
			setState(79);
			match(CONNECTOR);
			setState(80);
			connectorName();
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
	public static class ShutdownConnectorContext extends ParserRuleContext {
		public TerminalNode SHUTDOWN() { return getToken(PostgresConnectorSyntaxParser.SHUTDOWN, 0); }
		public TerminalNode CONNECTOR() { return getToken(PostgresConnectorSyntaxParser.CONNECTOR, 0); }
		public ConnectorNameContext connectorName() {
			return getRuleContext(ConnectorNameContext.class,0);
		}
		public ShutdownConnectorContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_shutdownConnector; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitShutdownConnector(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ShutdownConnectorContext shutdownConnector() throws RecognitionException {
		ShutdownConnectorContext _localctx = new ShutdownConnectorContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_shutdownConnector);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(82);
			match(SHUTDOWN);
			setState(83);
			match(CONNECTOR);
			setState(84);
			connectorName();
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
	public static class StartConnectorContext extends ParserRuleContext {
		public TerminalNode START() { return getToken(PostgresConnectorSyntaxParser.START, 0); }
		public TerminalNode CONNECTOR() { return getToken(PostgresConnectorSyntaxParser.CONNECTOR, 0); }
		public ConnectorNameContext connectorName() {
			return getRuleContext(ConnectorNameContext.class,0);
		}
		public StartConnectorContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_startConnector; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitStartConnector(this);
			else return visitor.visitChildren(this);
		}
	}

	public final StartConnectorContext startConnector() throws RecognitionException {
		StartConnectorContext _localctx = new StartConnectorContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_startConnector);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(86);
			match(START);
			setState(87);
			match(CONNECTOR);
			setState(88);
			connectorName();
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
	public static class CreateConnectorContext extends ParserRuleContext {
		public TerminalNode CREATE() { return getToken(PostgresConnectorSyntaxParser.CREATE, 0); }
		public TerminalNode CONNECTOR() { return getToken(PostgresConnectorSyntaxParser.CONNECTOR, 0); }
		public ConnectorNameContext connectorName() {
			return getRuleContext(ConnectorNameContext.class,0);
		}
		public TerminalNode CONNECT() { return getToken(PostgresConnectorSyntaxParser.CONNECT, 0); }
		public TerminalNode TO() { return getToken(PostgresConnectorSyntaxParser.TO, 0); }
		public ConnectorOptionsContext connectorOptions() {
			return getRuleContext(ConnectorOptionsContext.class,0);
		}
		public TerminalNode TEMPORARY() { return getToken(PostgresConnectorSyntaxParser.TEMPORARY, 0); }
		public IfnotexistsContext ifnotexists() {
			return getRuleContext(IfnotexistsContext.class,0);
		}
		public CreateConnectorContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_createConnector; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitCreateConnector(this);
			else return visitor.visitChildren(this);
		}
	}

	public final CreateConnectorContext createConnector() throws RecognitionException {
		CreateConnectorContext _localctx = new CreateConnectorContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_createConnector);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(90);
			match(CREATE);
			setState(92);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==TEMPORARY) {
				{
				setState(91);
				match(TEMPORARY);
				}
			}

			setState(94);
			match(CONNECTOR);
			setState(96);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==T__0) {
				{
				setState(95);
				ifnotexists();
				}
			}

			setState(98);
			connectorName();
			setState(99);
			match(CONNECT);
			setState(100);
			match(TO);
			setState(101);
			connectorOptions();
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
	public static class ConnectorNameContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(PostgresConnectorSyntaxParser.Identifier, 0); }
		public ConnectorNameContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_connectorName; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitConnectorName(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ConnectorNameContext connectorName() throws RecognitionException {
		ConnectorNameContext _localctx = new ConnectorNameContext(_ctx, getState());
		enterRule(_localctx, 14, RULE_connectorName);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(103);
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
	public static class TaskNameContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(PostgresConnectorSyntaxParser.Identifier, 0); }
		public TaskNameContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_taskName; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitTaskName(this);
			else return visitor.visitChildren(this);
		}
	}

	public final TaskNameContext taskName() throws RecognitionException {
		TaskNameContext _localctx = new TaskNameContext(_ctx, getState());
		enterRule(_localctx, 16, RULE_taskName);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(105);
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
	public static class ConnectorOptionsContext extends ParserRuleContext {
		public TerminalNode String() { return getToken(PostgresConnectorSyntaxParser.String, 0); }
		public ConnectorOptionsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_connectorOptions; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitConnectorOptions(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ConnectorOptionsContext connectorOptions() throws RecognitionException {
		ConnectorOptionsContext _localctx = new ConnectorOptionsContext(_ctx, getState());
		enterRule(_localctx, 18, RULE_connectorOptions);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(107);
			match(String);
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
	public static class TaskOptionsContext extends ParserRuleContext {
		public TerminalNode String() { return getToken(PostgresConnectorSyntaxParser.String, 0); }
		public TaskOptionsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_taskOptions; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitTaskOptions(this);
			else return visitor.visitChildren(this);
		}
	}

	public final TaskOptionsContext taskOptions() throws RecognitionException {
		TaskOptionsContext _localctx = new TaskOptionsContext(_ctx, getState());
		enterRule(_localctx, 20, RULE_taskOptions);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(109);
			match(String);
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
		public TerminalNode String() { return getToken(PostgresConnectorSyntaxParser.String, 0); }
		public TableNameContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_tableName; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitTableName(this);
			else return visitor.visitChildren(this);
		}
	}

	public final TableNameContext tableName() throws RecognitionException {
		TableNameContext _localctx = new TableNameContext(_ctx, getState());
		enterRule(_localctx, 22, RULE_tableName);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(111);
			match(String);
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
	public static class IfnotexistsContext extends ParserRuleContext {
		public IfnotexistsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_ifnotexists; }
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PostgresConnectorSyntaxVisitor ) return ((PostgresConnectorSyntaxVisitor<? extends T>)visitor).visitIfnotexists(this);
			else return visitor.visitChildren(this);
		}
	}

	public final IfnotexistsContext ifnotexists() throws RecognitionException {
		IfnotexistsContext _localctx = new IfnotexistsContext(_ctx, getState());
		enterRule(_localctx, 24, RULE_ifnotexists);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(113);
			match(T__0);
			setState(114);
			match(T__1);
			setState(115);
			match(T__2);
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
		"\u0004\u0001 v\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001\u0002\u0002"+
		"\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004\u0007\u0004\u0002\u0005"+
		"\u0007\u0005\u0002\u0006\u0007\u0006\u0002\u0007\u0007\u0007\u0002\b\u0007"+
		"\b\u0002\t\u0007\t\u0002\n\u0007\n\u0002\u000b\u0007\u000b\u0002\f\u0007"+
		"\f\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000"+
		"\u0003\u0000!\b\u0000\u0001\u0000\u0003\u0000$\b\u0000\u0001\u0000\u0001"+
		"\u0000\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0003"+
		"\u0001-\b\u0001\u0001\u0001\u0001\u0001\u0001\u0002\u0001\u0002\u0001"+
		"\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0003\u00027\b\u0002\u0001"+
		"\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001"+
		"\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001"+
		"\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001"+
		"\u0002\u0001\u0002\u0003\u0002M\b\u0002\u0001\u0003\u0001\u0003\u0001"+
		"\u0003\u0001\u0003\u0001\u0004\u0001\u0004\u0001\u0004\u0001\u0004\u0001"+
		"\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0006\u0001\u0006\u0003"+
		"\u0006]\b\u0006\u0001\u0006\u0001\u0006\u0003\u0006a\b\u0006\u0001\u0006"+
		"\u0001\u0006\u0001\u0006\u0001\u0006\u0001\u0006\u0001\u0007\u0001\u0007"+
		"\u0001\b\u0001\b\u0001\t\u0001\t\u0001\n\u0001\n\u0001\u000b\u0001\u000b"+
		"\u0001\f\u0001\f\u0001\f\u0001\f\u0001\f\u0000\u0000\r\u0000\u0002\u0004"+
		"\u0006\b\n\f\u000e\u0010\u0012\u0014\u0016\u0018\u0000\u0000x\u0000 \u0001"+
		"\u0000\u0000\u0000\u0002\'\u0001\u0000\u0000\u0000\u00040\u0001\u0000"+
		"\u0000\u0000\u0006N\u0001\u0000\u0000\u0000\bR\u0001\u0000\u0000\u0000"+
		"\nV\u0001\u0000\u0000\u0000\fZ\u0001\u0000\u0000\u0000\u000eg\u0001\u0000"+
		"\u0000\u0000\u0010i\u0001\u0000\u0000\u0000\u0012k\u0001\u0000\u0000\u0000"+
		"\u0014m\u0001\u0000\u0000\u0000\u0016o\u0001\u0000\u0000\u0000\u0018q"+
		"\u0001\u0000\u0000\u0000\u001a!\u0003\f\u0006\u0000\u001b!\u0003\n\u0005"+
		"\u0000\u001c!\u0003\b\u0004\u0000\u001d!\u0003\u0006\u0003\u0000\u001e"+
		"!\u0003\u0004\u0002\u0000\u001f!\u0003\u0002\u0001\u0000 \u001a\u0001"+
		"\u0000\u0000\u0000 \u001b\u0001\u0000\u0000\u0000 \u001c\u0001\u0000\u0000"+
		"\u0000 \u001d\u0001\u0000\u0000\u0000 \u001e\u0001\u0000\u0000\u0000 "+
		"\u001f\u0001\u0000\u0000\u0000!#\u0001\u0000\u0000\u0000\"$\u0005\u0006"+
		"\u0000\u0000#\"\u0001\u0000\u0000\u0000#$\u0001\u0000\u0000\u0000$%\u0001"+
		"\u0000\u0000\u0000%&\u0005\u0000\u0000\u0001&\u0001\u0001\u0000\u0000"+
		"\u0000\'(\u0005\u0019\u0000\u0000()\u0005\u0010\u0000\u0000),\u0003\u000e"+
		"\u0007\u0000*+\u0005\u0015\u0000\u0000+-\u0003\u0010\b\u0000,*\u0001\u0000"+
		"\u0000\u0000,-\u0001\u0000\u0000\u0000-.\u0001\u0000\u0000\u0000./\u0005"+
		"\u001a\u0000\u0000/\u0003\u0001\u0000\u0000\u000001\u0005\r\u0000\u0000"+
		"12\u0005\u0010\u0000\u00002L\u0003\u000e\u0007\u000034\u0005\u0013\u0000"+
		"\u000046\u0005\u0015\u0000\u000057\u0003\u0018\f\u000065\u0001\u0000\u0000"+
		"\u000067\u0001\u0000\u0000\u000078\u0001\u0000\u0000\u000089\u0003\u0010"+
		"\b\u00009:\u0003\u0014\n\u0000:M\u0001\u0000\u0000\u0000;<\u0005\u0014"+
		"\u0000\u0000<=\u0005\u0015\u0000\u0000=M\u0003\u0010\b\u0000>?\u0005\u000e"+
		"\u0000\u0000?@\u0005\u0015\u0000\u0000@M\u0003\u0010\b\u0000AB\u0005\u000f"+
		"\u0000\u0000BC\u0005\u0015\u0000\u0000CM\u0003\u0010\b\u0000DE\u0005\u0016"+
		"\u0000\u0000EF\u0005\u0015\u0000\u0000FM\u0003\u0010\b\u0000GH\u0005\u0016"+
		"\u0000\u0000HM\u0005\u0018\u0000\u0000IJ\u0005\u0016\u0000\u0000JK\u0005"+
		"\u0017\u0000\u0000KM\u0003\u0016\u000b\u0000L3\u0001\u0000\u0000\u0000"+
		"L;\u0001\u0000\u0000\u0000L>\u0001\u0000\u0000\u0000LA\u0001\u0000\u0000"+
		"\u0000LD\u0001\u0000\u0000\u0000LG\u0001\u0000\u0000\u0000LI\u0001\u0000"+
		"\u0000\u0000M\u0005\u0001\u0000\u0000\u0000NO\u0005\u000b\u0000\u0000"+
		"OP\u0005\u0010\u0000\u0000PQ\u0003\u000e\u0007\u0000Q\u0007\u0001\u0000"+
		"\u0000\u0000RS\u0005\u000f\u0000\u0000ST\u0005\u0010\u0000\u0000TU\u0003"+
		"\u000e\u0007\u0000U\t\u0001\u0000\u0000\u0000VW\u0005\u000e\u0000\u0000"+
		"WX\u0005\u0010\u0000\u0000XY\u0003\u000e\u0007\u0000Y\u000b\u0001\u0000"+
		"\u0000\u0000Z\\\u0005\f\u0000\u0000[]\u0005\u001b\u0000\u0000\\[\u0001"+
		"\u0000\u0000\u0000\\]\u0001\u0000\u0000\u0000]^\u0001\u0000\u0000\u0000"+
		"^`\u0005\u0010\u0000\u0000_a\u0003\u0018\f\u0000`_\u0001\u0000\u0000\u0000"+
		"`a\u0001\u0000\u0000\u0000ab\u0001\u0000\u0000\u0000bc\u0003\u000e\u0007"+
		"\u0000cd\u0005\u0011\u0000\u0000de\u0005\u0012\u0000\u0000ef\u0003\u0012"+
		"\t\u0000f\r\u0001\u0000\u0000\u0000gh\u0005\u001e\u0000\u0000h\u000f\u0001"+
		"\u0000\u0000\u0000ij\u0005\u001e\u0000\u0000j\u0011\u0001\u0000\u0000"+
		"\u0000kl\u0005\u001f\u0000\u0000l\u0013\u0001\u0000\u0000\u0000mn\u0005"+
		"\u001f\u0000\u0000n\u0015\u0001\u0000\u0000\u0000op\u0005\u001f\u0000"+
		"\u0000p\u0017\u0001\u0000\u0000\u0000qr\u0005\u0001\u0000\u0000rs\u0005"+
		"\u0002\u0000\u0000st\u0005\u0003\u0000\u0000t\u0019\u0001\u0000\u0000"+
		"\u0000\u0007 #,6L\\`";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}