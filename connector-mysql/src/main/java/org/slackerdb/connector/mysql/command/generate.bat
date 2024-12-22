del *.interp
del *.tokens
del MysqlConnectorSyntax.java
del MysqlConnectorSyntax.java
del MysqlConnectorSyntax.java
del MysqlConnectorSyntax.java

java -jar antlr-4.13.2-complete.jar -package org.slackerdb.connector.mysql.command -visitor -Dlanguage=Java -no-listener MysqlConnectorSyntax.g4

