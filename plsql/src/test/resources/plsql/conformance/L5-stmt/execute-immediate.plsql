-- 动态 SQL：EXECUTE IMMEDIATE <sql 文本> [INTO 目标...] [USING 值...]
-- 语义要点：SQL 文本在运行期求值；USING 按占位符出现顺序绑定；
-- 带 INTO 走单行语义（NO_DATA_FOUND / TOO_MANY_ROWS / 列数不符 24000）；
-- 不带 INTO 时结果集丢弃；动态 SQL 自身错误由后端给出且可被 EXCEPTION 捕获。

--! id: DYN-001
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: smoke, dynamic
--! setup: create table t(i int)
declare
  n int;
begin
  execute immediate 'insert into t values (7)';
  execute immediate 'select count(*) from t' into n;
  insert into t values(:n);
end;
--! expect-query: select i from t order by i | 1 ; 7
--! ---
--! id: DYN-002
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, using
--! setup: create table s(name text, n int)
declare
  who text := 'bob';
  num int := 42;
begin
  execute immediate 'insert into s values (?, ?)' using who, num;
end;
--! expect-query: select name, n from s | bob,42
--! ---
--! id: DYN-003
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, using
--! setup: create table t(a int, b int)
declare
  x int := 3;
begin
  -- :1/:2 的编号只是书写便利，绑定一律按占位符出现顺序
  execute immediate 'insert into t values (:2, :1)' using x, x * 2;
end;
--! expect-query: select a, b from t | 3,6
--! ---
--! id: DYN-004
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, expression
--! setup: create table t(i int)
declare
  sql_text text;
  v int := 5;
begin
  sql_text := 'insert into t values (' || v || ')';
  execute immediate sql_text;
end;
--! expect-query: select i from t | 5
--! ---
--! id: DYN-005
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, into
--! setup: create table t(a int, b text)
--! setup: insert into t values (1, 'x')
declare
  m int;
  s text;
begin
  execute immediate 'select a, b from t' into m, s;
  insert into t values(:m + 1, :s);
end;
--! expect-query: select a, b from t order by a | 1,x ; 2,x
--! ---
--! id: DYN-006
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, into, exception
--! setup: create table t(i int)
declare
  m int := 0;
begin
  begin
    execute immediate 'select i from t' into m;
  exception
    when no_data_found then
      m := 99;
  end;
  insert into t values(:m);
end;
--! expect-query: select i from t | 99
--! ---
--! id: DYN-007
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, into, exception
--! setup: create table t(i int)
--! setup: insert into t values (1)
--! setup: insert into t values (2)
declare
  m int := 0;
begin
  begin
    execute immediate 'select i from t' into m;
  exception
    when too_many_rows then
      m := 77;
  end;
  insert into t values(:m);
end;
--! expect-query: select i from t order by i | 1 ; 2 ; 77
--! ---
--! id: DYN-008
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, using, exception
--! setup: create table t(i int)
declare
  m int := 0;
begin
  begin
    execute immediate 'insert into t values (?)';
  exception
    when others then
      m := 5;
  end;
  insert into t values(:m);
end;
--! expect-query: select i from t | 5
--! ---
--! id: DYN-009
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, using, error
--! setup: create table t(i int)
declare
begin
  execute immediate 'insert into t values (1)' using 5;
end;
--! expect-error: extra value(s) in USING
--! ---
--! id: DYN-010
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, error
declare
  sql_text text;
begin
  execute immediate sql_text;
end;
--! expect-error: dynamic SQL text is NULL
--! ---
--! id: DYN-011
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic
--! setup: create table t(i int)
--! setup: insert into t values (1)
begin
  -- 无 INTO：结果集直接丢弃，语句本身照常执行
  execute immediate 'select * from t';
  execute immediate 'update t set i = 2 where i = 1';
end;
--! expect-query: select i from t | 2
--! ---
--! id: DYN-012
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, exception
--! setup: create table t(i int)
declare
  m int := 0;
begin
  begin
    execute immediate 'select * from no_such_table_xyz';
  exception
    when others then
      m := 3;
  end;
  insert into t values(:m);
end;
--! expect-query: select i from t | 3
--! ---
--! id: DYN-013
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, into, exception
--! setup: create table t(a int, b int)
--! setup: insert into t values (1, 2)
declare
  m int := 0;
begin
  begin
    execute immediate 'select a, b from t' into m;
  exception
    when others then
      m := 8;
  end;
  insert into t values(:m, 0);
end;
--! expect-query: select a, b from t order by a | 1,2 ; 8,0
--! ---
--! id: DYN-014
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic
--! setup: create table t(i int)
declare
  v int := 11;
begin
  -- 没有 USING 时 :name 按当前作用域变量取值（与内嵌 SQL 的语义一致）
  execute immediate 'insert into t values (:v)';
end;
--! expect-query: select i from t | 11
--! ---
--! id: DYN-015
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, loop, using
--! setup: create table t(i int)
declare
  i int := 0;
begin
  while i < 3 loop
    i := i + 1;
    execute immediate 'insert into t values (?)' using i;
  end loop;
end;
--! expect-query: select i from t order by i | 1 ; 2 ; 3
--! ---
--! id: DYN-016
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, ddl
declare
begin
  execute immediate 'create table d(i int)';
  execute immediate 'insert into d values (1)';
end;
--! expect-query: select i from d | 1
--! ---
--! id: DYN-017
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, using, null
--! setup: create table t(i int, s text)
declare
  v text;
begin
  execute immediate 'insert into t values (1, ?)' using v;
end;
--! expect-query: select i, s from t | 1,null
--! ---
--! id: DYN-018
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, mixed
--! setup: create table t(i int)
declare
  n int := 2;
begin
  insert into t values(:n);
  execute immediate 'insert into t values (?)' using n + 1;
  execute immediate 'delete from t where i = 99';
  insert into t values(:n + 10);
end;
--! expect-query: select i from t order by i | 2 ; 3 ; 12
--! ---
--! id: DYN-019
--! layer: L5
--! req: EXECUTE IMMEDIATE
--! tags: dynamic
--! setup: create table t(i int)
begin
  -- 动态 SQL 文本里带分号（字符串内的分号不能截断语句）
  execute immediate 'insert into t values (1);';
  execute immediate 'insert into t values (2)';
end;
--! expect-query: select i from t order by i | 1 ; 2
--! ---
