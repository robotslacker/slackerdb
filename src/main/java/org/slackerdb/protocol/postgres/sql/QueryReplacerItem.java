package org.slackerdb.protocol.postgres.sql;

public class QueryReplacerItem {
    private String toFind;
    private String toReplace;
    private boolean regex;

    public QueryReplacerItem(String toFind, String toReplace, boolean regex) {
        this.toFind = toFind;
        this.toReplace = toReplace;
        this.regex = regex;
    }

    public String getToFind() {
        return toFind;
    }

    public void setToFind(String toFind) {
        this.toFind = toFind;
    }

    public String getToReplace() {
        return toReplace;
    }

    public void setToReplace(String toReplace) {
        this.toReplace = toReplace;
    }

    public boolean isRegex() {
        return regex;
    }

    public void setRegex(boolean regex) {
        this.regex = regex;
    }
}
