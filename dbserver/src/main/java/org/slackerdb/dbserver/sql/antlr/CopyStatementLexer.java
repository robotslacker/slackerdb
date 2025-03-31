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
		IDENTIFIER=10, STRING_LITERAL=11, NUMBER=12, BOOLEAN=13, WS=14, Comment=15;
	public static String[] channelNames = {
		"DEFAULT_TOKEN_CHANNEL", "HIDDEN"
	};

	public static String[] modeNames = {
		"DEFAULT_MODE"
	};

	private static String[] makeRuleNames() {
		return new String[] {
			"T__0", "T__1", "T__2", "T__3", "COPY", "TO", "FROM", "WITH", "SColon", 
			"IDENTIFIER", "STRING_LITERAL", "NUMBER", "BOOLEAN", "WS", "Comment"
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
		"\u0004\u0000\u000f\u008f\u0006\uffff\uffff\u0002\u0000\u0007\u0000\u0002"+
		"\u0001\u0007\u0001\u0002\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002"+
		"\u0004\u0007\u0004\u0002\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002"+
		"\u0007\u0007\u0007\u0002\b\u0007\b\u0002\t\u0007\t\u0002\n\u0007\n\u0002"+
		"\u000b\u0007\u000b\u0002\f\u0007\f\u0002\r\u0007\r\u0002\u000e\u0007\u000e"+
		"\u0001\u0000\u0001\u0000\u0001\u0001\u0001\u0001\u0001\u0002\u0001\u0002"+
		"\u0001\u0003\u0001\u0003\u0001\u0004\u0001\u0004\u0001\u0004\u0001\u0004"+
		"\u0001\u0004\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0006\u0001\u0006"+
		"\u0001\u0006\u0001\u0006\u0001\u0006\u0001\u0007\u0001\u0007\u0001\u0007"+
		"\u0001\u0007\u0001\u0007\u0001\b\u0001\b\u0001\t\u0001\t\u0005\t>\b\t"+
		"\n\t\f\tA\t\t\u0001\n\u0001\n\u0001\n\u0001\n\u0005\nG\b\n\n\n\f\nJ\t"+
		"\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0005\nQ\b\n\n\n\f\nT\t\n\u0001"+
		"\n\u0003\nW\b\n\u0001\u000b\u0004\u000bZ\b\u000b\u000b\u000b\f\u000b["+
		"\u0001\u000b\u0001\u000b\u0004\u000b`\b\u000b\u000b\u000b\f\u000ba\u0003"+
		"\u000bd\b\u000b\u0001\f\u0001\f\u0001\f\u0001\f\u0001\f\u0001\f\u0001"+
		"\f\u0001\f\u0001\f\u0003\fo\b\f\u0001\r\u0004\rr\b\r\u000b\r\f\rs\u0001"+
		"\r\u0001\r\u0001\u000e\u0001\u000e\u0001\u000e\u0001\u000e\u0005\u000e"+
		"|\b\u000e\n\u000e\f\u000e\u007f\t\u000e\u0001\u000e\u0001\u000e\u0001"+
		"\u000e\u0001\u000e\u0005\u000e\u0085\b\u000e\n\u000e\f\u000e\u0088\t\u000e"+
		"\u0001\u000e\u0001\u000e\u0003\u000e\u008c\b\u000e\u0001\u000e\u0001\u000e"+
		"\u0001\u0086\u0000\u000f\u0001\u0001\u0003\u0002\u0005\u0003\u0007\u0004"+
		"\t\u0005\u000b\u0006\r\u0007\u000f\b\u0011\t\u0013\n\u0015\u000b\u0017"+
		"\f\u0019\r\u001b\u000e\u001d\u000f\u0001\u0000\u0017\u0002\u0000CCcc\u0002"+
		"\u0000OOoo\u0002\u0000PPpp\u0002\u0000YYyy\u0002\u0000TTtt\u0002\u0000"+
		"FFff\u0002\u0000RRrr\u0002\u0000MMmm\u0002\u0000WWww\u0002\u0000IIii\u0002"+
		"\u0000HHhh\u0003\u0000AZ__az\u0005\u0000..09AZ__az\u0001\u0000\'\'\u0001"+
		"\u0000\"\"\u0001\u000009\u0002\u0000UUuu\u0002\u0000EEee\u0002\u0000A"+
		"Aaa\u0002\u0000LLll\u0002\u0000SSss\u0003\u0000\t\n\r\r  \u0002\u0000"+
		"\n\n\r\r\u009c\u0000\u0001\u0001\u0000\u0000\u0000\u0000\u0003\u0001\u0000"+
		"\u0000\u0000\u0000\u0005\u0001\u0000\u0000\u0000\u0000\u0007\u0001\u0000"+
		"\u0000\u0000\u0000\t\u0001\u0000\u0000\u0000\u0000\u000b\u0001\u0000\u0000"+
		"\u0000\u0000\r\u0001\u0000\u0000\u0000\u0000\u000f\u0001\u0000\u0000\u0000"+
		"\u0000\u0011\u0001\u0000\u0000\u0000\u0000\u0013\u0001\u0000\u0000\u0000"+
		"\u0000\u0015\u0001\u0000\u0000\u0000\u0000\u0017\u0001\u0000\u0000\u0000"+
		"\u0000\u0019\u0001\u0000\u0000\u0000\u0000\u001b\u0001\u0000\u0000\u0000"+
		"\u0000\u001d\u0001\u0000\u0000\u0000\u0001\u001f\u0001\u0000\u0000\u0000"+
		"\u0003!\u0001\u0000\u0000\u0000\u0005#\u0001\u0000\u0000\u0000\u0007%"+
		"\u0001\u0000\u0000\u0000\t\'\u0001\u0000\u0000\u0000\u000b,\u0001\u0000"+
		"\u0000\u0000\r/\u0001\u0000\u0000\u0000\u000f4\u0001\u0000\u0000\u0000"+
		"\u00119\u0001\u0000\u0000\u0000\u0013;\u0001\u0000\u0000\u0000\u0015V"+
		"\u0001\u0000\u0000\u0000\u0017Y\u0001\u0000\u0000\u0000\u0019n\u0001\u0000"+
		"\u0000\u0000\u001bq\u0001\u0000\u0000\u0000\u001d\u008b\u0001\u0000\u0000"+
		"\u0000\u001f \u0005(\u0000\u0000 \u0002\u0001\u0000\u0000\u0000!\"\u0005"+
		")\u0000\u0000\"\u0004\u0001\u0000\u0000\u0000#$\u0005=\u0000\u0000$\u0006"+
		"\u0001\u0000\u0000\u0000%&\u0005,\u0000\u0000&\b\u0001\u0000\u0000\u0000"+
		"\'(\u0007\u0000\u0000\u0000()\u0007\u0001\u0000\u0000)*\u0007\u0002\u0000"+
		"\u0000*+\u0007\u0003\u0000\u0000+\n\u0001\u0000\u0000\u0000,-\u0007\u0004"+
		"\u0000\u0000-.\u0007\u0001\u0000\u0000.\f\u0001\u0000\u0000\u0000/0\u0007"+
		"\u0005\u0000\u000001\u0007\u0006\u0000\u000012\u0007\u0001\u0000\u0000"+
		"23\u0007\u0007\u0000\u00003\u000e\u0001\u0000\u0000\u000045\u0007\b\u0000"+
		"\u000056\u0007\t\u0000\u000067\u0007\u0004\u0000\u000078\u0007\n\u0000"+
		"\u00008\u0010\u0001\u0000\u0000\u00009:\u0005;\u0000\u0000:\u0012\u0001"+
		"\u0000\u0000\u0000;?\u0007\u000b\u0000\u0000<>\u0007\f\u0000\u0000=<\u0001"+
		"\u0000\u0000\u0000>A\u0001\u0000\u0000\u0000?=\u0001\u0000\u0000\u0000"+
		"?@\u0001\u0000\u0000\u0000@\u0014\u0001\u0000\u0000\u0000A?\u0001\u0000"+
		"\u0000\u0000BH\u0005\'\u0000\u0000CG\b\r\u0000\u0000DE\u0005\'\u0000\u0000"+
		"EG\u0005\'\u0000\u0000FC\u0001\u0000\u0000\u0000FD\u0001\u0000\u0000\u0000"+
		"GJ\u0001\u0000\u0000\u0000HF\u0001\u0000\u0000\u0000HI\u0001\u0000\u0000"+
		"\u0000IK\u0001\u0000\u0000\u0000JH\u0001\u0000\u0000\u0000KW\u0005\'\u0000"+
		"\u0000LR\u0005\"\u0000\u0000MQ\b\u000e\u0000\u0000NO\u0005\"\u0000\u0000"+
		"OQ\u0005\"\u0000\u0000PM\u0001\u0000\u0000\u0000PN\u0001\u0000\u0000\u0000"+
		"QT\u0001\u0000\u0000\u0000RP\u0001\u0000\u0000\u0000RS\u0001\u0000\u0000"+
		"\u0000SU\u0001\u0000\u0000\u0000TR\u0001\u0000\u0000\u0000UW\u0005\"\u0000"+
		"\u0000VB\u0001\u0000\u0000\u0000VL\u0001\u0000\u0000\u0000W\u0016\u0001"+
		"\u0000\u0000\u0000XZ\u0007\u000f\u0000\u0000YX\u0001\u0000\u0000\u0000"+
		"Z[\u0001\u0000\u0000\u0000[Y\u0001\u0000\u0000\u0000[\\\u0001\u0000\u0000"+
		"\u0000\\c\u0001\u0000\u0000\u0000]_\u0005.\u0000\u0000^`\u0007\u000f\u0000"+
		"\u0000_^\u0001\u0000\u0000\u0000`a\u0001\u0000\u0000\u0000a_\u0001\u0000"+
		"\u0000\u0000ab\u0001\u0000\u0000\u0000bd\u0001\u0000\u0000\u0000c]\u0001"+
		"\u0000\u0000\u0000cd\u0001\u0000\u0000\u0000d\u0018\u0001\u0000\u0000"+
		"\u0000ef\u0007\u0004\u0000\u0000fg\u0007\u0006\u0000\u0000gh\u0007\u0010"+
		"\u0000\u0000ho\u0007\u0011\u0000\u0000ij\u0007\u0005\u0000\u0000jk\u0007"+
		"\u0012\u0000\u0000kl\u0007\u0013\u0000\u0000lm\u0007\u0014\u0000\u0000"+
		"mo\u0007\u0011\u0000\u0000ne\u0001\u0000\u0000\u0000ni\u0001\u0000\u0000"+
		"\u0000o\u001a\u0001\u0000\u0000\u0000pr\u0007\u0015\u0000\u0000qp\u0001"+
		"\u0000\u0000\u0000rs\u0001\u0000\u0000\u0000sq\u0001\u0000\u0000\u0000"+
		"st\u0001\u0000\u0000\u0000tu\u0001\u0000\u0000\u0000uv\u0006\r\u0000\u0000"+
		"v\u001c\u0001\u0000\u0000\u0000wx\u0005-\u0000\u0000xy\u0005-\u0000\u0000"+
		"y}\u0001\u0000\u0000\u0000z|\b\u0016\u0000\u0000{z\u0001\u0000\u0000\u0000"+
		"|\u007f\u0001\u0000\u0000\u0000}{\u0001\u0000\u0000\u0000}~\u0001\u0000"+
		"\u0000\u0000~\u008c\u0001\u0000\u0000\u0000\u007f}\u0001\u0000\u0000\u0000"+
		"\u0080\u0081\u0005/\u0000\u0000\u0081\u0082\u0005*\u0000\u0000\u0082\u0086"+
		"\u0001\u0000\u0000\u0000\u0083\u0085\t\u0000\u0000\u0000\u0084\u0083\u0001"+
		"\u0000\u0000\u0000\u0085\u0088\u0001\u0000\u0000\u0000\u0086\u0087\u0001"+
		"\u0000\u0000\u0000\u0086\u0084\u0001\u0000\u0000\u0000\u0087\u0089\u0001"+
		"\u0000\u0000\u0000\u0088\u0086\u0001\u0000\u0000\u0000\u0089\u008a\u0005"+
		"*\u0000\u0000\u008a\u008c\u0005/\u0000\u0000\u008bw\u0001\u0000\u0000"+
		"\u0000\u008b\u0080\u0001\u0000\u0000\u0000\u008c\u008d\u0001\u0000\u0000"+
		"\u0000\u008d\u008e\u0006\u000e\u0001\u0000\u008e\u001e\u0001\u0000\u0000"+
		"\u0000\u000f\u0000?FHPRV[acns}\u0086\u008b\u0002\u0006\u0000\u0000\u0000"+
		"\u0001\u0000";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}