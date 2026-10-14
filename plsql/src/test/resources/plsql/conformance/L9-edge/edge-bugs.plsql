--! id: EDG-BUG-001
--! layer: L9
--! req: EXECUTE IMMEDIATE
--! tags: into-zero-rows
-- EXECUTE IMMEDIATE ... INTO 0 行 → NO_DATA_FOUND
--! setup: create table src(a int)
--! setup: create table t(i int)
declare
  x int;
begin
  execute immediate 'select a from src' into :x;
  insert into t values(1);
exception
  when no_data_found then
    insert into t values(2);
end;
--! expect-query: select i from t | 2
--! ---
--! id: EDG-BUG-002
--! layer: L9
--! req: EXECUTE IMMEDIATE
--! tags: into-multi-rows
-- EXECUTE IMMEDIATE ... INTO 多行 → TOO_MANY_ROWS
--! setup: create table src(a int)
--! setup: insert into src values (1),(2)
--! setup: create table t(i int)
declare
  x int;
begin
  execute immediate 'select a from src' into :x;
  insert into t values(1);
exception
  when too_many_rows then
    insert into t values(3);
end;
--! expect-query: select i from t | 3
--! ---
--! id: EDG-BUG-003
--! layer: L9
--! req: SELECT INTO
--! tags: type-mismatch-target, error
-- SELECT INTO 字符串列→整数变量失败（可捕获）
--! setup: create table src(s text)
--! setup: insert into src values ('x')
--! setup: create table t(i int)
declare
  v int;
begin
  select s into :v from src;
  insert into t values(1);
exception
  when others then
    insert into t values(2);
end;
--! expect-query: select i from t | 2
--! ---
--! id: EDG-BUG-004
--! layer: L9
--! req: SQL
--! tags: constraint-error-catch
-- 约束/空值错误可被 EXCEPTION 捕获
--! setup: create table t(i int not null)
--! setup: insert into t values (1)
begin
  insert into t values (null);
  insert into t values (9);
exception
  when others then
    -- DuckDB 语句失败后事务进入 abort，需回滚才能继续
    rollback;
    insert into t values (7);
end;
--! expect-query: select i from t order by i | 1 ; 7
--! ---
--! id: EDG-BUG-005
--! layer: L9
--! req: SQL
--! tags: bool-bind
-- 布尔变量绑定进 SQL
--! setup: create table t(b bool)
declare
  f boolean;
begin
  let f = true;
  insert into t values(:f);
  insert into t select not :f;
end;
--! expect-query: select b from t order by b | false ; true
--! ---
--! id: EDG-BUG-006
--! layer: L9
--! req: FOR
--! tags: list-mixed
-- 列表形式 FOR 循环
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in [10, 20, 30] loop
    insert into t values(:i);
  end loop;
end;
--! expect-query: select v from t order by v | 10 ; 20 ; 30
--! ---
--! id: EDG-BUG-007
--! layer: L9
--! req: RETURN
--! tags: return-in-loop
-- RETURN 在循环内也能结束整个块
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 1..10 loop
    insert into t values(:i);
    if :i = 2 then
      return;
    end if;
  end loop;
  insert into t values(99);
end;
--! expect-query: select v from t order by v | 1 ; 2
--! ---
--! id: EDG-BUG-008
--! layer: L9
--! req: EXPR
--! tags: not-in-null
-- NOT IN 含 NULL 的三值逻辑
--! setup: create table t(v int)
declare
  x int;
begin
  let x = 1;
  -- 1 NOT IN (2, NULL) → NULL（未知）→ 按 false
  if :x not in (2, null) then
    insert into t values(1);
  else
    insert into t values(2);
  end if;
end;
--! expect-query: select v from t | 2
--! ---
--! id: EDG-BUG-009
--! layer: L9
--! req: BIND
--! tags: date-bind
-- 日期绑定进 SQL
--! setup: create table t(d date)
declare
  d date;
begin
  let d = '2024-01-02';
  insert into t values(:d);
end;
--! expect-query: select cast(d as text) from t | 2024-01-02
--! ---
--! id: EDG-BUG-010
--! layer: L9
--! req: CURSOR
--! tags: cursor-in-nested-block
-- 外层打开的游标在内层块结束后仍在作用域
--! setup: create table src(a int)
--! setup: insert into src values (5)
--! setup: create table t(v int)
declare
  cursor c is select a from src;
  x int;
begin
  open c;
  begin
    null;
  end;
  fetch c into :x;
  close c;
  insert into t values(:x);
end;
--! expect-query: select v from t | 5
--! ---
--! id: EDG-BUG-011
--! layer: L9
--! req: BIND
--! tags: text-bind-null-eq
-- 文本变量 NULL，= 比较按三值逻辑：0 行被删，两行都保留
--! setup: create table t(s text)
--! setup: insert into t values (null),('a')
declare
  s text;
begin
  delete from t where s = :s;
end;
--! expect-query: select s from t order by s | a ; null
