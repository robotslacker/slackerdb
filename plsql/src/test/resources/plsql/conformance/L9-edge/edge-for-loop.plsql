--! id: EDG-FOR-001
--! layer: L9
--! req: FOR
--! tags: variable-bounds
-- 边界可以是变量表达式
--! setup: create table t(v int)
declare
  lo int;
  hi int;
  i int;
begin
  let lo = 2;
  let hi = 4;
  for :i in :lo..:hi loop
    insert into t values(:i);
  end loop;
end;
--! expect-query: select v from t order by v | 2 ; 3 ; 4
--! ---
--! id: EDG-FOR-002
--! layer: L9
--! req: FOR
--! tags: empty-forward
-- 前向空区间：from > to 时 0 次迭代
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 5..1 loop
    insert into t values(:i);
  end loop;
  insert into t values(0);
end;
--! expect-query: select v from t | 0
--! ---
--! id: EDG-FOR-003
--! layer: L9
--! req: FOR
--! tags: reverse
-- REVERSE 闭区间降序
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in reverse 3..1 loop
    insert into t values(:i);
  end loop;
end;
--! expect-query: select v from t order by v | 1 ; 2 ; 3
--! ---
--! id: EDG-FOR-004
--! layer: L9
--! req: FOR
--! tags: single
-- 单元素区间
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 7..7 loop
    insert into t values(:i);
  end loop;
end;
--! expect-query: select v from t | 7
--! ---
--! id: EDG-FOR-005
--! layer: L9
--! req: FOR
--! tags: null-bound, error
-- NULL 边界：编译外报错（不能被捕获？实际是 SYNTAX_ERROR → 不可捕获）
--! setup: create table t(v int)
declare
  n int;
begin
  for :i in :n..5 loop
    insert into t values(1);
  end loop;
end;
--! expect-error: FOR loop bounds must not be NULL
--! ---
--! id: EDG-FOR-006
--! layer: L9
--! req: WHILE
--! tags: labeled-exit
-- 标签 EXIT 退出指定循环
--! setup: create table t(v int)
declare
  i int;
  j int;
begin
  <<outer>>
  for :i in 1..3 loop
    for :j in 1..3 loop
      insert into t values(:j);
      exit outer when :j = 2;
    end loop;
    insert into t values(9);
  end loop;
end;
--! expect-query: select v from t order by v | 1 ; 2
--! ---
--! id: EDG-FOR-007
--! layer: L9
--! req: WHILE
--! tags: continue-inner
-- CONTINUE 继续最内层循环
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 1..3 loop
    if :i = 2 then
      continue;
    end if;
    insert into t values(:i);
  end loop;
end;
--! expect-query: select v from t order by v | 1 ; 3
--! ---
--! id: EDG-FOR-008
--! layer: L9
--! req: LOOP
--! tags: labeled-continue
-- 标签 CONTINUE 跳到外层循环继续
--! setup: create table t(v int)
declare
  i int;
  j int;
begin
  <<outer>>
  for :i in 1..2 loop
    for :j in 1..2 loop
      insert into t values(100);
      continue outer;
    end loop;
  end loop;
end;
--! expect-query: select count(*) from t | 2
--! ---
--! id: EDG-FOR-009
--! layer: L9
--! req: LOOP
--! tags: exit-when-inline-sql
-- EXIT WHEN 条件里含内嵌 SQL 常量表达式求值
--! setup: create table t(v int)
begin
  loop
    exit when 2 > 1;
    insert into t values(1);
  end loop;
  insert into t values(2);
end;
--! expect-query: select v from t | 2
--! ---
--! id: EDG-FOR-010
--! layer: L9
--! req: FOR
--! tags: decimal-bounds
-- 数值边界（含小数）取整
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 1.9..3.9 loop
    insert into t values(:i);
  end loop;
end;
--! expect-query: select v from t order by v | 1 ; 2 ; 3
