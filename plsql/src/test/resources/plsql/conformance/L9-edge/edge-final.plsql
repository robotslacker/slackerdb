--! id: EDG-FIN-001
--! layer: L9
--! req: EXECUTE IMMEDIATE
--! tags: numbered-placeholders
-- 编号占位符 :1 :2 按出现顺序绑定 USING
--! setup: create table t(a int, b int)
declare
  x int;
  y int;
begin
  let x = 10;
  let y = 20;
  execute immediate 'insert into t values (:1, :2)' using :x, :y;
end;
--! expect-query: select a, b from t | 10,20
--! ---
--! id: EDG-FIN-002
--! layer: L9
--! req: EXECUTE IMMEDIATE
--! tags: seq-placeholders
-- ? 与 :1 混用，仍按出现顺序
--! setup: create table t(a int, b int)
declare
  x int;
  y int;
begin
  let x = 1;
  let y = 2;
  execute immediate 'insert into t values (?, :1)' using :x, :y;
end;
--! expect-query: select a, b from t | 1,2
--! ---
--! id: EDG-FIN-003
--! layer: L9
--! req: EXPR
--! tags: is-not-null
-- IS NOT NULL 判断
--! setup: create table t(v int)
declare
  x int;
begin
  let x = 5;
  if :x is not null then
    insert into t values(1);
  end if;
  let x = null;
  if :x is null then
    insert into t values(2);
  end if;
end;
--! expect-query: select v from t order by v | 1 ; 2
--! ---
--! id: EDG-FIN-004
--! layer: L9
--! req: EXIT
--! tags: not-in-exit-cond
-- EXIT WHEN 条件用 NOT
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 1..10 loop
    exit when not (:i < 4);
    insert into t values(:i);
  end loop;
end;
--! expect-query: select v from t order by v | 1 ; 2 ; 3
--! ---
--! id: EDG-FIN-005
--! layer: L9
--! req: WHILE
--! tags: double-loop-nested-exit
-- 嵌套循环内用未匹配标签 EXIT → 错误
--! setup: create table t(v int)
declare
  i int;
  j int;
begin
  for :i in 1..2 loop
    exit unrelated_label;
  end loop;
end;
--! expect-error: EXIT/CONTINUE used outside of a loop
--! ---
--! id: EDG-FIN-006
--! layer: L9
--! req: FOR
--! tags: list-strings
-- 字符串列表 FOR 循环
--! setup: create table t(s text)
declare
  i text;
begin
  for :i in ['a','b','c'] loop
    insert into t values(:i);
  end loop;
end;
--! expect-query: select s from t order by s | a ; b ; c
--! ---
--! id: EDG-FIN-007
--! layer: L9
--! req: SQL
--! tags: multi-stmt-block
-- 块内多条 SQL 与混合控制流
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 1..5 loop
    if :i % 2 = 0 then
      insert into t values(:i);
    else
      insert into t values(0 - :i);
    end if;
  end loop;
end;
--! expect-query: select v from t order by v | -5 ; -3 ; -1 ; 2 ; 4
