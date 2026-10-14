--! id: EDG-REAL-001
--! layer: L9
--! req: CURSOR
--! tags: semi-in-query
-- 游标查询的字符串里含分号，不应截断遮罩
--! setup: create table t(s text)
--! setup: insert into t values ('x')
declare
  cursor c is select 'a;b' as s;
  v text;
begin
  open c;
  fetch c into :v;
  close c;
  insert into t values(:v);
end;
--! expect-query: select s from t order by s | "a;b" ; x
--! ---
--! id: EDG-REAL-002
--! layer: L9
--! req: SELECT INTO
--! tags: with-cte
-- WITH CTE 的 SELECT INTO
--! setup: create table t(v int)
declare
  x int;
begin
  with cte as (select 42 as a)
  select a into :x from cte;
  insert into t values(:x);
end;
--! expect-query: select v from t | 42
--! ---
--! id: EDG-REAL-003
--! layer: L9
--! req: CURSOR
--! tags: multi-cursor-loop
-- 块内多个游标依次使用
--! setup: create table src(a int)
--! setup: insert into src values (1),(2)
--! setup: create table t(v int)
declare
  cursor c1 is select a from src order by a;
  cursor c2 is select a from src order by a desc;
  x int;
begin
  open c1;
  fetch c1 into :x;
  insert into t values(:x);
  close c1;
  open c2;
  fetch c2 into :x;
  insert into t values(:x);
  close c2;
end;
--! expect-query: select v from t order by v | 1 ; 2
--! ---
--! id: EDG-REAL-004
--! layer: L9
--! req: CURSOR
--! tags: outer-cursor-inner-open
-- 外层游标在内层块打开，内层退出后仍保持打开（关闭按声明作用域）
--! setup: create table src(a int)
--! setup: insert into src values (5)
--! setup: create table t(v int)
declare
  cursor c is select a from src;
  x int;
begin
  begin
    open c;
    raise my_bad;
  exception
    when my_bad then
      null;
  end;
  fetch c into :x;
  close c;
  insert into t values(:x);
end;
--! expect-query: select v from t | 5
--! ---
--! id: EDG-REAL-005
--! layer: L9
--! req: WHILE
--! tags: while-skip-with-continue
-- WHILE 循环里 CONTINUE 继续条件判断
--! setup: create table t(v int)
declare
  i int;
begin
  let i = 0;
  while :i < 3 loop
    let i = :i + 1;
    if :i = 2 then
      continue;
    end if;
    insert into t values(:i);
  end loop;
end;
--! expect-query: select v from t order by v | 1 ; 3
--! ---
--! id: EDG-REAL-006
--! layer: L9
--! req: FOR
--! tags: nested-for-accumulate
-- 嵌套 FOR 累加和
--! setup: create table t(v int)
declare
  i int;
  j int;
  sum int;
begin
  let sum = 0;
  for :i in 1..3 loop
    for :j in 1..3 loop
      let sum = :sum + 1;
    end loop;
  end loop;
  insert into t values(:sum);
end;
--! expect-query: select v from t | 9
--! ---
--! id: EDG-REAL-007
--! layer: L9
--! req: SQL
--! tags: update-with-bind
-- UPDATE 中绑定变量做运算
--! setup: create table t(v int)
--! setup: insert into t values (1),(2),(3)
declare
  n int;
begin
  let n = 10;
  update t set v = v + :n;
end;
--! expect-query: select v from t order by v | 11 ; 12 ; 13
--! ---
--! id: EDG-REAL-008
--! layer: L9
--! req: SELECT INTO
--! tags: into-after-groupby
-- SELECT INTO 聚合结果
--! setup: create table src(a int)
--! setup: insert into src values (1),(2),(3)
--! setup: create table t(v int)
declare
  x int;
begin
  select sum(a) into :x from src;
  insert into t values(:x);
end;
--! expect-query: select v from t | 6
--! ---
--! id: EDG-REAL-009
--! layer: L9
--! req: EXCEPTION
--! tags: handler-reruns-loop
-- 异常处理后重新执行一次循环
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 1..3 loop
    begin
      if :i = 2 then
        raise skip_value;
      end if;
      insert into t values(:i);
    exception
      when skip_value then
        null;
    end;
  end loop;
end;
--! expect-query: select v from t order by v | 1 ; 3
--! ---
--! id: EDG-REAL-010
--! layer: L9
--! req: SQL
--! tags: with-plain
-- 普通 WITH 内嵌 SQL（带 INTO 之外）验证 WITH 本身可遮罩执行
--! setup: create table t(v int)
begin
  insert into t with cte as (select 42 as a) select a from cte;
end;
--! expect-query: select v from t | 42
