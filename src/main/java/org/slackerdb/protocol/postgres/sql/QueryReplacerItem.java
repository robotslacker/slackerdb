package org.slackerdb.protocol.postgres.sql;

public class QueryReplacerItem {
    private String toFind;
    private String toReplace;
    private boolean regex;
    private boolean sampleReplace;

    public QueryReplacerItem(String toFind, String toReplace, boolean regex, boolean sampleReplace) {
        this.toFind = toFind;
        this.toReplace = toReplace;
        this.regex = regex;
        this.sampleReplace = sampleReplace;

    }

    public String getToFind() {
        return toFind;
    }

    public String getToReplace() {
        return toReplace;
    }

    public boolean isRegex() {
        return regex;
    }

    public boolean isSampleReplace() {
        return sampleReplace;
    }

}
