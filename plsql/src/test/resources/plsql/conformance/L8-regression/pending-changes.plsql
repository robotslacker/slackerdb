-- 本文件锁定"历史行为"中**将被有意变更**的部分。
-- 每条用例都带 pending-change-* 标签，P6 替换引擎后更新期望值，
-- 这些用例是"变更确实发生且被记录"的证据。

--! id: PEND-001
--! layer: L8
--! req: FOR
--! tags: flipped-p6
-- FOR 上界闭区间（历史行为是 i < n）
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 1 TO 5 loop
    insert into t values(:i);
  end loop;
end;
--! expect-query: select v from t order by v | 1 ; 2 ; 3 ; 4 ; 5
--! ---
--! id: PEND-002
--! layer: L8
--! req: FOR
--! tags: for-empty
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 5 TO 1 loop
    insert into t values(:i);
  end loop;
  insert into t values(0);
end;
--! expect-query: select v from t | 0
--! ---
--! id: PEND-003
--! layer: L8
--! req: EXIT
--! tags: flipped-p6
-- EXIT 只退出最内层循环（历史行为是退出整个块）
--! setup: create table src(a int)
--! setup: create table t(v int)
declare
  cursor c is select a from src;
  i int;
begin
  loop
    insert into t values(1);
    exit;
    exit when c%notfound;
  end loop;
  insert into t values(2);
end;
--! expect-query: select v from t | 1 ; 2
--! ---
--! id: PEND-004
--! layer: L8
--! req: IF
--! tags: bugfix-if-no-else
-- 条件为假且无 ELSE 时必须跳过（旧实现 NPE）。该缺陷已在 P2 修复。
--! setup: create table t(v int)
begin
  if 3 > 5 then
    insert into t values(1);
  end if;
  insert into t values(2);
end;
--! expect-query: select v from t | 2
--! ---
--! id: PEND-005
--! layer: L8
--! req: DECLARE
--! tags: flipped-p6
-- 未初始化变量是 NULL（历史行为是数值 0）
--! setup: create table t(v int)
declare
  x int;
begin
  insert into t values(:x);
end;
--! expect-query: select v from t | null
--! ---
--! id: PEND-006
--! layer: L8
--! req: DECLARE
--! tags: flipped-p6
-- 表达式按绑定参数求值、字符串内不插值（历史行为是把值拼进 SQL）
--! setup: create table t(v text)
declare
  name text;
begin
  let name = 'o''brien';
  insert into t values(:name);
  insert into t values('x :name y');
end;
--! expect-query: select v from t order by v | "o'brien" ; x :name y
