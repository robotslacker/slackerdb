--! id: CSM-001
--! layer: L5
--! req: CURSOR
--! tags: state-machine
-- 未 OPEN 就 FETCH → 24000
--! setup: create table src(a int)
--! setup: create table t(v int)
declare
  cursor c is select a from src;
  x int;
begin
  fetch c into :x;
  insert into t values(1);
end;
--! expect-error: not open
--! ---
--! id: CSM-002
--! layer: L5
--! req: CURSOR
--! tags: state-machine
-- CLOSE 未打开的游标 → 24000
--! setup: create table src(a int)
--! setup: create table t(v int)
declare
  cursor c is select a from src;
begin
  close c;
  insert into t values(1);
end;
--! expect-error: not open
--! ---
--! id: CSM-003
--! layer: L5
--! req: CURSOR
--! tags: state-machine
-- 重复 OPEN → 24000
--! setup: create table src(a int)
--! setup: create table t(v int)
declare
  cursor c is select a from src;
begin
  open c;
  open c;
  insert into t values(1);
end;
--! expect-error: already open
--! ---
--! id: CSM-004
--! layer: L5
--! req: CURSOR
--! tags: state-machine
-- FETCH 后再 CLOSE 再 FETCH → 24000
--! setup: create table src(a int)
--! setup: insert into src values(1)
--! setup: create table t(v int)
declare
  cursor c is select a from src;
  x int;
begin
  open c;
  fetch c into :x;
  close c;
  fetch c into :x;
  insert into t values(1);
end;
--! expect-error: not open
--! ---
--! id: CSM-005
--! layer: L5
--! req: CURSOR
--! tags: state-machine, isopen, rowcount
-- %ISOPEN / %ROWCOUNT 在状态迁移中的取值
-- （游标属性是 PL/SQL 表达式，必须先赋给变量再绑定进 SQL）
--! setup: create table src(a int)
--! setup: insert into src values(1),(2),(3)
--! setup: create table t(v int)
declare
  cursor c is select a from src order by a;
  x int;
  v int;
begin
  let v = case when c%isopen then 1 else 0 end;
  insert into t values(:v);
  open c;
  let v = case when c%isopen then 1 else 0 end;
  insert into t values(:v);
  fetch c into :x;
  let v = c%rowcount;
  insert into t values(:v);
  fetch c into :x;
  let v = c%rowcount;
  insert into t values(:v);
  close c;
  let v = case when c%isopen then 1 else 0 end;
  insert into t values(:v);
end;
--! expect-query: select v from t | 0 ; 1 ; 1 ; 2 ; 0
--! ---
--! id: CSM-006
--! layer: L5
--! req: CURSOR
--! tags: state-machine, eof
-- 到 EOF 后再次 FETCH 幂等（%NOTFOUND 保持 true，变量保持原值）
--! setup: create table src(a int)
--! setup: insert into src values(7)
--! setup: create table t(v int, n int)
declare
  cursor c is select a from src;
  x int;
  n int;
begin
  open c;
  fetch c into :x;
  fetch c into :x;
  fetch c into :x;
  let n = case when c%notfound then 1 else 0 end;
  insert into t values(:x, :n);
  close c;
end;
--! expect-query: select v, n from t | 7,1
--! ---
--! id: CSM-007
--! layer: L5
--! req: CURSOR
--! tags: state-machine, reopen
-- CLOSE 之后可以重新 OPEN，%ROWCOUNT 归零
--! setup: create table src(a int)
--! setup: insert into src values(1),(2)
--! setup: create table t(v int)
declare
  cursor c is select a from src order by a;
  x int;
  r int;
begin
  open c;
  fetch c into :x;
  close c;
  open c;
  let r = c%rowcount;
  insert into t values(:r);
  fetch c into :x;
  insert into t values(:x);
  close c;
end;
--! expect-query: select v from t | 0 ; 1
--! ---
--! id: CSM-008
--! layer: L5
--! req: CURSOR
--! tags: state-machine, into-mismatch
-- FETCH 目标数与列数不符 → 24000
--! setup: create table src(a int, b int)
--! setup: insert into src values(1, 2)
--! setup: create table t(v int)
declare
  cursor c is select a, b from src;
  x int;
begin
  open c;
  fetch c into :x;
  insert into t values(1);
end;
--! expect-error: target count
--! ---
--! id: CSM-009
--! layer: L5
--! req: CURSOR
--! tags: state-machine, auto-close
-- 块结束时（含异常路径）自动关闭块内游标；再次 OPEN 不报"已打开"
--! setup: create table src(a int)
--! setup: insert into src values(1)
--! setup: create table t(v int)
declare
  cursor c is select a from src;
  x int;
begin
  open c;
  fetch c into :x;
  begin
    begin
      insert into no_such_table values(1);
    exception
      when others then
        insert into t values(1);
    end;
  end;
  close c;
  insert into t values(2);
end;
--! expect-query: select v from t order by v | 1 ; 2
--! ---
--! id: CSM-010
--! layer: L5
--! req: CURSOR
--! tags: state-machine, nested-scope
-- 内层块的游标不影响外层（内层退出只关自己的游标）
--! setup: create table src(a int)
--! setup: insert into src values(1),(2)
--! setup: create table t(v int)
declare
  cursor c1 is select a from src order by a;
  x int;
begin
  open c1;
  fetch c1 into :x;
  insert into t values(:x);
  begin
    declare
      cursor c2 is select a from src order by a;
      y int;
    begin
      open c2;
      fetch c2 into :y;
      insert into t values(:y);
      close c2;
    end;
  end;
  fetch c1 into :x;
  insert into t values(:x);
  close c1;
end;
--! expect-query: select v from t order by v | 1 ; 1 ; 2
