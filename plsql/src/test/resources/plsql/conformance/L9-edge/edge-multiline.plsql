-- 回归：多行内嵌 SQL 语句不得被复制/截断（历史缺陷：遮罩器在末行重复写占位符，
-- 导致 sqlText 把同一段 SQL 重复拼接，execute 时报 "syntax error ... at or near ..."）。
--! id: EDG-ML-001
--! layer: L9
--! req: SQL
--! tags: multiline-insert
-- 多行 INSERT...SELECT
--! setup: create table t(v int)
begin
  insert into t
  select 42;
end;
--! expect-query: select v from t | 42
--! ---
--! id: EDG-ML-002
--! layer: L9
--! req: SELECT INTO
--! tags: multiline-select-into
-- 多行 WITH CTE + SELECT INTO
--! setup: create table t(v int)
declare
  x int;
begin
  with cte as (select 42 as a)
  select a into :x from cte;
  insert into t
  values(:x);
end;
--! expect-query: select v from t | 42
--! ---
--! id: EDG-ML-003
--! layer: L9
--! req: CURSOR
--! tags: multiline-cursor
-- 多行游标查询
--! setup: create table src(a int)
--! setup: insert into src values (7)
--! setup: create table t(v int)
declare
  cursor c is
    select a
      from src;
  x int;
begin
  open c;
  fetch c into :x;
  close c;
  insert into t values(:x);
end;
--! expect-query: select v from t | 7
--! ---
--! id: EDG-ML-004
--! layer: L9
--! req: SQL
--! tags: multiline-update
-- 多行 UPDATE
--! setup: create table t(v int)
--! setup: insert into t values (1),(2),(3)
begin
  update t
     set v = v + 10
   where v > 1;
end;
--! expect-query: select v from t order by v | 1 ; 12 ; 13
--! ---
--! id: EDG-ML-005
--! layer: L9
--! req: SQL
--! tags: multiline-select
-- 多行 SELECT（块内执行，无 INTO，结果丢弃即可）
--! setup: create table src(a int)
--! setup: insert into src values (1),(2)
begin
  select a
    from src
   order by a;
end;
--! expect-error: none
--! ---
--! id: EDG-ML-006
--! layer: L9
--! req: SELECT INTO
--! tags: multiline-multi-target
-- 多行 SELECT INTO 多目标
--! setup: create table src(a int, b int)
--! setup: insert into src values (3,4)
--! setup: create table t(x int, y int)
declare
  x int;
  y int;
begin
  select a, b
    into :x, :y
    from src;
  insert into t values(:x, :y);
end;
--! expect-query: select x, y from t | 3,4
