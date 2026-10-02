--! id: CUR-001
--! layer: L5
--! req: SELECT INTO
--! tags: smoke
--! setup: create table src(a int, b int)
--! setup: insert into src values (3, 4)
--! setup: create table t(x int, y int)
declare
  x int;
  y int;
begin
  select a, b into :x, :y from src;
  insert into t values(:x, :y);
end;
--! expect-query: select x, y from t | 3,4
--! ---
--! id: CUR-002
--! layer: L5
--! req: SELECT INTO
--! tags: flipped-p6
-- 未初始化变量为 NULL，且 SELECT INTO 0 行必须抛 NO_DATA_FOUND（不再静默插入 NULL）
--! setup: create table src(a int, b int)
--! setup: create table t(x int, y int)
declare
  x int;
  y int;
begin
  select a, b into :x, :y from src;
  insert into t values(:x, :y);
end;
--! expect-error: no data found
--! ---
--! id: CUR-003
--! layer: L5
--! req: SELECT INTO
--! tags: flipped-p6
-- SELECT INTO 多行必须抛 TOO_MANY_ROWS（不再静默取第一行）
--! setup: create table src(a int, b int)
--! setup: insert into src values (1, 2), (3, 4)
--! setup: create table t(x int, y int)
declare
  x int;
  y int;
begin
  select a, b into :x, :y from src order by a;
  insert into t values(:x, :y);
end;
--! expect-error: too many rows
--! ---
--! id: CUR-004
--! layer: L5
--! req: FETCH INTO
--! tags: eof
--! setup: create table src(a int)
--! setup: insert into src values (5)
--! setup: create table t(x int)
declare
  cursor c is select a from src;
  x int;
begin
  open c;
  fetch c into :x;
  fetch c into :x;
  close c;
  insert into t values(:x);
end;
--! expect-query: select x from t | 5
--! ---
--! id: CUR-005
--! layer: L5
--! req: FETCH INTO
--! tags: eof, empty
--! setup: create table src(a int)
--! setup: create table t(v int)
declare
  cursor c is select a from src;
  x int;
begin
  open c;
  loop
    fetch c into :x;
    exit when c%notfound;
    insert into t values(:x);
  end loop;
  close c;
end;
--! expect-query: select count(*) from t | 0
--! ---
--! id: CUR-006
--! layer: L5
--! req: FETCH INTO
--! tags: multi-column
--! setup: create table src(a int, b text)
--! setup: insert into src values (1,'x'),(2,'y'),(3,'z')
--! setup: create table t(a int, b text)
declare
  cursor c is select a, b from src order by a;
  x int;
  y text;
begin
  open c;
  loop
    fetch c into :x, :y;
    exit when c%notfound;
    insert into t values(:x, :y);
  end loop;
  close c;
end;
--! expect-query: select a, b from t order by a | 1,x ; 2,y ; 3,z
