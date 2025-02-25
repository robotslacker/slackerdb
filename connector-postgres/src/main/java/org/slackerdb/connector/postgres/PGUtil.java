package org.slackerdb.connector.postgres;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class PGUtil {
    public static String getTableDDL(Connection connection, String schemaName, String tableName) throws SQLException {
        String ret = null;
        String sql = """
                SELECT ret  FROM
                	(
                			SELECT
                				'CREATE TABLE {{schema}}.{{table}} (' || array_to_string(ARRAY (
                								SELECT SQL
                								FROM
                									(
                										(
                											-- 字段信息
                											SELECT array_to_string(ARRAY (
                											SELECT A.attname || ' ' || concat_ws ( '', T.typname, SUBSTRING ( format_type ( A.atttypid, A.atttypmod ) FROM '\\(.*\\)' ) ) || ' ' ||
                														CASE A.attnotnull  WHEN 't' THEN 'NOT NULL' ELSE '' END || ' ' ||
                														CASE WHEN D.adbin IS NOT NULL THEN ' DEFAULT ' || pg_get_expr ( D.adbin, A.attrelid ) ELSE'' END
                											FROM
                												pg_attribute
                												A LEFT JOIN pg_description b ON A.attrelid = b.objoid
                												AND A.attnum = b.objsubid
                												LEFT JOIN pg_attrdef D ON A.attrelid = D.adrelid
                												AND A.attnum = D.adnum,
                												pg_type T
                											WHERE
                												A.attstattarget =- 1
                												AND A.attrelid = '{{schema}}.{{table}}' :: REGCLASS :: OID
                												AND A.attnum > 0
                												AND NOT A.attisdropped
                												AND A.atttypid = T.OID
                											ORDER BY A.attnum  ),',' || CHR( 10 )  ) as SQL, 1 seri
                										)
                										UNION
                										(
                											-- 约束（含主外键）信息
                											SELECT 'CONSTRAINT ' || conname || ' ' || pg_get_constraintdef ( OID ) as SQL, 2 seri
                											FROM pg_constraint T
                											WHERE conrelid = '{{schema}}.{{table}}' :: REGCLASS :: OID
                											ORDER BY contype DESC
                										)
                										ORDER BY seri 
                									) T
                							),',' || CHR( 10 )  ) || ')' AS ret, 1 AS orderby
                			UNION
                				--索引信息
                				SELECT array_to_string(ARRAY (
                								SELECT pg_get_indexdef ( indexrelid )
                								FROM pg_index
                								WHERE indrelid = '{{schema}}.{{table}}' :: REGCLASS :: OID
                								AND indisprimary = 'f'
                								AND indisunique = 'f'
                						),';' || CHR( 10 ) ) AS ret,2 AS orderby
                			UNION --注释信息
                				SELECT array_to_string(ARRAY (
                									SELECT
                										'COMMENT ON COLUMN ' || '{{schema}}.{{table}}.' || A.attname || ' IS ''' || b.description || ''''
                									FROM pg_attribute A
                									LEFT JOIN pg_description b ON A.attrelid = b.objoid
                										AND A.attnum = b.objsubid
                									WHERE
                										A.attstattarget =- 1
                										AND A.attrelid = '{{schema}}.{{table}}' :: REGCLASS :: OID
                										AND b.description IS NOT NULL
                									ORDER BY
                										A.attnum
                						), ';' || CHR( 10 )  ) AS ret,3 AS orderby
                				ORDER BY orderby
                	) results                
                """;
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql.replaceAll("\\{\\{schema}}", schemaName).replaceAll("\\{\\{table}}", tableName));
        if (rs.next())
        {
            ret = rs.getString("ret");
        }
        rs.close();
        statement.close();
        return ret;
    }
}
