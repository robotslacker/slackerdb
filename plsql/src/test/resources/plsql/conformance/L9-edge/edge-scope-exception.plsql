--! id: EDG-SCO-001
--! layer: L9
--! req: SCOPE
--! tags: shadow
-- 内层块遮蔽外层变量，内层退出后外层值不变
--! setup: create table t(v int)
declare
  x int;
begin
  let x = 10;
  begin
    declare
      y int;
    begin
      let y = 99;
      let x = x + 1;
      insert into t values(:y);
    end;
  end;
  insert into t values(:x);
end;
--! expect-query: select v from t | 99 ; 11
--! ---
--! id: EDG-SCO-002
--! layer: L9
--! req: SCOPE
--! tags: shadow
-- 内层同名声明遮蔽外层；内层赋值不影响外层
--! setup: create table t(v int)
declare
  x int;
begin
  let x = 5;
  begin
    declare
      x int;
    begin
      let x = 7;
    end;
  end;
  insert into t values(:x);
end;
--! expect-query: select v from t | 5
--! ---
--! id: EDG-SCO-003
--! layer: L9
--! req: EXCEPTION
--! tags: handler-continue
-- 异常被捕获后继续执行块后续语句
--! setup: create table t(i int)
begin
  raise my_err;
  insert into t values(1);
exception
  when my_err then
    insert into t values(2);
end;
--! expect-query: select i from t | 2
--! ---
--! id: EDG-SCO-004
--! layer: L9
--! req: EXCEPTION
--! tags: handler-not-run, no-exception
-- 无异常时 handler 里插入不执行
--! setup: create table t(i int)
begin
  null;
exception
  when others then
    insert into t values(9);
end;
--! expect-query: select count(*) from t | 0
--! ---
--! id: EDG-SCO-005
--! layer: L9
--! req: EXCEPTION
--! tags: oth-not-defined
-- 错误名只匹配同名字；未匹配则冒泡为未处理异常
--! setup: create table t(i int)
begin
  raise abc;
exception
  when xyz then
    insert into t values(1);
end;
--! expect-error: Unhandled PL/SQL exception [abc]
--! ---
--! id: EDG-SCO-006
--! layer: L9
--! req: EXCEPTION
--! tags: reraise-nested
-- 内层处理块 RAISE 重抛，外层不同名 handler 不捕获 → 未处理
--! setup: create table t(i int)
begin
  begin
    raise my_bug;
  exception
    when my_bug then
      insert into t values(1);
      raise;
  end;
exception
  when some_other then
    insert into t values(2);
end;
--! expect-error: Unhandled PL/SQL exception [my_bug]
--! ---
--! id: EDG-SCO-007
--! layer: L9
--! req: RETURN
--! tags: return-early
-- RETURN 提前结束块（后续语句不执行）
--! setup: create table t(i int)
begin
  insert into t values(1);
  return;
  insert into t values(2);
end;
--! expect-query: select i from t | 1
--! ---
--! id: EDG-SCO-008
--! layer: L9
--! req: EXCEPTION
--! tags: exception-in-nested
-- 内层块异常被内层捕获，外层正常继续
--! setup: create table t(i int)
begin
  begin
    raise boom;
  exception
    when boom then
      insert into t values(1);
  end;
  insert into t values(2);
end;
--! expect-query: select i from t order by i | 1 ; 2
--! ---
--! id: EDG-SCO-009
--! layer: L9
--! req: EXCEPTION
--! tags: epsilon-hands
-- EXCEPTION 段必须捕获本块异常，且只执行匹配的第一个 handler
--! setup: create table t(i int)
begin
  raise e1;
exception
  when e1 then
    insert into t values(1);
  when e1 then
    insert into t values(2);
end;
--! expect-query: select i from t | 1
--! ---
--! id: EDG-SCO-010
--! layer: L9
--! req: DECLARE
--! tags: notnull-null, error
-- NOT NULL 变量赋 NULL：报错（可被捕获）
--! setup: create table t(i int)
declare
  x int not null;
begin
  let x = null;
  insert into t values(9);
exception
  when others then
    insert into t values(3);
end;
--! expect-query: select i from t | 3
