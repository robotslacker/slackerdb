// Generated from CopyStatement.g4 by ANTLR 4.13.2
package org.slackerdb.dbserver.sql.antlr;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.*;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class CopyStatementLexer extends Lexer {
	static { RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		T__0=1, T__1=2, T__2=3, T__3=4, COPY=5, TO=6, FROM=7, WITH=8, SColon=9, 
		STDIN=10, STDOUT=11, BOOLEAN=12, IDENTIFIER=13, STRING_LITERAL=14, NUMBER=15, 
		NULL_CHAR=16, WS=17, Comment=18;
	public static String[] channelNames = {
		"DEFAULT_TOKEN_CHANNEL", "HIDDEN"
	};

	public static String[] modeNames = {
		"DEFAULT_MODE"
	};

	private static String[] makeRuleNames() {
		return new String[] {
			"T__0", "T__1", "T__2", "T__3", "COPY", "TO", "FROM", "WITH", "SColon", 
			"STDIN", "STDOUT", "BOOLEAN", "IDENTIFIER", "STRING_LITERAL", "NUMBER", 
			"NULL_CHAR", "WS", "Comment"
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


	public CopyStatementLexer(CharStream input) {
		super(input);
		_interp = new LexerATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@Override
	public String getGrammarFileName() { return "CopyStatement.g4"; }

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
		"\u0004\u0000\u0012\u00a4\u0006\uffff\uffff\u0002\u0000\u0007\u0000\u0002"+
		"\u0001\u0007\u0001\u0002\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002"+
		"\u0004\u0007\u0004\u0002\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002"+
		"\u0007\u0007\u0007\u0002\b\u0007\b\u0002\t\u0007\t\u0002\n\u0007\n\u0002"+
		"\u000b\u0007\u000b\u0002\f\u0007\f\u0002\r\u0007\r\u0002\u000e\u0007\u000e"+
		"\u0002\u000f\u0007\u000f\u0002\u0010\u0007\u0010\u0002\u0011\u0007\u0011"+
		"\u0001\u0000\u0001\u0000\u0001\u0001\u0001\u0001\u0001\u0002\u0001\u0002"+
		"\u0001\u0003\u0001\u0003\u0001\u0004\u0001\u0004\u0001\u0004\u0001\u0004"+
		"\u0001\u0004\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0006\u0001\u0006"+
		"\u0001\u0006\u0001\u0006\u0001\u0006\u0001\u0007\u0001\u0007\u0001\u0007"+
		"\u0001\u0007\u0001\u0007\u0001\b\u0001\b\u0001\t\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001"+
		"\n\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0003\u000bX\b\u000b\u0001\f\u0001"+
		"\f\u0005\f\\\b\f\n\f\f\f_\t\f\u0001\r\u0001\r\u0001\r\u0001\r\u0005\r"+
		"e\b\r\n\r\f\rh\t\r\u0001\r\u0001\r\u0001\r\u0001\r\u0001\r\u0005\ro\b"+
		"\r\n\r\f\rr\t\r\u0001\r\u0003\ru\b\r\u0001\u000e\u0004\u000ex\b\u000e"+
		"\u000b\u000e\f\u000ey\u0001\u000e\u0001\u000e\u0004\u000e~\b\u000e\u000b"+
		"\u000e\f\u000e\u007f\u0003\u000e\u0082\b\u000e\u0001\u000f\u0001\u000f"+
		"\u0001\u0010\u0004\u0010\u0087\b\u0010\u000b\u0010\f\u0010\u0088\u0001"+
		"\u0010\u0001\u0010\u0001\u0011\u0001\u0011\u0001\u0011\u0001\u0011\u0005"+
		"\u0011\u0091\b\u0011\n\u0011\f\u0011\u0094\t\u0011\u0001\u0011\u0001\u0011"+
		"\u0001\u0011\u0001\u0011\u0005\u0011\u009a\b\u0011\n\u0011\f\u0011\u009d"+
		"\t\u0011\u0001\u0011\u0001\u0011\u0003\u0011\u00a1\b\u0011\u0001\u0011"+
		"\u0001\u0011\u0001\u009b\u0000\u0012\u0001\u0001\u0003\u0002\u0005\u0003"+
		"\u0007\u0004\t\u0005\u000b\u0006\r\u0007\u000f\b\u0011\t\u0013\n\u0015"+
		"\u000b\u0017\f\u0019\r\u001b\u000e\u001d\u000f\u001f\u0010!\u0011#\u0012"+
		"\u0001\u0000\u0019\u0002\u0000CCcc\u0002\u0000OOoo\u0002\u0000PPpp\u0002"+
		"\u0000YYyy\u0002\u0000TTtt\u0002\u0000FFff\u0002\u0000RRrr\u0002\u0000"+
		"MMmm\u0002\u0000WWww\u0002\u0000IIii\u0002\u0000HHhh\u0002\u0000SSss\u0002"+
		"\u0000DDdd\u0002\u0000NNnn\u0002\u0000UUuu\u0002\u0000EEee\u0002\u0000"+
		"AAaa\u0002\u0000LLll\u0003\u0000AZ__az\u0005\u0000..09AZ__az\u0001\u0000"+
		"\'\'\u0001\u0000\"\"\u0001\u000009\u0003\u0000\t\n\r\r  \u0002\u0000\n"+
		"\n\r\r\u00b1\u0000\u0001\u0001\u0000\u0000\u0000\u0000\u0003\u0001\u0000"+
		"\u0000\u0000\u0000\u0005\u0001\u0000\u0000\u0000\u0000\u0007\u0001\u0000"+
		"\u0000\u0000\u0000\t\u0001\u0000\u0000\u0000\u0000\u000b\u0001\u0000\u0000"+
		"\u0000\u0000\r\u0001\u0000\u0000\u0000\u0000\u000f\u0001\u0000\u0000\u0000"+
		"\u0000\u0011\u0001\u0000\u0000\u0000\u0000\u0013\u0001\u0000\u0000\u0000"+
		"\u0000\u0015\u0001\u0000\u0000\u0000\u0000\u0017\u0001\u0000\u0000\u0000"+
		"\u0000\u0019\u0001\u0000\u0000\u0000\u0000\u001b\u0001\u0000\u0000\u0000"+
		"\u0000\u001d\u0001\u0000\u0000\u0000\u0000\u001f\u0001\u0000\u0000\u0000"+
		"\u0000!\u0001\u0000\u0000\u0000\u0000#\u0001\u0000\u0000\u0000\u0001%"+
		"\u0001\u0000\u0000\u0000\u0003\'\u0001\u0000\u0000\u0000\u0005)\u0001"+
		"\u0000\u0000\u0000\u0007+\u0001\u0000\u0000\u0000\t-\u0001\u0000\u0000"+
		"\u0000\u000b2\u0001\u0000\u0000\u0000\r5\u0001\u0000\u0000\u0000\u000f"+
		":\u0001\u0000\u0000\u0000\u0011?\u0001\u0000\u0000\u0000\u0013A\u0001"+
		"\u0000\u0000\u0000\u0015G\u0001\u0000\u0000\u0000\u0017W\u0001\u0000\u0000"+
		"\u0000\u0019Y\u0001\u0000\u0000\u0000\u001bt\u0001\u0000\u0000\u0000\u001d"+
		"w\u0001\u0000\u0000\u0000\u001f\u0083\u0001\u0000\u0000\u0000!\u0086\u0001"+
		"\u0000\u0000\u0000#\u00a0\u0001\u0000\u0000\u0000%&\u0005(\u0000\u0000"+
		"&\u0002\u0001\u0000\u0000\u0000\'(\u0005)\u0000\u0000(\u0004\u0001\u0000"+
		"\u0000\u0000)*\u0005,\u0000\u0000*\u0006\u0001\u0000\u0000\u0000+,\u0005"+
		"=\u0000\u0000,\b\u0001\u0000\u0000\u0000-.\u0007\u0000\u0000\u0000./\u0007"+
		"\u0001\u0000\u0000/0\u0007\u0002\u0000\u000001\u0007\u0003\u0000\u0000"+
		"1\n\u0001\u0000\u0000\u000023\u0007\u0004\u0000\u000034\u0007\u0001\u0000"+
		"\u00004\f\u0001\u0000\u0000\u000056\u0007\u0005\u0000\u000067\u0007\u0006"+
		"\u0000\u000078\u0007\u0001\u0000\u000089\u0007\u0007\u0000\u00009\u000e"+
		"\u0001\u0000\u0000\u0000:;\u0007\b\u0000\u0000;<\u0007\t\u0000\u0000<"+
		"=\u0007\u0004\u0000\u0000=>\u0007\n\u0000\u0000>\u0010\u0001\u0000\u0000"+
		"\u0000?@\u0005;\u0000\u0000@\u0012\u0001\u0000\u0000\u0000AB\u0007\u000b"+
		"\u0000\u0000BC\u0007\u0004\u0000\u0000CD\u0007\f\u0000\u0000DE\u0007\t"+
		"\u0000\u0000EF\u0007\r\u0000\u0000F\u0014\u0001\u0000\u0000\u0000GH\u0007"+
		"\u000b\u0000\u0000HI\u0007\u0004\u0000\u0000IJ\u0007\f\u0000\u0000JK\u0007"+
		"\u0001\u0000\u0000KL\u0007\u000e\u0000\u0000LM\u0007\u0004\u0000\u0000"+
		"M\u0016\u0001\u0000\u0000\u0000NO\u0007\u0004\u0000\u0000OP\u0007\u0006"+
		"\u0000\u0000PQ\u0007\u000e\u0000\u0000QX\u0007\u000f\u0000\u0000RS\u0007"+
		"\u0005\u0000\u0000ST\u0007\u0010\u0000\u0000TU\u0007\u0011\u0000\u0000"+
		"UV\u0007\u000b\u0000\u0000VX\u0007\u000f\u0000\u0000WN\u0001\u0000\u0000"+
		"\u0000WR\u0001\u0000\u0000\u0000X\u0018\u0001\u0000\u0000\u0000Y]\u0007"+
		"\u0012\u0000\u0000Z\\\u0007\u0013\u0000\u0000[Z\u0001\u0000\u0000\u0000"+
		"\\_\u0001\u0000\u0000\u0000][\u0001\u0000\u0000\u0000]^\u0001\u0000\u0000"+
		"\u0000^\u001a\u0001\u0000\u0000\u0000_]\u0001\u0000\u0000\u0000`f\u0005"+
		"\'\u0000\u0000ae\b\u0014\u0000\u0000bc\u0005\'\u0000\u0000ce\u0005\'\u0000"+
		"\u0000da\u0001\u0000\u0000\u0000db\u0001\u0000\u0000\u0000eh\u0001\u0000"+
		"\u0000\u0000fd\u0001\u0000\u0000\u0000fg\u0001\u0000\u0000\u0000gi\u0001"+
		"\u0000\u0000\u0000hf\u0001\u0000\u0000\u0000iu\u0005\'\u0000\u0000jp\u0005"+
		"\"\u0000\u0000ko\b\u0015\u0000\u0000lm\u0005\"\u0000\u0000mo\u0005\"\u0000"+
		"\u0000nk\u0001\u0000\u0000\u0000nl\u0001\u0000\u0000\u0000or\u0001\u0000"+
		"\u0000\u0000pn\u0001\u0000\u0000\u0000pq\u0001\u0000\u0000\u0000qs\u0001"+
		"\u0000\u0000\u0000rp\u0001\u0000\u0000\u0000su\u0005\"\u0000\u0000t`\u0001"+
		"\u0000\u0000\u0000tj\u0001\u0000\u0000\u0000u\u001c\u0001\u0000\u0000"+
		"\u0000vx\u0007\u0016\u0000\u0000wv\u0001\u0000\u0000\u0000xy\u0001\u0000"+
		"\u0000\u0000yw\u0001\u0000\u0000\u0000yz\u0001\u0000\u0000\u0000z\u0081"+
		"\u0001\u0000\u0000\u0000{}\u0005.\u0000\u0000|~\u0007\u0016\u0000\u0000"+
		"}|\u0001\u0000\u0000\u0000~\u007f\u0001\u0000\u0000\u0000\u007f}\u0001"+
		"\u0000\u0000\u0000\u007f\u0080\u0001\u0000\u0000\u0000\u0080\u0082\u0001"+
		"\u0000\u0000\u0000\u0081{\u0001\u0000\u0000\u0000\u0081\u0082\u0001\u0000"+
		"\u0000\u0000\u0082\u001e\u0001\u0000\u0000\u0000\u0083\u0084\u0005\u0000"+
		"\u0000\u0000\u0084 \u0001\u0000\u0000\u0000\u0085\u0087\u0007\u0017\u0000"+
		"\u0000\u0086\u0085\u0001\u0000\u0000\u0000\u0087\u0088\u0001\u0000\u0000"+
		"\u0000\u0088\u0086\u0001\u0000\u0000\u0000\u0088\u0089\u0001\u0000\u0000"+
		"\u0000\u0089\u008a\u0001\u0000\u0000\u0000\u008a\u008b\u0006\u0010\u0000"+
		"\u0000\u008b\"\u0001\u0000\u0000\u0000\u008c\u008d\u0005-\u0000\u0000"+
		"\u008d\u008e\u0005-\u0000\u0000\u008e\u0092\u0001\u0000\u0000\u0000\u008f"+
		"\u0091\b\u0018\u0000\u0000\u0090\u008f\u0001\u0000\u0000\u0000\u0091\u0094"+
		"\u0001\u0000\u0000\u0000\u0092\u0090\u0001\u0000\u0000\u0000\u0092\u0093"+
		"\u0001\u0000\u0000\u0000\u0093\u00a1\u0001\u0000\u0000\u0000\u0094\u0092"+
		"\u0001\u0000\u0000\u0000\u0095\u0096\u0005/\u0000\u0000\u0096\u0097\u0005"+
		"*\u0000\u0000\u0097\u009b\u0001\u0000\u0000\u0000\u0098\u009a\t\u0000"+
		"\u0000\u0000\u0099\u0098\u0001\u0000\u0000\u0000\u009a\u009d\u0001\u0000"+
		"\u0000\u0000\u009b\u009c\u0001\u0000\u0000\u0000\u009b\u0099\u0001\u0000"+
		"\u0000\u0000\u009c\u009e\u0001\u0000\u0000\u0000\u009d\u009b\u0001\u0000"+
		"\u0000\u0000\u009e\u009f\u0005*\u0000\u0000\u009f\u00a1\u0005/\u0000\u0000"+
		"\u00a0\u008c\u0001\u0000\u0000\u0000\u00a0\u0095\u0001\u0000\u0000\u0000"+
		"\u00a1\u00a2\u0001\u0000\u0000\u0000\u00a2\u00a3\u0006\u0011\u0001\u0000"+
		"\u00a3$\u0001\u0000\u0000\u0000\u000f\u0000W]dfnpty\u007f\u0081\u0088"+
		"\u0092\u009b\u00a0\u0002\u0006\u0000\u0000\u0000\u0001\u0000";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}