--! id: EDG-CUR-001
--! layer: L9
--! req: CURSOR
--! tags: open-twice, error
-- 重复打开已打开的游标报错
--! setup: create table src(a int)
--! setup: insert into src values (1)
declare
  cursor c is select a from src;
begin
  open c;
  open c;
end;
--! expect-error: already open
--! ---
--! id: EDG-CUR-002
--! layer: L9
--! req: CURSOR
--! tags: close-unopened, error
-- 关闭未打开的游标报错
--! setup: create table src(a int)
declare
  cursor c is select a from src;
begin
  close c;
end;
--! expect-error: is not open
--! ---
--! id: EDG-CUR-003
--! layer: L9
--! req: CURSOR
--! tags: fetch-unopened, error
-- 对未打开的游标 FETCH 报错
--! setup: create table src(a int)
declare
  cursor c is select a from src;
  x int;
begin
  fetch c into :x;
end;
--! expect-error: is not open
--! ---
--! id: EDG-CUR-004
--! layer: L9
--! req: CURSOR
--! tags: rowcount
-- %ROWCOUNT 统计已取行数；到达 EOF 后保持
--! setup: create table src(a int)
--! setup: insert into src values (10),(20),(30)
--! setup: create table t(v int)
declare
  cursor c is select a from src order by a;
  x int;
  r int;
begin
  open c;
  fetch c into :x;
  let r = c%rowcount;
  insert into t values(:r);
  fetch c into :x;
  let r = c%rowcount;
  insert into t values(:r);
  fetch c into :x;
  let r = c%rowcount;
  insert into t values(:r);
  close c;
end;
--! expect-query: select v from t order by v | 1 ; 2 ; 3
--! ---
--! id: EDG-CUR-005
--! layer: L9
--! req: CURSOR
--! tags: isopen
-- %ISOPEN 反映游标开闭状态
--! setup: create table t(v int)
declare
  cursor c is select 1;
begin
  if c%isopen then
    insert into t values(1);
  else
    insert into t values(2);
  end if;
  open c;
  if c%isopen then
    insert into t values(3);
  end if;
  close c;
end;
--! expect-query: select v from t order by v | 2 ; 3
--! ---
--! id: EDG-CUR-006
--! layer: L9
--! req: CURSOR
--! tags: found-after-fetch
-- 成功取到一行后 %FOUND 为真
--! setup: create table src(a int)
--! setup: insert into src values (7)
--! setup: create table t(v int)
declare
  cursor c is select a from src;
  x int;
begin
  open c;
  fetch c into :x;
  if c%found then
    insert into t values(1);
  end if;
  close c;
end;
--! expect-query: select v from t | 1
--! ---
--! id: EDG-CUR-007
--! layer: L9
--! req: CURSOR
--! tags: reopen
-- 关闭后可重新打开，重新读取数据
--! setup: create table src(a int)
--! setup: insert into src values (5)
--! setup: create table t(v int)
declare
  cursor c is select a from src;
  x int;
begin
  open c;
  fetch c into :x;
  close c;
  open c;
  fetch c into :x;
  close c;
  insert into t values(:x);
end;
--! expect-query: select v from t | 5
--! ---
--! id: EDG-CUR-008
--! layer: L9
--! req: CURSOR
--! tags: notfound-eof
-- 取完所有行后 %NOTFOUND 为真
--! setup: create table src(a int)
--! setup: insert into src values (1),(2)
--! setup: create table t(v int)
declare
  cursor c is select a from src order by a;
  x int;
begin
  open c;
  loop
    fetch c into :x;
    exit when c%notfound;
  end loop;
  if c%notfound then
    insert into t values(1);
  end if;
  close c;
end;
--! expect-query: select v from t | 1
