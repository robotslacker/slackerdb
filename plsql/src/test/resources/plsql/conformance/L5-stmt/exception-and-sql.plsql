--! id: EXC-001
--! layer: L5
--! req: EXCEPTION
--! tags: smoke
--! setup: create table t(i int)
--! setup: insert into t values(1)
begin
  update t set i = 5;
exception:
  update t set i = 9;
end;
--! expect-query: select i from t | 5
--! ---
--! id: EXC-002
--! layer: L5
--! req: EXCEPTION
--! tags: rollback
--! setup: create table t(i int)
--! setup: insert into t values(1)
begin
  update t set i = 'abc';
exception:
  rollback;
  update t set i = 6;
end;
--! expect-query: select i from t | 6
--! ---
--! id: EXC-003
--! layer: L5
--! req: EXCEPTION
--! tags: nested, handler-not-run
-- EXCEPTION 段只在**发生异常**时执行；本例无异常，因此 handler 内的嵌套块不会运行。
--! setup: create table t(i int)
--! setup: insert into t values(1)
begin
  insert into t values(2);
exception:
  begin
    insert into t values(3);
  exception:
    insert into t values(4);
  end;
end;
--! expect-query: select i from t order by i | 1 ; 2
--! ---
--! id: EXC-004
--! layer: L5
--! req: EXCEPTION
--! tags: nested, smoke
-- 注意：DuckDB 在语句失败后会 abort 当前事务，处理块里必须先 ROLLBACK 才能继续执行 SQL。
--! setup: create table t(i int)
--! setup: insert into t values(1)
begin
  insert into t values(2);
  update t set i = 'abc';
exception:
  rollback;
  begin
    insert into t values(3);
  exception:
    insert into t values(4);
  end;
end;
--! expect-query: select i from t order by i | 1 ; 3
--! ---
--! id: EXC-005
--! layer: L5
--! req: EXCEPTION
--! tags: zero-divide
-- 除零抛 22012，可被 WHEN ZERO_DIVIDE 捕获（后端 Infinity 已由引擎本地检查拦下）
--! setup: create table t(i int)
--! setup: insert into t values(1)
declare
  x int;
begin
  let x = 1 / 0;
  insert into t values(9);
exception
  when zero_divide then
    insert into t values(7);
end;
--! expect-query: select i from t order by i | 1 ; 7
--! ---
--! id: EXC-006
--! layer: L5
--! req: EXCEPTION
--! tags: no-data-found
-- SELECT INTO 无数据抛 NO_DATA_FOUND，可被捕获并继续
--! setup: create table src(a int)
--! setup: create table t(i int)
declare
  x int;
begin
  select a into :x from src;
  insert into t values(1);
exception
  when no_data_found then
    insert into t values(2);
end;
--! expect-query: select i from t | 2
--! ---
--! id: EXC-007
--! layer: L5
--! req: EXCEPTION
--! tags: raise-custom
-- RAISE 自定义异常 + WHEN 名字匹配
--! setup: create table t(i int)
begin
  raise my_error;
exception
  when other_error then
    insert into t values(1);
  when my_error then
    insert into t values(2);
end;
--! expect-query: select i from t | 2
--! ---
--! id: EXC-008
--! layer: L5
--! req: EXCEPTION
--! tags: raise-reraise
-- 处理块内 RAISE; 重抛 → 外层 OTHERS 捕获
--! setup: create table t(i int)
begin
  begin
    raise my_error;
  exception
    when my_error then
      insert into t values(1);
      raise;
  end;
  insert into t values(9);
exception
  when others then
    insert into t values(2);
end;
--! expect-query: select i from t order by i | 1 ; 2
--! ---
--! id: SQL-001
--! layer: L5
--! req: SQL
--! tags: smoke, semicolon-in-string
--! setup: create table t(s text)
begin
  insert into t values('a;b');
end;
--! expect-query: select s from t | "a;b"
--! ---
--! id: SQL-002
--! layer: L5
--! req: SQL
--! tags: bind-expression
--! setup: create table t(i int)
declare
  x int;
begin
  let x = 10;
  insert into t values(:x + 1);
end;
--! expect-query: select i from t | 11
--! ---
--! id: SQL-003
--! layer: L5
--! req: SQL
--! tags: smoke, update
--! setup: create table t(i int, flag text)
--! setup: insert into t values (1,'a'),(2,'a'),(3,'b')
begin
  update t set flag = 'x' where flag = 'a';
end;
--! expect-query: select i, flag from t order by i | 1,x ; 2,x ; 3,b
--! ---
--! id: SQL-004
--! layer: L5
--! req: SQL
--! tags: flipped-p6
-- 字符串字面量内的 :var **不插值**，':current' 就是字面文本。
-- （历史实现用非贪婪正则把它截成 ':c' → '**UNKNOWN**urrent'。）
--! setup: create table t(s text)
declare
  current int;
begin
  let current = 10;
  insert into t select ':current';
end;
--! expect-query: select s from t | ":current"
--! ---
--! id: SQL-005
--! layer: L5
--! req: SQL
--! tags: flipped-p6
-- P4 起 SQL 段由遮罩处理，CASE...END 不再与结构关键字冲突
--! setup: create table t(s text)
begin
  insert into t select case when 1=1 then 'y' else 'n' end;
end;
--! expect-query: select s from t | y
--! ---
--! id: SQL-006
--! layer: L5
--! req: SQL
--! tags: flipped-p6
-- P4 起 $$ 出现在 SQL 段内不再触发词法错误（DuckDB 自己支持美元引用）
--! setup: create table t(s text)
begin
  insert into t values($$a$$);
end;
--! expect-query: select s from t | a
