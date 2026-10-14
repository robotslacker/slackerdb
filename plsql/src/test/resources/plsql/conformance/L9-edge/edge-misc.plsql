--! id: EDG-MIS-001
--! layer: L9
--! req: EXIT
--! tags: exit-outside-loop, error
-- EXIT 在循环外 → 报错
begin
  exit;
end;
--! expect-error: EXIT/CONTINUE used outside of a loop
--! ---
--! id: EDG-MIS-002
--! layer: L9
--! req: LOOP
--! tags: continue-outside-loop, error
-- CONTINUE 在循环外 → 报错
begin
  continue;
end;
--! expect-error: EXIT/CONTINUE used outside of a loop
--! ---
--! id: EDG-MIS-003
--! layer: L9
--! req: LOOP
--! tags: assign-loop-var, error
-- 给 FOR 循环变量赋值 → 报错
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 1..3 loop
    let i = 9;
  end loop;
end;
--! expect-error: Cannot assign to loop variable
--! ---
--! id: EDG-MIS-004
--! layer: L9
--! req: EXCEPTION
--! tags: raise-no-active, error
-- 无活动异常时 RAISE; → 报错
begin
  raise;
end;
--! expect-error: RAISE without an active exception
--! ---
--! id: EDG-MIS-005
--! layer: L9
--! req: EXCEPTION
--! tags: exc-in-handler
-- 处理块内出现新异常 → 冒泡给外层
--! setup: create table t(i int)
begin
  begin
    raise e1;
  exception
    when e1 then
      raise e2;
  end;
exception
  when e2 then
    insert into t values(2);
end;
--! expect-query: select i from t | 2
--! ---
--! id: EDG-MIS-006
--! layer: L9
--! req: DECLARE
--! tags: default-ref-earlier
-- DEFAULT 初始值可引用先前声明的变量
--! setup: create table t(v int)
declare
  a int default 3;
  b int default :a * 2;
begin
  insert into t values(:b);
end;
--! expect-query: select v from t | 6
--! ---
--! id: EDG-MIS-007
--! layer: L9
--! req: DECLARE
--! tags: assign-forms
-- := 与 LET 两种赋值等价
--! setup: create table t(v int)
declare
  a int;
begin
  a := 10;
  insert into t values(:a);
end;
--! expect-query: select v from t | 10
--! ---
--! id: EDG-MIS-008
--! layer: L9
--! req: EXECUTE IMMEDIATE
--! tags: cast-preserved
-- 动态 SQL 里的 :: 类型转换原样保留，不被当占位符
--! setup: create table t(s text)
declare
  v int;
begin
  let v = 7;
  execute immediate 'insert into t select ?::text' using :v;
end;
--! expect-query: select s from t | 7
--! ---
--! id: EDG-MIS-009
--! layer: L9
--! req: SELECT INTO
--! tags: cast-in-select
-- SELECT INTO 语句里的 :: 转换
--! setup: create table src(a int)
--! setup: insert into src values (5)
--! setup: create table t(s text)
declare
  s text;
begin
  select a::text into :s from src;
  insert into t values(:s);
end;
--! expect-query: select s from t | 5
--! ---
--! id: EDG-MIS-010
--! layer: L9
--! req: WHILE
--! tags: break-when
-- BREAK WHEN 等价 EXIT WHEN
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 1..10 loop
    if :i = 3 then
      break;
    end if;
    insert into t values(:i);
  end loop;
end;
--! expect-query: select v from t order by v | 1 ; 2
--! ---
--! id: EDG-MIS-011
--! layer: L9
--! req: EXCEPTION
--! tags: multiple-handler
-- 多 handler 自顶而下首个匹配
--! setup: create table t(i int)
begin
  raise b;
exception
  when a then
    insert into t values(1);
  when b or c then
    insert into t values(2);
  when others then
    insert into t values(3);
end;
--! expect-query: select i from t | 2
--! ---
--! id: EDG-MIS-012
--! layer: L9
--! req: SQL
--! tags: sql-error-catch
-- 非语法 SQL 错误可被 EXCEPTION 捕获
--! setup: create table t(i int)
begin
  select * from nonexistent_table_name_xyz;
exception
  when others then
    insert into t values(1);
end;
--! expect-query: select i from t | 1
