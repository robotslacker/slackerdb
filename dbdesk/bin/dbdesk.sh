#!/bin/bash

# switch to the distribution root (bin/..)
cd "$(dirname "$0")/.." || exit 1

if [ -n "$SLACKERDB_JAVA_HOME" ]; then
    _SLACKERDB_JAVA="$SLACKERDB_JAVA_HOME/bin/java"
elif [ -n "$JAVA_HOME" ]; then
    _SLACKERDB_JAVA="$JAVA_HOME/bin/java"
else
    _SLACKERDB_JAVA="java"
fi

# DbDesk is an independent management UI; it does not start or manage dbserver.
exec "$_SLACKERDB_JAVA" -jar "jlib/slackerdb-dbdesk-0.2.2-standalone.jar" "$@"
