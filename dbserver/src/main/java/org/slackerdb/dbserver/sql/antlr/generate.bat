del *.interp
del *.tokens
del CopyStatementBaseVisitor.java
del CopyStatementLexer.java
del CopyStatementParser.java
del CopyStatementVisitor.java
java -jar antlr-4.13.2-complete.jar -package org.slackerdb.dbserver.sql.antlr -visitor -Dlanguage=Java -no-listener CopyStatement.g4