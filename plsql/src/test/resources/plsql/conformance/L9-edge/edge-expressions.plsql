--! id: EDG-EXP-001
--! layer: L9
--! req: EXPR
--! tags: short-circuit-and
-- AND 短路：false AND (expr) 不求值右侧
--! setup: create table t(v int)
begin
  if false and (1 / 0 = 1) then
    insert into t values(1);
  else
    insert into t values(2);
  end if;
end;
--! expect-query: select v from t | 2
--! ---
--! id: EDG-EXP-002
--! layer: L9
--! req: EXPR
--! tags: short-circuit-or
-- OR 短路：true OR (expr) 不求值右侧
--! setup: create table t(v int)
begin
  if true or (1 / 0 = 1) then
    insert into t values(1);
  end if;
end;
--! expect-query: select v from t | 1
--! ---
--! id: EDG-EXP-003
--! layer: L9
--! req: EXPR
--! tags: three-valued-null
-- NULL 比较为 NULL，按 false 处理
--! setup: create table t(v int)
declare
  x int;
begin
  if :x = 1 then
    insert into t values(1);
  else
    insert into t values(2);
  end if;
end;
--! expect-query: select v from t | 2
--! ---
--! id: EDG-EXP-004
--! layer: L9
--! req: EXPR
--! tags: null-is-null
-- IS NULL 判断
--! setup: create table t(v int)
declare
  x int;
begin
  if :x is null then
    insert into t values(1);
  end if;
  let x = 5;
  if :x is null then
    insert into t values(2);
  end if;
end;
--! expect-query: select v from t | 1
--! ---
--! id: EDG-EXP-005
--! layer: L9
--! req: EXPR
--! tags: in-list
-- IN 列表
--! setup: create table t(v int)
declare
  x int;
begin
  let x = 3;
  if :x in (1, 3, 5) then
    insert into t values(1);
  end if;
end;
--! expect-query: select v from t | 1
--! ---
--! id: EDG-EXP-006
--! layer: L9
--! req: EXPR
--! tags: between
-- BETWEEN 闭区间
--! setup: create table t(v int)
declare
  x int;
begin
  let x = 5;
  if :x between 3 and 6 then
    insert into t values(1);
  end if;
end;
--! expect-query: select v from t | 1
--! ---
--! id: EDG-EXP-007
--! layer: L9
--! req: EXPR
--! tags: case
-- CASE 表达式
--! setup: create table t(v int)
declare
  x int;
  y int;
begin
  let x = 2;
  let y = case when :x = 1 then 10 when :x = 2 then 20 else 30 end;
  insert into t values(:y);
end;
--! expect-query: select v from t | 20
--! ---
--! id: EDG-EXP-008
--! layer: L9
--! req: EXPR
--! tags: not
-- NOT 与 != 
--! setup: create table t(v int)
declare
  x int;
begin
  let x = 1;
  if not(:x = 2) then
    insert into t values(1);
  end if;
  if :x != 2 then
    insert into t values(2);
  end if;
end;
--! expect-query: select v from t order by v | 1 ; 2
--! ---
--! id: EDG-EXP-009
--! layer: L9
--! req: EXPR
--! tags: bool-condition-error, error
-- 非布尔条件：if 5 then → 报错
--! setup: create table t(v int)
begin
  if 5 then
    insert into t values(1);
  end if;
end;
--! expect-error: Condition must be BOOLEAN
--! ---
--! id: EDG-EXP-010
--! layer: L9
--! req: EXPR
--! tags: function
-- 数据库函数调用（length）
--! setup: create table t(v int)
declare
  s text;
begin
  let s = 'hello';
  insert into t values(length(:s));
end;
--! expect-query: select v from t | 5
--! ---
--! id: EDG-EXP-011
--! layer: L9
--! req: EXPR
--! tags: mod
-- 取模运算
--! setup: create table t(v int)
declare
  x int;
begin
  let x = 7 % 3;
  insert into t values(:x);
end;
--! expect-query: select v from t | 1
--! ---
--! id: EDG-EXP-012
--! layer: L9
--! req: EXPR
--! tags: mod-zero-error
-- 取模零：报错（可捕获）
--! setup: create table t(v int)
declare
  x int;
begin
  let x = 5 % 0;
  insert into t values(9);
exception
  when zero_divide then
    insert into t values(8);
end;
--! expect-query: select v from t | 8
