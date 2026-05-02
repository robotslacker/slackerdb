package org.slackerdb.common.utils;

/**
 * 字符串处理工具类。
 * 提供字符串处理的实用方法，如分隔字符串同时考虑引号内的内容。
 */
public class StringUtil {
    /**
     * 分割字符串，同时考虑引号内的分隔符。
     * 该方法使用正则表达式分割字符串，但会忽略单引号和双引号内的分隔符。
     *
     * @param source 要分割的源字符串
     * @param delimiter 分隔符字符
     * @return 分割后的字符串数组
     */
    public static String[] splitString(String source, char delimiter)
    {
        return source.split(delimiter + "(?=(?:[^']*'[^']*')*[^']*$)(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
   }
}
