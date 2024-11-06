del *.interp
del *.tokens
del PlSqlParserBaseVisitor.java
del PlSqlParserLexer.java
del PlSqlParserParser.java
del PlSqlParserVisitor.java
java -jar antlr-4.13.2-complete.jar -package org.slackerdb.plsql -visitor -Dlanguage=Java -no-listener PlSqlParser.g4

