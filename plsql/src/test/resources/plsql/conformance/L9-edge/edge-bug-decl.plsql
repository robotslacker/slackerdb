--! id: EDG-BUG-DECL-002
--! layer: L9
--! mode: compile
--! req: DECLARE
--! tags: default-is-null
-- 变量默认值里出现 "is"（如 a is null）不应被当成游标查询起点
declare
  a int;
  x boolean default (a is null);
  y int default 3;
begin
  if :x then
    null;
  else
    null;
  end if;
end;
--! expect-error: none
--! ---
--! id: EDG-BUG-DECL-003
--! layer: L9
--! mode: compile
--! req: DECLARE
--! tags: default-expr
-- 默认值含括号表达式与"is not null"
declare
  a int default 1;
  x boolean default (a is not null);
  y int default :a + 1;
begin
  null;
end;
--! expect-error: none
--! ---
--! id: EDG-BUG-DECL-004
--! layer: L9
--! mode: compile
--! req: CURSOR
--! tags: cursor-plus-default-is
-- CURSOR 声明与含 IS 的变量默认值共存
declare
  a int;
  x boolean default (a is null);
  cursor c is select a;
  y int default 7;
begin
  null;
end;
--! expect-error: none
--! ---
--! id: EDG-BUG-DECL-005
--! layer: L9
--! mode: compile
--! req: CURSOR
--! tags: two-cursors
-- 连续两个 CURSOR 声明仍正确遮罩
declare
  cursor c1 is select 1;
  cursor c2 is select 2;
begin
  null;
end;
--! expect-error: none
--! ---
--! id: EDG-BUG-DECL-006
--! layer: L9
--! req: DECLARE
--! tags: default-is-exec
-- 含 IS 的默认值在 exec 模式下正确求值
--! setup: create table t(v int)
declare
  a int;
  x boolean default (a is null);
begin
  if :x then
    insert into t values(1);
  else
    insert into t values(2);
  end if;
end;
--! expect-query: select v from t | 1
--! ---
--! id: EDG-BUG-DECL-007
--! layer: L9
--! req: CURSOR
--! tags: cursor-param-is-query
-- CURSOR 带参数声明，IS 后的查询正常遮罩
--! mode: compile
declare
  cursor c(p int) is select p;
begin
  null;
end;
--! expect-error: none
