/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.util;

import org.slackerdb.jdbc.geometric.*;
import org.slackerdb.jdbc.geometric.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class is used to tokenize the text output of org.postgres. It's mainly used by the geometric
 * classes, but is useful in parsing any output from custom data types output from org.postgresql.
 *
 * @see PGbox
 * @see PGcircle
 * @see PGlseg
 * @see PGpath
 * @see PGpoint
 * @see PGpolygon
 */
public class PGtokenizer {

  private static final Map<Character, Character> CLOSING_TO_OPENING_CHARACTER = new HashMap<>();

  static 	{
    CLOSING_TO_OPENING_CHARACTER.put(')', '(');

    CLOSING_TO_OPENING_CHARACTER.put(']', '[');

    CLOSING_TO_OPENING_CHARACTER.put('>', '<');

    CLOSING_TO_OPENING_CHARACTER.put('"', '"');
  }

  // Our tokens
  protected List<String> tokens = new ArrayList<>();

  /**
   * Create a tokeniser.
   *
   * <p>We could have used StringTokenizer to do this, however, we needed to handle nesting of '(' ')'
   * '[' ']' '&lt;' and '&gt;' as these are used by the geometric data types.</p>
   *
   * @param string containing tokens
   * @param delim single character to split the tokens
   */
  @SuppressWarnings("method.invocation")
  public PGtokenizer(String string, char delim) {
    tokenize(string, delim);
  }

  /**
   * This resets this tokenizer with a new string and/or delimiter.
   *
   * @param string containing tokens
   * @param delim single character to split the tokens
   * @return number of tokens
   */
  public int tokenize(String string, char delim) {
    tokens.clear();

    final Deque<Character> stack = new ArrayDeque<>();

    // stack keeps track of the levels we are in the current token.
    // if stack.size is > 0 then we don't split a token when delim is matched.
    //
    // The Geometric datatypes use this, because often a type may have others
    // (usually PGpoint) embedded within a token.
    //
    // Peter 1998 Jan 6 - Added < and > to the nesting rules
    int p;
    int s;
    boolean skipChar = false;
    boolean nestedDoubleQuote = false;
    char c = (char) 0;
    for (p = 0, s = 0; p < string.length(); p++) {
      c = string.charAt(p);

      // increase nesting if an open character is found
      if (c == '(' || c == '[' || c == '<' || (!nestedDoubleQuote && !skipChar && c == '"')) {
        stack.push(c);
        if (c == '"') {
          nestedDoubleQuote = true;
          skipChar = true;
        }
      }

      // decrease nesting if a close character is found
      if (c == ')' || c == ']' || c == '>' || (nestedDoubleQuote && !skipChar && c == '"')) {

        if (c == '"') {
          while (!stack.isEmpty() && !Character.valueOf('"').equals(stack.peek())) {
            stack.pop();
          }
          nestedDoubleQuote = false;
          stack.pop();
        } else {
          final Character ch = CLOSING_TO_OPENING_CHARACTER.get(c);
          if (!stack.isEmpty() && ch != null && ch.equals(stack.peek())) {
            stack.pop();
          }
        }
      }

      skipChar = c == '\\';

      if (stack.isEmpty() && c == delim) {
        tokens.add(string.substring(s, p));
        s = p + 1; // +1 to skip the delimiter
      }

    }

    // Don't forget the last token ;-)
    if (s < string.length()) {
      tokens.add(string.substring(s));
    }

    // check for last token empty
    if ( s == string.length() && c == delim) {
      tokens.add("");
    }

    return tokens.size();
  }

  /**
   * @return the number of tokens available
   */
  public int getSize() {
    return tokens.size();
  }

  /**
   * @param n Token number ( 0 ... getSize()-1 )
   * @return The token value
   */
  public String getToken(int n) {
    return tokens.get(n);
  }

  /**
   * This returns a new tokenizer based on one of our tokens.
   *
   * <p>The geometric datatypes use this to process nested tokens (usually PGpoint).</p>
   *
   * @param n Token number ( 0 ... getSize()-1 )
   * @param delim The delimiter to use
   * @return A new instance of PGtokenizer based on the token
   */
  public PGtokenizer tokenizeToken(int n, char delim) {
    return new PGtokenizer(getToken(n), delim);
  }

  /**
   * This removes the lead/trailing strings from a string.
   *
   * @param s Source string
   * @param l Leading string to remove
   * @param t Trailing string to remove
   * @return String without the lead/trailing strings
   */
  public static String remove(String s, String l, String t) {
    if (s.startsWith(l)) {
      s = s.substring(l.length());
    }
    if (s.endsWith(t)) {
      s = s.substring(0, s.length() - t.length());
    }
    return s;
  }

  /**
   * This removes the lead/trailing strings from all tokens.
   *
   * @param l Leading string to remove
   * @param t Trailing string to remove
   */
  public void remove(String l, String t) {
    for (int i = 0; i < tokens.size(); i++) {
      tokens.set(i, remove(tokens.get(i), l, t));
    }
  }

  /**
   * Removes ( and ) from the beginning and end of a string.
   *
   * @param s String to remove from
   * @return String without the ( or )
   */
  public static String removePara(String s) {
    return remove(s, "(", ")");
  }

  /**
   * Removes ( and ) from the beginning and end of all tokens.
   */
  public void removePara() {
    remove("(", ")");
  }

  /**
   * Removes [ and ] from the beginning and end of a string.
   *
   * @param s String to remove from
   * @return String without the [ or ]
   */
  public static String removeBox(String s) {
    return remove(s, "[", "]");
  }

  /**
   * Removes [ and ] from the beginning and end of all tokens.
   */
  public void removeBox() {
    remove("[", "]");
  }

  /**
   * Removes &lt; and &gt; from the beginning and end of a string.
   *
   * @param s String to remove from
   * @return String without the &lt; or &gt;
   */
  public static String removeAngle(String s) {
    return remove(s, "<", ">");
  }

  /**
   * Removes &lt; and &gt; from the beginning and end of all tokens.
   */
  public void removeAngle() {
    remove("<", ">");
  }

  /**
   * Removes curly braces { and } from the beginning and end of a string.
   *
   * @param s String to remove from
   * @return String without the { or }
   */
  public static String removeCurlyBrace(String s) {
    return remove(s, "{", "}");
  }

  /**
   * Removes &lt; and &gt; from the beginning and end of all tokens.
   */
  public void removeCurlyBrace() {
    remove("{", "}");
  }

}
