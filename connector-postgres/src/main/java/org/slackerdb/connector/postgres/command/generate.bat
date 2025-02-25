del *.interp
del *.tokens
del PostgresConnectorSyntax.java

java -jar antlr-4.13.2-complete.jar -package org.slackerdb.connector.postgres.command -visitor -Dlanguage=Java -no-listener PostgresConnectorSyntax.g4

