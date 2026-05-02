package org.slackerdb.plugins.dbsyncer;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.SetStatement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.create.table.NamedConstraint;

import java.util.List;
import java.util.ArrayList;

public class SQLConvert {

    /**
     * 转换MySQL脚本到DuckDB兼容的语句
     * @param sql MySQL DDL语句
     * @return 转换后的DuckDB DDL语句，如果无法转换则返回原始语句
     */
    public static String convertMysqlScript(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return sql;
        }
        
        String trimmedSql = sql.trim();
        String upperSql = trimmedSql.toUpperCase();
        
        // 特殊处理：CREATE DATABASE -> CREATE SCHEMA
        if (upperSql.startsWith("CREATE DATABASE")) {
            // 将CREATE DATABASE替换为CREATE SCHEMA
            String result = trimmedSql.replaceFirst("(?i)CREATE DATABASE", "CREATE SCHEMA");
            // 移除CHARSET和COLLATE选项（DuckDB可能不支持）
            result = result.replaceAll("(?i)\\s*CHARSET\\s+\\w+", "");
            result = result.replaceAll("(?i)\\s*COLLATE\\s+\\w+", "");
            // 清理标识符中的反引号
            result = cleanMySQLIdentifierInDropSchema(result);
            // 清理多余的空格
            result = result.replaceAll("\\s+", " ").trim();
            return result;
        }
        
        // 特殊处理：USE database -> 移除反引号
        if (upperSql.startsWith("USE ")) {
            // 移除反引号
            return cleanMySQLIdentifierInDropSchema(trimmedSql);
        }
        
        // 特殊处理：DROP DATABASE -> DROP SCHEMA
        if (upperSql.startsWith("DROP DATABASE")) {
            // 将DROP DATABASE替换为DROP SCHEMA
            String result = trimmedSql.replaceFirst("(?i)DROP DATABASE", "DROP SCHEMA");
            // 清理标识符中的反引号
            result = cleanMySQLIdentifierInDropSchema(result);
            return result;
        }
        
        try {
            // 使用JSQLParser解析SQL语句
            Statement statement = CCJSqlParserUtil.parse(trimmedSql);
            
            // 根据语句类型进行转换
            if (statement instanceof SetStatement) {
                // SET语句直接忽略，返回空字符
                return "";
            } else if (statement instanceof CreateTable) {
                return convertMySQLCreateTable((CreateTable) statement);
            } else if (statement instanceof Alter) {
                return convertMySQLAlterTable((Alter) statement);
            } else if (statement instanceof Drop) {
                return convertMySQLDropTable((Drop) statement);
            } else if (statement instanceof Truncate) {
                return convertMySQLTruncateTable((Truncate) statement);
            } else {
                // 其他语句类型（SELECT, INSERT, UPDATE, DELETE等），返回原始语句
                return trimmedSql;
            }
            
        } catch (JSQLParserException e) {
            // 解析失败，返回原始语句
            return trimmedSql;
        } catch (Exception e) {
            // 其他异常，返回原始语句
            return trimmedSql;
        }
    }
    
    /**
     * 转换PostgreSQL脚本（目前按照完全原样SQL返回，不做转换）
     * @param sql PostgreSQL DDL语句
     * @return 原始SQL语句，不做任何转换
     */
    public static String convertPGScript(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return sql;
        }
        return sql.trim();
    }
    
    /**
     * 清理标识符引用（移除反引号或转换为双引号
     */
    private static String cleanMySQLIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return identifier;
        }
        
        // 如果标识符被反引号包围，移除反引号
        String cleaned;
        if (identifier.startsWith("`") && identifier.endsWith("`")) {
            cleaned = identifier.substring(1, identifier.length() - 1);
        } else {
            cleaned = identifier;
        }
        
        // 检查是否为DuckDB关键字，如果是则用双引号包围
        if (isDuckDBKeyword(cleaned)) {
            return "\"" + cleaned + "\"";
        }
        
        return cleaned;
    }
    
    /**
     * 检查字符串是否为DuckDB关键字
     */
    private static boolean isDuckDBKeyword(String identifier) {
        if (identifier == null) {
            return false;
        }
        
        // DuckDB关键字列表（部分常见关键字）
        // 参考：https://duckdb.org/docs/sql/introduction#keywords
        String[] keywords = {
            "ALL", "ALTER", "AND", "AS", "ASC", "BETWEEN", "BY", "CASE", "CAST",
            "CHECK", "COLUMN", "CONSTRAINT", "CREATE", "CROSS", "CURRENT_DATE",
            "CURRENT_TIME", "CURRENT_TIMESTAMP", "DATABASE", "DEFAULT", "DELETE",
            "DESC", "DISTINCT", "DROP", "ELSE", "END", "ESCAPE", "EXCEPT",
            "EXISTS", "EXTRACT", "FALSE", "FOR", "FOREIGN", "FROM", "FULL",
            "GROUP", "HAVING", "IF", "IN", "INNER", "INSERT", "INTERSECT",
            "INTO", "IS", "JOIN", "LEFT", "LIKE", "LIMIT", "NOT", "NULL",
            "ON", "OR", "ORDER", "OUTER", "PRIMARY", "REFERENCES", "RIGHT",
            "SELECT", "SET", "SOME", "TABLE", "THEN", "TO", "TRUE", "UNION",
            "UNIQUE", "UPDATE", "USING", "VALUES", "WHEN", "WHERE", "WITH"
        };
        
        String upper = identifier.toUpperCase();
        for (String keyword : keywords) {
            if (keyword.equals(upper)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 清理DROP SCHEMA语句中的标识符
     */
    private static String cleanMySQLIdentifierInDropSchema(String sql) {
        // 简单的实现：移除所有反引号
        return sql.replace("`", "");
    }
    
    /**
     * 转换CREATE TABLE语句
     */
    private static String convertMySQLCreateTable(CreateTable createTable) {
        StringBuilder result = new StringBuilder();
        
        // 添加CREATE TABLE [IF NOT EXISTS] 表名
        result.append("CREATE TABLE ");
        if (createTable.isIfNotExists()) {
            result.append("IF NOT EXISTS ");
        }
        result.append(cleanMySQLIdentifier(createTable.getTable().getName())).append(" (\n");
        
        // 处理列定�?
        List<String> columnDefs = new ArrayList<>();
        List<String> primaryKeyColumns = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        
        for (ColumnDefinition colDef : createTable.getColumnDefinitions()) {
            StringBuilder colBuilder = new StringBuilder();
            
            // 列名
            colBuilder.append(cleanMySQLIdentifier(colDef.getColumnName()));
            
            // 数据类型
            ColDataType dataType = colDef.getColDataType();
            String mysqlType = dataType != null ? dataType.getDataType() : "VARCHAR";
            String duckdbType = mapMySQLTypeToDuckDB(mysqlType);
            colBuilder.append(" ").append(duckdbType);
            
            // 列约�?- 更复杂的处理以保留DEFAULT
            List<String> colSpecs = colDef.getColumnSpecs();
            if (colSpecs != null && !colSpecs.isEmpty()) {
                // 使用索引遍历以便处理多标记约束
                for (int i = 0; i < colSpecs.size(); i++) {
                    String spec = colSpecs.get(i);
                    String upperSpec = spec.toUpperCase();
                    
                    if (upperSpec.contains("NOT NULL")) {
                        colBuilder.append(" NOT NULL");
                    } else if (upperSpec.equals("PRIMARY")) {
                        // 检查下一个标记是否是"KEY"
                        if (i + 1 < colSpecs.size() && colSpecs.get(i + 1).equalsIgnoreCase("KEY")) {
                            // 列级主键
                            primaryKeyColumns.add(colDef.getColumnName());
                            i++; // 跳过"KEY"标记
                        }
                    } else if (upperSpec.contains("UNIQUE")) {
                        colBuilder.append(" UNIQUE");
                    } else if (upperSpec.equals("DEFAULT")) {
                        // DEFAULT约束，需要获取�?
                        StringBuilder defaultExpr = new StringBuilder(" DEFAULT");
                        i++; // 移动到�?
                        while (i < colSpecs.size()) {
                            String next = colSpecs.get(i);
                            String upperNext = next.toUpperCase();
                            // 如果下一个标记是另一个约束的关键字，停止
                            if (upperNext.contains("NOT NULL") || upperNext.equals("PRIMARY") ||
                                upperNext.contains("UNIQUE") || upperNext.contains("AUTO_INCREMENT") ||
                                upperNext.contains("COMMENT") || upperNext.equals("ON")) {
                                i--; // 回退一步，让外层循环处理这个约�?
                                break;
                            }
                            // 转换CURRENT_TIMESTAMP为DuckDB兼容的current_timestamp
                            String convertedNext = next;
                            if (next.equalsIgnoreCase("CURRENT_TIMESTAMP") || next.toUpperCase().startsWith("CURRENT_TIMESTAMP(")) {
                                // 处理CURRENT_TIMESTAMP和CURRENT_TIMESTAMP(6)等变�?
                                convertedNext = "current_timestamp";
                                
                                // 如果当前标记�?开头，或者下一个标记以(开头，需要跳过精度参�?
                                // 处理CURRENT_TIMESTAMP(6)作为单个标记的情�?
                                if (next.toUpperCase().startsWith("CURRENT_TIMESTAMP(")) {
                                    // 已经是CURRENT_TIMESTAMP(6)格式，不需要额外处�?
                                    // 精度参数已经包含在标记中，我们直接忽略它
                                } else {
                                    // 处理CURRENT_TIMESTAMP (6)有空格的情况
                                    // 跳过所有后续的精度参数标记，直到遇到
                                    int j = i + 1;
                                    while (j < colSpecs.size()) {
                                        String nextToken = colSpecs.get(j);
                                        if (nextToken.startsWith("(")) {
                                            // 跳过开括号
                                            j++;
                                            // 继续跳过直到遇到闭括号或数字
                                            while (j < colSpecs.size()) {
                                                String innerToken = colSpecs.get(j);
                                                if (innerToken.contains(")")) {
                                                    j++; // 跳过闭括�?
                                                    break;
                                                }
                                                j++; // 跳过数字或其他内容
                                            }
                                            i = j - 1; // 更新i以跳过所有精度参数
                                            break;
                                        } else {
                                            // 不是精度参数，停止
                                            break;
                                        }
                                    }
                                }
                            }
                            defaultExpr.append(" ").append(convertedNext);
                            i++;
                        }
                        colBuilder.append(defaultExpr);
                    } else if (upperSpec.contains("COMMENT")) {
                        // COMMENT约束，需要跳过整个注释�?
                        // COMMENT后面通常跟着字符串值，COMMENT '用户'
                        i++; // 移动到注释
                        // 继续跳过直到遇到下一个约束或结束
                        while (i < colSpecs.size()) {
                            String next = colSpecs.get(i);
                            String upperNext = next.toUpperCase();
                            // 如果下一个标记是另一个约束的关键字，停止
                            if (upperNext.contains("NOT NULL") || upperNext.equals("PRIMARY") ||
                                upperNext.contains("UNIQUE") || upperNext.contains("AUTO_INCREMENT") ||
                                upperNext.contains("DEFAULT") || upperNext.equals("ON")) {
                                i--; // 回退一步，让外层循环处理这个约束
                                break;
                            }
                            // 否则继续跳过注释
                            i++;
                        }
                    } else if (upperSpec.equals("ON")) {
                        // 检查下一个标记是否是"UPDATE"
                        if (i + 1 < colSpecs.size() && colSpecs.get(i + 1).equalsIgnoreCase("UPDATE")) {
                            // ON UPDATE约束，需要跳过整个ON UPDATE CURRENT_TIMESTAMP
                            i++; // 跳过"UPDATE"标记
                            i++; // 移动到值（CURRENT_TIMESTAMP
                            // 继续跳过直到遇到下一个约束或结束
                            while (i < colSpecs.size()) {
                                String next = colSpecs.get(i);
                                String upperNext = next.toUpperCase();
                                // 如果下一个标记是另一个约束的关键字，停止
                                if (upperNext.contains("NOT NULL") || upperNext.equals("PRIMARY") ||
                                    upperNext.contains("UNIQUE") || upperNext.contains("AUTO_INCREMENT") ||
                                    upperNext.contains("DEFAULT") || upperNext.contains("COMMENT")) {
                                    i--; // 回退一步，让外层循环处理这个约束
                                    break;
                                }
                                // 否则继续跳过ON UPDATE
                                i++;
                            }
                        }
                    }
                }
            }
            
            columnDefs.add(colBuilder.toString());
        }
        
        // 处理表级约束
        if (createTable.getIndexes() != null) {
            for (Index index : createTable.getIndexes()) {
                if (index instanceof NamedConstraint constraint) {
                    String constraintType = constraint.getType();
                    if ("PRIMARY KEY".equalsIgnoreCase(constraintType)) {
                        // 表级主键
                        List<String> pkCols = extractMySQLColumnNames(constraint.getColumns());
                        primaryKeyColumns.addAll(pkCols);
                    } else if ("UNIQUE".equalsIgnoreCase(constraintType)) {
                        // 唯一约束
                        constraints.add("UNIQUE (" + String.join(", ", extractMySQLColumnNames(constraint.getColumns())) + ")");
                    }
                } else if ("PRIMARY KEY".equalsIgnoreCase(index.getType())) {
                    // 直接处理PRIMARY KEY类型
                    List<String> pkCols = extractMySQLColumnNames(index.getColumns());
                    primaryKeyColumns.addAll(pkCols);
                }
            }
        }
        
        // 构建列定义部分
        for (int i = 0; i < columnDefs.size(); i++) {
            result.append("  ").append(columnDefs.get(i));
            if (i < columnDefs.size() - 1 || !primaryKeyColumns.isEmpty() || !constraints.isEmpty()) {
                result.append(",");
            }
            result.append("\n");
        }
        
        // 添加主键约束
        if (!primaryKeyColumns.isEmpty()) {
            result.append("  PRIMARY KEY (").append(String.join(", ", primaryKeyColumns)).append(")");
            if (!constraints.isEmpty()) {
                result.append(",");
            }
            result.append("\n");
        }
        
        // 添加其他约束
        for (int i = 0; i < constraints.size(); i++) {
            result.append("  ").append(constraints.get(i));
            if (i < constraints.size() - 1) {
                result.append(",");
            }
            result.append("\n");
        }
        
        // 移除末尾的逗号和换行
        String resultStr = result.toString().trim();
        if (resultStr.endsWith(",")) {
            resultStr = resultStr.substring(0, resultStr.length() - 1);
        }
        
        // 重新构建完整的CREATE TABLE语句
        StringBuilder finalResult = new StringBuilder();
        finalResult.append(resultStr).append("\n)");
        
        // 移除MySQL特有的表选项（ENGINE, CHARSET, ROW_FORMAT等）
        List<String> tableOptions = createTable.getTableOptionsStrings();
        if (tableOptions != null && !tableOptions.isEmpty()) {
            // 将列表转换为字符�?
            String tableOptionsStr = String.join(" ", tableOptions);
            // 简单过滤掉MySQL特有的选项
            String cleanedOptions = tableOptionsStr
                .replaceAll("(?i)ENGINE\\s*=\\s*\\w+", "")
                .replaceAll("(?i)DEFAULT\\s+CHARSET\\s*=\\s*\\w+", "")
                .replaceAll("(?i)CHARSET\\s*=\\s*\\w+", "")
                .replaceAll("(?i)COLLATE\\s*=\\s*\\w+", "")
                .replaceAll("(?i)AUTO_INCREMENT\\s*=\\s*\\d+", "")
                .replaceAll("(?i)ROW_FORMAT\\s*=\\s*\\w+", "")  // 移除ROW_FORMAT选项
                .replaceAll("(?i)COMMENT\\s*=\\s*'.*?'", "")  // 移除单引号注�?
                .replaceAll("(?i)COMMENT\\s*=\\s*\".*?\"", "") // 移除双引号注�?
                .trim();
            
            if (!cleanedOptions.isEmpty() && !cleanedOptions.equals(",")) {
                finalResult.append(" ").append(cleanedOptions);
            }
        }
        
        return finalResult.toString();
    }
    
    /**
     * 转换ALTER TABLE语句
     */
    private static String convertMySQLAlterTable(Alter alter) {
        // 简化处理：返回原始语句，但替换一些关键字
        // 在实际实现中，应该解析AlterExpression并转换
        String ddl = alter.toString();
        
        // DuckDB不支持AUTOINCREMENT，移除AUTO_INCREMENT约束
        ddl = ddl.replaceAll("(?i)\\s*AUTO_INCREMENT", "");
        
        // 替换INT(11)为INTEGER
        ddl = ddl.replaceAll("(?i)INT\\(\\d+\\)", "INTEGER");
        
        // 替换TINYINT(1)为BOOLEAN
        ddl = ddl.replaceAll("(?i)TINYINT\\(1\\)", "BOOLEAN");
        
        // 限制DECIMAL/NUMERIC精度不超过38
        // 使用Pattern和Matcher代替lambda表达�?
        java.util.regex.Pattern decimalPattern = java.util.regex.Pattern.compile(
            "(?i)(DECIMAL|NUMERIC)\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)"
        );
        java.util.regex.Matcher matcher = decimalPattern.matcher(ddl);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String type = matcher.group(1);
            int precision = Integer.parseInt(matcher.group(2));
            int scale = Integer.parseInt(matcher.group(3));
            if (precision > 38) {
                precision = 38;
                if (scale > precision) {
                    scale = precision;
                }
                matcher.appendReplacement(sb, type + "(" + precision + "," + scale + ")");
            } else {
                matcher.appendReplacement(sb, matcher.group(0));
            }
        }
        matcher.appendTail(sb);
        ddl = sb.toString();
        
        // 清理表名中的反引号
        // 注意：这里简单处理，实际应该解析Alter对象并清理标识符
        ddl = ddl.replaceAll("`", "");
        
        // 移除COMMENT注释
        ddl = ddl.replaceAll("(?i)\\s*COMMENT\\s+'.*?'", "");
        ddl = ddl.replaceAll("(?i)\\s*COMMENT\\s+\".*?\"", "");
        
        return ddl;
    }
    
    /**
     * 转换DROP TABLE语句
     */
    private static String convertMySQLDropTable(Drop drop) {
        StringBuilder result = new StringBuilder();
        result.append("DROP TABLE ");
        
        if (drop.isIfExists()) {
            result.append("IF EXISTS ");
        }
        
        result.append(cleanMySQLIdentifier(drop.getName().getName()));
        
        return result.toString();
    }
    
    /**
     * 转换TRUNCATE TABLE语句
     */
    private static String convertMySQLTruncateTable(Truncate truncate) {
        return "TRUNCATE TABLE " + cleanMySQLIdentifier(truncate.getTable().getName());
    }
    
    /**
     * 映射MySQL数据类型到DuckDB类型
     */
    private static String mapMySQLTypeToDuckDB(String mysqlType) {
        if (mysqlType == null) {
            return "VARCHAR";
        }
        
        // 清理类型字符串，移除多余空格
        String cleanType = mysqlType.trim().toUpperCase();
        
        // 提取基础类型（移除括号和参数�?
        String baseType = cleanType.replaceAll("\\(.*\\)", "").trim();
        
        // 移除UNSIGNED/SIGNED修饰符，只保留基础类型�?
        baseType = baseType.replace("UNSIGNED", "").replace("SIGNED", "").trim();
        
        // 检查是否是TINYINT(1) - 应该映射为BOOLEAN
        if ("TINYINT".equals(baseType) && cleanType.contains("(1)")) {
            return "BOOLEAN";
        }
        
        // 对于有参数的类型（如INT(11), VARCHAR(50)），保留参数
        boolean hasArgs = cleanType.contains("(") && cleanType.contains(")");

        return switch (baseType) {
            case "TINYINT" -> "TINYINT";
            case "SMALLINT" -> "SMALLINT";
            case "MEDIUMINT", "INT", "INTEGER" ->
                // 对于INT类型，移除参数，使用INTEGER
                    "INTEGER";
            case "BIGINT" -> "BIGINT";
            case "DECIMAL", "NUMERIC" -> {
                if (hasArgs) {
                    // 解析精度和小数位，确保精度不超过38
                    String args = cleanType.substring(cleanType.indexOf('(') + 1, cleanType.indexOf(')'));
                    String[] parts = args.split(",");
                    if (parts.length == 2) {
                        try {
                            int precision = Integer.parseInt(parts[0].trim());
                            int scale = Integer.parseInt(parts[1].trim());
                            // 如果精度超过38，限制为38
                            if (precision > 38) {
                                precision = 38;
                                // 确保小数位不超过精度
                                if (scale > precision) {
                                    scale = precision;
                                }
                                yield baseType + "(" + precision + "," + scale + ")";
                            }
                        } catch (NumberFormatException e) {
                            // 如果解析失败，返回原始类�?
                            // ����DECIMAL/NUMERIC����ʧ��
                        }
                    }
                }
                yield hasArgs ? cleanType : baseType;
            }
            case "FLOAT" -> "FLOAT";
            case "DOUBLE", "REAL" -> "DOUBLE";
            case "BIT" -> "BOOLEAN";
            case "CHAR", "VARCHAR" -> hasArgs ? cleanType : baseType;
            case "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT" -> "TEXT";
            case "BINARY", "VARBINARY", "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB" -> "BLOB";
            case "DATE" -> "DATE";
            case "TIME" -> "TIME";
            case "DATETIME", "TIMESTAMP" -> "TIMESTAMP";
            case "YEAR" -> "INTEGER";
            case "ENUM", "SET" -> "VARCHAR";
            case "JSON" -> "VARCHAR"; // DuckDB支持JSON但作为VARCHAR存储
            case "GEOMETRY", "POINT", "LINESTRING", "POLYGON" -> "BLOB"; // 空间类型可能不支�?
            default -> {
                // δ֪MySQL���ͣ�ʹ��VARCHAR
                yield "VARCHAR";
            }
        };
    }
    
    /**
     * 从列列表提取列名
     */
    private static List<String> extractMySQLColumnNames(List<?> columns) {
        List<String> columnNames = new ArrayList<>();
        if (columns != null) {
            for (Object col : columns) {
                if (col instanceof net.sf.jsqlparser.schema.Column) {
                    String colName = ((net.sf.jsqlparser.schema.Column) col).getColumnName();
                    columnNames.add(cleanMySQLIdentifier(colName));
                } else if (col instanceof String) {
                    columnNames.add(cleanMySQLIdentifier((String) col));
                } else {
                    // 其他类型通过toString获取
                    columnNames.add(cleanMySQLIdentifier(col.toString()));
                }
            }
        }
        return columnNames;
    }
}

