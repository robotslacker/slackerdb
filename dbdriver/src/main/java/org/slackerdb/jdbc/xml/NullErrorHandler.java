/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.xml;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * Error handler that silently suppresses all errors.
 */
public class NullErrorHandler implements ErrorHandler {
  public static final NullErrorHandler INSTANCE = new NullErrorHandler();

  @Override
  public void error(SAXParseException e) {
  }

  @Override
  public void fatalError(SAXParseException e) {
  }

  @Override
  public void warning(SAXParseException e) {
  }
}
