-- 与 README-CN.adoc「PL/SQL 支持 → 典型示例」中的例子逐字对应
-- （正文去掉 README 里的 DO $$ 包裹：本语料直接跑引擎，解包由协议层负责）。
-- 目的：文档里的例子必须真的能跑；改动文档示例时同步改这里。

-- ---------- 示例 1：变量 + IF/ELSIF + WHILE 循环 ----------
--! id: RDEX-001
--! layer: L8
--! req: README example
--! tags: readme, loop, branch
DECLARE
    i     INTEGER := 1;
    total INTEGER := 0;
BEGIN
    CREATE OR REPLACE TABLE demo_numbers(n INTEGER, kind TEXT);

    WHILE i <= 6 LOOP
        IF i % 2 = 0 THEN
            INSERT INTO demo_numbers VALUES (:i, 'even');
        ELSIF i % 3 = 0 THEN
            INSERT INTO demo_numbers VALUES (:i, 'multiple of 3');
        ELSE
            INSERT INTO demo_numbers VALUES (:i, 'other');
        END IF;
        total := total + i;
        i := i + 1;
    END LOOP;

    INSERT INTO demo_numbers VALUES (:total, 'sum');
END;
--! expect-query: select n, kind from demo_numbers order by n | 1,other ; 2,even ; 3,multiple of 3 ; 4,even ; 5,other ; 6,even ; 21,sum
--! ---
-- ---------- 示例 2：游标遍历 + %NOTFOUND + 异常处理 ----------
--! id: RDEX-002
--! layer: L8
--! req: README example
--! tags: readme, cursor, exception
DECLARE
    CURSOR cur IS SELECT id, name FROM demo_emp ORDER BY id;
    v_id    INTEGER;
    v_name  TEXT;
    v_total INTEGER := 0;
BEGIN
    CREATE OR REPLACE TABLE demo_emp(id INTEGER, name TEXT);
    INSERT INTO demo_emp VALUES (1, 'alice'), (2, 'bob'), (3, 'carol');
    CREATE OR REPLACE TABLE demo_audit(id INTEGER, note TEXT);

    OPEN cur;
    LOOP
        FETCH cur INTO v_id, v_name;
        EXIT WHEN cur%NOTFOUND;

        IF v_name IS NULL THEN
            CONTINUE;
        END IF;

        INSERT INTO demo_audit VALUES (:v_id, 'ok: ' || :v_name);
        v_total := v_total + 1;
    END LOOP;
    CLOSE cur;

    INSERT INTO demo_audit VALUES (0, 'count = ' || CAST(:v_total AS TEXT));
EXCEPTION
    WHEN OTHERS THEN
        INSERT INTO demo_audit VALUES (-1, 'failed');
END;
--! expect-query: select id, note from demo_audit order by id | 0,count = 3 ; 1,ok: alice ; 2,ok: bob ; 3,ok: carol
--! ---
-- ---------- 示例 3：SELECT INTO 与异常分支 ----------
--! id: RDEX-003
--! layer: L8
--! req: README example
--! tags: readme, select-into, exception
DECLARE
    v_name TEXT;
    v_cnt  INTEGER;
BEGIN
    CREATE OR REPLACE TABLE demo_dept(id INTEGER, name TEXT);
    INSERT INTO demo_dept VALUES (1, 'sales'), (2, 'hr');

    SELECT count(*) INTO v_cnt FROM demo_dept;

    BEGIN
        SELECT name INTO v_name FROM demo_dept WHERE id = 99;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            v_name := '(not found)';
        WHEN TOO_MANY_ROWS THEN
            v_name := '(too many rows)';
    END;

    CREATE OR REPLACE TABLE demo_result(cnt INTEGER, name TEXT);
    INSERT INTO demo_result VALUES (:v_cnt, :v_name);
END;
--! expect-query: select cnt, name from demo_result | 2,(not found)
--! ---
-- ---------- 示例 4：动态 SQL（EXECUTE IMMEDIATE + USING + INTO） ----------
--! id: RDEX-004
--! layer: L8
--! req: README example
--! tags: readme, dynamic-sql
DECLARE
    v_table TEXT := 'demo_dyn';
    v_id    INTEGER := 7;
    v_cnt   INTEGER;
BEGIN
    EXECUTE IMMEDIATE 'CREATE OR REPLACE TABLE ' || v_table || '(id INTEGER)';
    EXECUTE IMMEDIATE 'INSERT INTO ' || v_table || ' VALUES (?)' USING v_id;
    EXECUTE IMMEDIATE 'SELECT count(*) FROM ' || v_table INTO v_cnt;
    EXECUTE IMMEDIATE 'INSERT INTO ' || v_table || ' VALUES (?)' USING v_cnt * 10;
END;
--! expect-query: select id from demo_dyn order by id | 7 ; 10
--! ---
