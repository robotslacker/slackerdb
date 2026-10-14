--! id: EDG-ADV-001
--! layer: L9
--! req: SELECT INTO
--! tags: column-count-mismatch, error
-- SELECT INTO 目标列数与查询列数不符 → 报错（可捕获）
--! setup: create table src(a int, b int)
--! setup: insert into src values (1,2)
--! setup: create table t(i int)
declare
  x int;
begin
  select a, b into :x from src;
  insert into t values(1);
exception
  when invalid_cursor then
    insert into t values(2);
end;
--! expect-query: select i from t | 2
--! ---
--! id: EDG-ADV-002
--! layer: L9
--! req: FETCH
--! tags: column-count-mismatch, error
-- FETCH 目标列数与查询列数不符 → 报错（可捕获）
--! setup: create table src(a int, b int)
--! setup: insert into src values (1,2)
--! setup: create table t(i int)
declare
  cursor c is select a, b from src;
  x int;
begin
  open c;
  fetch c into :x;
  insert into t values(1);
exception
  when others then
    insert into t values(2);
end;
--! expect-query: select i from t | 2
--! ---
--! id: EDG-ADV-003
--! layer: L9
--! req: FOR
--! tags: empty-list
-- 列表形式 FOR 空列表 → 0 次迭代
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in [] loop
    insert into t values(9);
  end loop;
  insert into t values(1);
end;
--! expect-query: select v from t | 1
--! ---
--! id: EDG-ADV-004
--! layer: L9
--! req: FOR
--! tags: reverse-empty-list
-- 空列表 + REVERSE → 0 次迭代
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in reverse [] loop
    insert into t values(9);
  end loop;
  insert into t values(1);
end;
--! expect-query: select v from t | 1
--! ---
--! id: EDG-ADV-005
--! layer: L9
--! req: TYPES
--! tags: bad-bool-text, error
-- 给布尔变量赋数字 → 报错（可捕获）
--! setup: create table t(i int)
declare
  b boolean;
begin
  let b = 1;
  insert into t values(1);
exception
  when others then
    insert into t values(2);
end;
--! expect-query: select i from t | 2
--! ---
--! id: EDG-ADV-006
--! layer: L9
--! req: DECLARE
--! tags: default-fn
-- DEFAULT 可用函数表达式初始化
--! setup: create table t(v int)
declare
  x int default length('hello');
begin
  insert into t values(:x);
end;
--! expect-query: select v from t | 5
--! ---
--! id: EDG-ADV-007
--! layer: L9
--! req: LOOP
--! tags: exit-unknown-label, error
-- EXIT 指向不存在的标签 → 报错
--! setup: create table t(i int)
begin
  loop
    exit no_such_label;
  end loop;
end;
--! expect-error: EXIT/CONTINUE used outside of a loop
--! ---
--! id: EDG-ADV-008
--! layer: L9
--! req: FOR
--! tags: list-expr
-- 列表项可以是变量/表达式
--! setup: create table t(v int)
declare
  a int;
  i int;
begin
  let a = 100;
  for :i in [:a, :a + 1, 200] loop
    insert into t values(:i);
  end loop;
end;
--! expect-query: select v from t order by v | 100 ; 101 ; 200
--! ---
--! id: EDG-ADV-009
--! layer: L9
--! req: EXCEPTION
--! tags: rollback-then-insert
-- 异常处理里先回滚再插入（DuckDB 事务已中止，必须先回滚）
--! setup: create table t(i int not null)
--! setup: insert into t values (1)
begin
  insert into t values (null);
exception
  when others then
    rollback;
    insert into t values (5);
end;
--! expect-query: select i from t order by i | 1 ; 5
--! ---
--! id: EDG-ADV-010
--! layer: L9
--! req: SQL
--! tags: ddl-in-block
-- 块内执行 DDL（建表）
declare
  x int;
begin
  create table adv10(i int);
  insert into adv10 values(7);
end;
--! expect-query: select count(*) from adv10 | 1
