package org.slackerdb.common.utils;

public class StringUtil {
    public static String[] splitString(String source, char delimiter)
    {
        return source.split(delimiter + "(?=(?:[^']*'[^']*')*[^']*$)(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
   }
}
