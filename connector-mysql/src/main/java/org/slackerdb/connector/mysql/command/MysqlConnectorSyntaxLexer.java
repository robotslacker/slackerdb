// Generated from MysqlConnectorSyntax.g4 by ANTLR 4.13.2
package org.slackerdb.connector.mysql.command;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.*;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class MysqlConnectorSyntaxLexer extends Lexer {
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
	public static String[] channelNames = {
		"DEFAULT_TOKEN_CHANNEL", "HIDDEN"
	};

	public static String[] modeNames = {
		"DEFAULT_MODE"
	};

	private static String[] makeRuleNames() {
		return new String[] {
			"T__0", "T__1", "T__2", "WS", "CRLF", "SColon", "Assign", "Comma", "QMark", 
			"Colon", "DROP", "CREATE", "ALTER", "START", "SHUTDOWN", "CONNECTOR", 
			"CONNECT", "TO", "ADD", "REMOVE", "TASK", "RESYNC", "TABLE", "FULL", 
			"SHOW", "STATUS", "TEMPORARY", "Bool", "Number", "Identifier", "String", 
			"Comment", "Int", "Digit"
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


	public MysqlConnectorSyntaxLexer(CharStream input) {
		super(input);
		_interp = new LexerATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@Override
	public String getGrammarFileName() { return "MysqlConnectorSyntax.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public String[] getChannelNames() { return channelNames; }

	@Override
	public String[] getModeNames() { return modeNames; }

	@Override
	public ATN getATN() { return _ATN; }

	public static final String _serializedATN =
		"\u0004\u0000 \u0137\u0006\uffff\uffff\u0002\u0000\u0007\u0000\u0002\u0001"+
		"\u0007\u0001\u0002\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004"+
		"\u0007\u0004\u0002\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002\u0007"+
		"\u0007\u0007\u0002\b\u0007\b\u0002\t\u0007\t\u0002\n\u0007\n\u0002\u000b"+
		"\u0007\u000b\u0002\f\u0007\f\u0002\r\u0007\r\u0002\u000e\u0007\u000e\u0002"+
		"\u000f\u0007\u000f\u0002\u0010\u0007\u0010\u0002\u0011\u0007\u0011\u0002"+
		"\u0012\u0007\u0012\u0002\u0013\u0007\u0013\u0002\u0014\u0007\u0014\u0002"+
		"\u0015\u0007\u0015\u0002\u0016\u0007\u0016\u0002\u0017\u0007\u0017\u0002"+
		"\u0018\u0007\u0018\u0002\u0019\u0007\u0019\u0002\u001a\u0007\u001a\u0002"+
		"\u001b\u0007\u001b\u0002\u001c\u0007\u001c\u0002\u001d\u0007\u001d\u0002"+
		"\u001e\u0007\u001e\u0002\u001f\u0007\u001f\u0002 \u0007 \u0002!\u0007"+
		"!\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0001\u0001\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002"+
		"\u0001\u0002\u0001\u0002\u0001\u0003\u0004\u0003U\b\u0003\u000b\u0003"+
		"\f\u0003V\u0001\u0003\u0001\u0003\u0001\u0004\u0001\u0004\u0001\u0005"+
		"\u0001\u0005\u0001\u0006\u0001\u0006\u0001\u0007\u0001\u0007\u0001\b\u0001"+
		"\b\u0001\t\u0001\t\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\f\u0001\f\u0001\f\u0001\f\u0001\f\u0001\f\u0001\r\u0001\r\u0001"+
		"\r\u0001\r\u0001\r\u0001\r\u0001\u000e\u0001\u000e\u0001\u000e\u0001\u000e"+
		"\u0001\u000e\u0001\u000e\u0001\u000e\u0001\u000e\u0001\u000e\u0001\u000f"+
		"\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f"+
		"\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u0010\u0001\u0010\u0001\u0010"+
		"\u0001\u0010\u0001\u0010\u0001\u0010\u0001\u0010\u0001\u0010\u0001\u0011"+
		"\u0001\u0011\u0001\u0011\u0001\u0012\u0001\u0012\u0001\u0012\u0001\u0012"+
		"\u0001\u0013\u0001\u0013\u0001\u0013\u0001\u0013\u0001\u0013\u0001\u0013"+
		"\u0001\u0013\u0001\u0014\u0001\u0014\u0001\u0014\u0001\u0014\u0001\u0014"+
		"\u0001\u0015\u0001\u0015\u0001\u0015\u0001\u0015\u0001\u0015\u0001\u0015"+
		"\u0001\u0015\u0001\u0016\u0001\u0016\u0001\u0016\u0001\u0016\u0001\u0016"+
		"\u0001\u0016\u0001\u0017\u0001\u0017\u0001\u0017\u0001\u0017\u0001\u0017"+
		"\u0001\u0018\u0001\u0018\u0001\u0018\u0001\u0018\u0001\u0018\u0001\u0019"+
		"\u0001\u0019\u0001\u0019\u0001\u0019\u0001\u0019\u0001\u0019\u0001\u0019"+
		"\u0001\u001a\u0001\u001a\u0001\u001a\u0001\u001a\u0001\u001a\u0001\u001a"+
		"\u0001\u001a\u0001\u001a\u0001\u001a\u0001\u001a\u0001\u001b\u0001\u001b"+
		"\u0001\u001b\u0001\u001b\u0001\u001b\u0001\u001b\u0001\u001b\u0001\u001b"+
		"\u0001\u001b\u0003\u001b\u00de\b\u001b\u0001\u001c\u0001\u001c\u0001\u001c"+
		"\u0005\u001c\u00e3\b\u001c\n\u001c\f\u001c\u00e6\t\u001c\u0003\u001c\u00e8"+
		"\b\u001c\u0001\u001d\u0001\u001d\u0005\u001d\u00ec\b\u001d\n\u001d\f\u001d"+
		"\u00ef\t\u001d\u0001\u001d\u0001\u001d\u0001\u001d\u0001\u001d\u0001\u001d"+
		"\u0005\u001d\u00f6\b\u001d\n\u001d\f\u001d\u00f9\t\u001d\u0001\u001d\u0003"+
		"\u001d\u00fc\b\u001d\u0001\u001e\u0001\u001e\u0001\u001e\u0001\u001e\u0005"+
		"\u001e\u0102\b\u001e\n\u001e\f\u001e\u0105\t\u001e\u0001\u001e\u0001\u001e"+
		"\u0001\u001e\u0001\u001e\u0001\u001e\u0005\u001e\u010c\b\u001e\n\u001e"+
		"\f\u001e\u010f\t\u001e\u0001\u001e\u0003\u001e\u0112\b\u001e\u0001\u001f"+
		"\u0001\u001f\u0001\u001f\u0001\u001f\u0005\u001f\u0118\b\u001f\n\u001f"+
		"\f\u001f\u011b\t\u001f\u0001\u001f\u0001\u001f\u0001\u001f\u0001\u001f"+
		"\u0005\u001f\u0121\b\u001f\n\u001f\f\u001f\u0124\t\u001f\u0001\u001f\u0001"+
		"\u001f\u0003\u001f\u0128\b\u001f\u0001\u001f\u0001\u001f\u0001 \u0001"+
		" \u0005 \u012e\b \n \f \u0131\t \u0001 \u0003 \u0134\b \u0001!\u0001!"+
		"\u0001\u0122\u0000\"\u0001\u0001\u0003\u0002\u0005\u0003\u0007\u0004\t"+
		"\u0005\u000b\u0006\r\u0007\u000f\b\u0011\t\u0013\n\u0015\u000b\u0017\f"+
		"\u0019\r\u001b\u000e\u001d\u000f\u001f\u0010!\u0011#\u0012%\u0013\'\u0014"+
		")\u0015+\u0016-\u0017/\u00181\u00193\u001a5\u001b7\u001c9\u001d;\u001e"+
		"=\u001f? A\u0000C\u0000\u0001\u0000 \u0002\u0000IIii\u0002\u0000FFff\u0002"+
		"\u0000NNnn\u0002\u0000OOoo\u0002\u0000TTtt\u0002\u0000EEee\u0002\u0000"+
		"XXxx\u0002\u0000SSss\u0003\u0000\t\n\f\r  \u0002\u0000DDdd\u0002\u0000"+
		"RRrr\u0002\u0000PPpp\u0002\u0000CCcc\u0002\u0000AAaa\u0002\u0000LLll\u0002"+
		"\u0000HHhh\u0002\u0000UUuu\u0002\u0000WWww\u0002\u0000MMmm\u0002\u0000"+
		"VVvv\u0002\u0000KKkk\u0002\u0000YYyy\u0002\u0000BBbb\u0003\u0000AZ__a"+
		"z\u0005\u0000..09AZ__az\u0001\u0000\"\"\u0004\u0000\n\n\r\r\"\"\\\\\u0002"+
		"\u0000\n\n\r\r\u0001\u0000\'\'\u0004\u0000\n\n\r\r\'\'\\\\\u0001\u0000"+
		"19\u0001\u000009\u0145\u0000\u0001\u0001\u0000\u0000\u0000\u0000\u0003"+
		"\u0001\u0000\u0000\u0000\u0000\u0005\u0001\u0000\u0000\u0000\u0000\u0007"+
		"\u0001\u0000\u0000\u0000\u0000\t\u0001\u0000\u0000\u0000\u0000\u000b\u0001"+
		"\u0000\u0000\u0000\u0000\r\u0001\u0000\u0000\u0000\u0000\u000f\u0001\u0000"+
		"\u0000\u0000\u0000\u0011\u0001\u0000\u0000\u0000\u0000\u0013\u0001\u0000"+
		"\u0000\u0000\u0000\u0015\u0001\u0000\u0000\u0000\u0000\u0017\u0001\u0000"+
		"\u0000\u0000\u0000\u0019\u0001\u0000\u0000\u0000\u0000\u001b\u0001\u0000"+
		"\u0000\u0000\u0000\u001d\u0001\u0000\u0000\u0000\u0000\u001f\u0001\u0000"+
		"\u0000\u0000\u0000!\u0001\u0000\u0000\u0000\u0000#\u0001\u0000\u0000\u0000"+
		"\u0000%\u0001\u0000\u0000\u0000\u0000\'\u0001\u0000\u0000\u0000\u0000"+
		")\u0001\u0000\u0000\u0000\u0000+\u0001\u0000\u0000\u0000\u0000-\u0001"+
		"\u0000\u0000\u0000\u0000/\u0001\u0000\u0000\u0000\u00001\u0001\u0000\u0000"+
		"\u0000\u00003\u0001\u0000\u0000\u0000\u00005\u0001\u0000\u0000\u0000\u0000"+
		"7\u0001\u0000\u0000\u0000\u00009\u0001\u0000\u0000\u0000\u0000;\u0001"+
		"\u0000\u0000\u0000\u0000=\u0001\u0000\u0000\u0000\u0000?\u0001\u0000\u0000"+
		"\u0000\u0001E\u0001\u0000\u0000\u0000\u0003H\u0001\u0000\u0000\u0000\u0005"+
		"L\u0001\u0000\u0000\u0000\u0007T\u0001\u0000\u0000\u0000\tZ\u0001\u0000"+
		"\u0000\u0000\u000b\\\u0001\u0000\u0000\u0000\r^\u0001\u0000\u0000\u0000"+
		"\u000f`\u0001\u0000\u0000\u0000\u0011b\u0001\u0000\u0000\u0000\u0013d"+
		"\u0001\u0000\u0000\u0000\u0015f\u0001\u0000\u0000\u0000\u0017k\u0001\u0000"+
		"\u0000\u0000\u0019r\u0001\u0000\u0000\u0000\u001bx\u0001\u0000\u0000\u0000"+
		"\u001d~\u0001\u0000\u0000\u0000\u001f\u0087\u0001\u0000\u0000\u0000!\u0091"+
		"\u0001\u0000\u0000\u0000#\u0099\u0001\u0000\u0000\u0000%\u009c\u0001\u0000"+
		"\u0000\u0000\'\u00a0\u0001\u0000\u0000\u0000)\u00a7\u0001\u0000\u0000"+
		"\u0000+\u00ac\u0001\u0000\u0000\u0000-\u00b3\u0001\u0000\u0000\u0000/"+
		"\u00b9\u0001\u0000\u0000\u00001\u00be\u0001\u0000\u0000\u00003\u00c3\u0001"+
		"\u0000\u0000\u00005\u00ca\u0001\u0000\u0000\u00007\u00dd\u0001\u0000\u0000"+
		"\u00009\u00df\u0001\u0000\u0000\u0000;\u00fb\u0001\u0000\u0000\u0000="+
		"\u0111\u0001\u0000\u0000\u0000?\u0127\u0001\u0000\u0000\u0000A\u0133\u0001"+
		"\u0000\u0000\u0000C\u0135\u0001\u0000\u0000\u0000EF\u0007\u0000\u0000"+
		"\u0000FG\u0007\u0001\u0000\u0000G\u0002\u0001\u0000\u0000\u0000HI\u0007"+
		"\u0002\u0000\u0000IJ\u0007\u0003\u0000\u0000JK\u0007\u0004\u0000\u0000"+
		"K\u0004\u0001\u0000\u0000\u0000LM\u0007\u0005\u0000\u0000MN\u0007\u0006"+
		"\u0000\u0000NO\u0007\u0000\u0000\u0000OP\u0007\u0007\u0000\u0000PQ\u0007"+
		"\u0004\u0000\u0000QR\u0007\u0007\u0000\u0000R\u0006\u0001\u0000\u0000"+
		"\u0000SU\u0007\b\u0000\u0000TS\u0001\u0000\u0000\u0000UV\u0001\u0000\u0000"+
		"\u0000VT\u0001\u0000\u0000\u0000VW\u0001\u0000\u0000\u0000WX\u0001\u0000"+
		"\u0000\u0000XY\u0006\u0003\u0000\u0000Y\b\u0001\u0000\u0000\u0000Z[\u0005"+
		"\n\u0000\u0000[\n\u0001\u0000\u0000\u0000\\]\u0005;\u0000\u0000]\f\u0001"+
		"\u0000\u0000\u0000^_\u0005=\u0000\u0000_\u000e\u0001\u0000\u0000\u0000"+
		"`a\u0005,\u0000\u0000a\u0010\u0001\u0000\u0000\u0000bc\u0005?\u0000\u0000"+
		"c\u0012\u0001\u0000\u0000\u0000de\u0005:\u0000\u0000e\u0014\u0001\u0000"+
		"\u0000\u0000fg\u0007\t\u0000\u0000gh\u0007\n\u0000\u0000hi\u0007\u0003"+
		"\u0000\u0000ij\u0007\u000b\u0000\u0000j\u0016\u0001\u0000\u0000\u0000"+
		"kl\u0007\f\u0000\u0000lm\u0007\n\u0000\u0000mn\u0007\u0005\u0000\u0000"+
		"no\u0007\r\u0000\u0000op\u0007\u0004\u0000\u0000pq\u0007\u0005\u0000\u0000"+
		"q\u0018\u0001\u0000\u0000\u0000rs\u0007\r\u0000\u0000st\u0007\u000e\u0000"+
		"\u0000tu\u0007\u0004\u0000\u0000uv\u0007\u0005\u0000\u0000vw\u0007\n\u0000"+
		"\u0000w\u001a\u0001\u0000\u0000\u0000xy\u0007\u0007\u0000\u0000yz\u0007"+
		"\u0004\u0000\u0000z{\u0007\r\u0000\u0000{|\u0007\n\u0000\u0000|}\u0007"+
		"\u0004\u0000\u0000}\u001c\u0001\u0000\u0000\u0000~\u007f\u0007\u0007\u0000"+
		"\u0000\u007f\u0080\u0007\u000f\u0000\u0000\u0080\u0081\u0007\u0010\u0000"+
		"\u0000\u0081\u0082\u0007\u0004\u0000\u0000\u0082\u0083\u0007\t\u0000\u0000"+
		"\u0083\u0084\u0007\u0003\u0000\u0000\u0084\u0085\u0007\u0011\u0000\u0000"+
		"\u0085\u0086\u0007\u0002\u0000\u0000\u0086\u001e\u0001\u0000\u0000\u0000"+
		"\u0087\u0088\u0007\f\u0000\u0000\u0088\u0089\u0007\u0003\u0000\u0000\u0089"+
		"\u008a\u0007\u0002\u0000\u0000\u008a\u008b\u0007\u0002\u0000\u0000\u008b"+
		"\u008c\u0007\u0005\u0000\u0000\u008c\u008d\u0007\f\u0000\u0000\u008d\u008e"+
		"\u0007\u0004\u0000\u0000\u008e\u008f\u0007\u0003\u0000\u0000\u008f\u0090"+
		"\u0007\n\u0000\u0000\u0090 \u0001\u0000\u0000\u0000\u0091\u0092\u0007"+
		"\f\u0000\u0000\u0092\u0093\u0007\u0003\u0000\u0000\u0093\u0094\u0007\u0002"+
		"\u0000\u0000\u0094\u0095\u0007\u0002\u0000\u0000\u0095\u0096\u0007\u0005"+
		"\u0000\u0000\u0096\u0097\u0007\f\u0000\u0000\u0097\u0098\u0007\u0004\u0000"+
		"\u0000\u0098\"\u0001\u0000\u0000\u0000\u0099\u009a\u0007\u0004\u0000\u0000"+
		"\u009a\u009b\u0007\u0003\u0000\u0000\u009b$\u0001\u0000\u0000\u0000\u009c"+
		"\u009d\u0007\r\u0000\u0000\u009d\u009e\u0007\t\u0000\u0000\u009e\u009f"+
		"\u0007\t\u0000\u0000\u009f&\u0001\u0000\u0000\u0000\u00a0\u00a1\u0007"+
		"\n\u0000\u0000\u00a1\u00a2\u0007\u0005\u0000\u0000\u00a2\u00a3\u0007\u0012"+
		"\u0000\u0000\u00a3\u00a4\u0007\u0003\u0000\u0000\u00a4\u00a5\u0007\u0013"+
		"\u0000\u0000\u00a5\u00a6\u0007\u0005\u0000\u0000\u00a6(\u0001\u0000\u0000"+
		"\u0000\u00a7\u00a8\u0007\u0004\u0000\u0000\u00a8\u00a9\u0007\r\u0000\u0000"+
		"\u00a9\u00aa\u0007\u0007\u0000\u0000\u00aa\u00ab\u0007\u0014\u0000\u0000"+
		"\u00ab*\u0001\u0000\u0000\u0000\u00ac\u00ad\u0007\n\u0000\u0000\u00ad"+
		"\u00ae\u0007\u0005\u0000\u0000\u00ae\u00af\u0007\u0007\u0000\u0000\u00af"+
		"\u00b0\u0007\u0015\u0000\u0000\u00b0\u00b1\u0007\u0002\u0000\u0000\u00b1"+
		"\u00b2\u0007\f\u0000\u0000\u00b2,\u0001\u0000\u0000\u0000\u00b3\u00b4"+
		"\u0007\u0004\u0000\u0000\u00b4\u00b5\u0007\r\u0000\u0000\u00b5\u00b6\u0007"+
		"\u0016\u0000\u0000\u00b6\u00b7\u0007\u000e\u0000\u0000\u00b7\u00b8\u0007"+
		"\u0005\u0000\u0000\u00b8.\u0001\u0000\u0000\u0000\u00b9\u00ba\u0007\u0001"+
		"\u0000\u0000\u00ba\u00bb\u0007\u0010\u0000\u0000\u00bb\u00bc\u0007\u000e"+
		"\u0000\u0000\u00bc\u00bd\u0007\u000e\u0000\u0000\u00bd0\u0001\u0000\u0000"+
		"\u0000\u00be\u00bf\u0007\u0007\u0000\u0000\u00bf\u00c0\u0007\u000f\u0000"+
		"\u0000\u00c0\u00c1\u0007\u0003\u0000\u0000\u00c1\u00c2\u0007\u0011\u0000"+
		"\u0000\u00c22\u0001\u0000\u0000\u0000\u00c3\u00c4\u0007\u0007\u0000\u0000"+
		"\u00c4\u00c5\u0007\u0004\u0000\u0000\u00c5\u00c6\u0007\r\u0000\u0000\u00c6"+
		"\u00c7\u0007\u0004\u0000\u0000\u00c7\u00c8\u0007\u0010\u0000\u0000\u00c8"+
		"\u00c9\u0007\u0007\u0000\u0000\u00c94\u0001\u0000\u0000\u0000\u00ca\u00cb"+
		"\u0007\u0004\u0000\u0000\u00cb\u00cc\u0007\u0005\u0000\u0000\u00cc\u00cd"+
		"\u0007\u0012\u0000\u0000\u00cd\u00ce\u0007\u000b\u0000\u0000\u00ce\u00cf"+
		"\u0007\u0003\u0000\u0000\u00cf\u00d0\u0007\n\u0000\u0000\u00d0\u00d1\u0007"+
		"\r\u0000\u0000\u00d1\u00d2\u0007\n\u0000\u0000\u00d2\u00d3\u0007\u0015"+
		"\u0000\u0000\u00d36\u0001\u0000\u0000\u0000\u00d4\u00d5\u0007\u0004\u0000"+
		"\u0000\u00d5\u00d6\u0007\n\u0000\u0000\u00d6\u00d7\u0007\u0010\u0000\u0000"+
		"\u00d7\u00de\u0007\u0005\u0000\u0000\u00d8\u00d9\u0007\u0001\u0000\u0000"+
		"\u00d9\u00da\u0007\r\u0000\u0000\u00da\u00db\u0007\u000e\u0000\u0000\u00db"+
		"\u00dc\u0007\u0007\u0000\u0000\u00dc\u00de\u0007\u0005\u0000\u0000\u00dd"+
		"\u00d4\u0001\u0000\u0000\u0000\u00dd\u00d8\u0001\u0000\u0000\u0000\u00de"+
		"8\u0001\u0000\u0000\u0000\u00df\u00e7\u0003A \u0000\u00e0\u00e4\u0005"+
		".\u0000\u0000\u00e1\u00e3\u0003C!\u0000\u00e2\u00e1\u0001\u0000\u0000"+
		"\u0000\u00e3\u00e6\u0001\u0000\u0000\u0000\u00e4\u00e2\u0001\u0000\u0000"+
		"\u0000\u00e4\u00e5\u0001\u0000\u0000\u0000\u00e5\u00e8\u0001\u0000\u0000"+
		"\u0000\u00e6\u00e4\u0001\u0000\u0000\u0000\u00e7\u00e0\u0001\u0000\u0000"+
		"\u0000\u00e7\u00e8\u0001\u0000\u0000\u0000\u00e8:\u0001\u0000\u0000\u0000"+
		"\u00e9\u00ed\u0007\u0017\u0000\u0000\u00ea\u00ec\u0007\u0018\u0000\u0000"+
		"\u00eb\u00ea\u0001\u0000\u0000\u0000\u00ec\u00ef\u0001\u0000\u0000\u0000"+
		"\u00ed\u00eb\u0001\u0000\u0000\u0000\u00ed\u00ee\u0001\u0000\u0000\u0000"+
		"\u00ee\u00fc\u0001\u0000\u0000\u0000\u00ef\u00ed\u0001\u0000\u0000\u0000"+
		"\u00f0\u00f1\u0005$\u0000\u0000\u00f1\u00f2\u0005{\u0000\u0000\u00f2\u00f3"+
		"\u0001\u0000\u0000\u0000\u00f3\u00f7\u0007\u0017\u0000\u0000\u00f4\u00f6"+
		"\u0007\u0018\u0000\u0000\u00f5\u00f4\u0001\u0000\u0000\u0000\u00f6\u00f9"+
		"\u0001\u0000\u0000\u0000\u00f7\u00f5\u0001\u0000\u0000\u0000\u00f7\u00f8"+
		"\u0001\u0000\u0000\u0000\u00f8\u00fa\u0001\u0000\u0000\u0000\u00f9\u00f7"+
		"\u0001\u0000\u0000\u0000\u00fa\u00fc\u0005}\u0000\u0000\u00fb\u00e9\u0001"+
		"\u0000\u0000\u0000\u00fb\u00f0\u0001\u0000\u0000\u0000\u00fc<\u0001\u0000"+
		"\u0000\u0000\u00fd\u0103\u0007\u0019\u0000\u0000\u00fe\u0102\b\u001a\u0000"+
		"\u0000\u00ff\u0100\u0005\\\u0000\u0000\u0100\u0102\b\u001b\u0000\u0000"+
		"\u0101\u00fe\u0001\u0000\u0000\u0000\u0101\u00ff\u0001\u0000\u0000\u0000"+
		"\u0102\u0105\u0001\u0000\u0000\u0000\u0103\u0101\u0001\u0000\u0000\u0000"+
		"\u0103\u0104\u0001\u0000\u0000\u0000\u0104\u0106\u0001\u0000\u0000\u0000"+
		"\u0105\u0103\u0001\u0000\u0000\u0000\u0106\u0112\u0007\u0019\u0000\u0000"+
		"\u0107\u010d\u0007\u001c\u0000\u0000\u0108\u010c\b\u001d\u0000\u0000\u0109"+
		"\u010a\u0005\\\u0000\u0000\u010a\u010c\b\u001b\u0000\u0000\u010b\u0108"+
		"\u0001\u0000\u0000\u0000\u010b\u0109\u0001\u0000\u0000\u0000\u010c\u010f"+
		"\u0001\u0000\u0000\u0000\u010d\u010b\u0001\u0000\u0000\u0000\u010d\u010e"+
		"\u0001\u0000\u0000\u0000\u010e\u0110\u0001\u0000\u0000\u0000\u010f\u010d"+
		"\u0001\u0000\u0000\u0000\u0110\u0112\u0007\u001c\u0000\u0000\u0111\u00fd"+
		"\u0001\u0000\u0000\u0000\u0111\u0107\u0001\u0000\u0000\u0000\u0112>\u0001"+
		"\u0000\u0000\u0000\u0113\u0114\u0005-\u0000\u0000\u0114\u0115\u0005-\u0000"+
		"\u0000\u0115\u0119\u0001\u0000\u0000\u0000\u0116\u0118\b\u001b\u0000\u0000"+
		"\u0117\u0116\u0001\u0000\u0000\u0000\u0118\u011b\u0001\u0000\u0000\u0000"+
		"\u0119\u0117\u0001\u0000\u0000\u0000\u0119\u011a\u0001\u0000\u0000\u0000"+
		"\u011a\u0128\u0001\u0000\u0000\u0000\u011b\u0119\u0001\u0000\u0000\u0000"+
		"\u011c\u011d\u0005/\u0000\u0000\u011d\u011e\u0005*\u0000\u0000\u011e\u0122"+
		"\u0001\u0000\u0000\u0000\u011f\u0121\t\u0000\u0000\u0000\u0120\u011f\u0001"+
		"\u0000\u0000\u0000\u0121\u0124\u0001\u0000\u0000\u0000\u0122\u0123\u0001"+
		"\u0000\u0000\u0000\u0122\u0120\u0001\u0000\u0000\u0000\u0123\u0125\u0001"+
		"\u0000\u0000\u0000\u0124\u0122\u0001\u0000\u0000\u0000\u0125\u0126\u0005"+
		"*\u0000\u0000\u0126\u0128\u0005/\u0000\u0000\u0127\u0113\u0001\u0000\u0000"+
		"\u0000\u0127\u011c\u0001\u0000\u0000\u0000\u0128\u0129\u0001\u0000\u0000"+
		"\u0000\u0129\u012a\u0006\u001f\u0000\u0000\u012a@\u0001\u0000\u0000\u0000"+
		"\u012b\u012f\u0007\u001e\u0000\u0000\u012c\u012e\u0003C!\u0000\u012d\u012c"+
		"\u0001\u0000\u0000\u0000\u012e\u0131\u0001\u0000\u0000\u0000\u012f\u012d"+
		"\u0001\u0000\u0000\u0000\u012f\u0130\u0001\u0000\u0000\u0000\u0130\u0134"+
		"\u0001\u0000\u0000\u0000\u0131\u012f\u0001\u0000\u0000\u0000\u0132\u0134"+
		"\u00050\u0000\u0000\u0133\u012b\u0001\u0000\u0000\u0000\u0133\u0132\u0001"+
		"\u0000\u0000\u0000\u0134B\u0001\u0000\u0000\u0000\u0135\u0136\u0007\u001f"+
		"\u0000\u0000\u0136D\u0001\u0000\u0000\u0000\u0012\u0000V\u00dd\u00e4\u00e7"+
		"\u00ed\u00f7\u00fb\u0101\u0103\u010b\u010d\u0111\u0119\u0122\u0127\u012f"+
		"\u0133\u0001\u0000\u0001\u0000";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}