package org.slackerdb.dbserver.sql;

public record QueryReplacerItem(String toFind, String toReplace, boolean regex, boolean sampleReplace) {

}
