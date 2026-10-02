--! id: DECL-001
--! layer: L5
--! req: DECLARE
--! tags: smoke
--! setup: create table t(i int)
declare
  x int;
begin
  let x = 7;
  insert into t values(:x);
end;
--! expect-query: select i from t | 7
--! ---
--! id: DECL-002
--! layer: L5
--! req: DECLARE
--! tags: smoke, types
--! setup: create table t(col1 int, col2 text, col3 double, col4 bigint, col5 date, col6 timestamp, col7 float)
declare
  x int;
  y text;
  z double;
  m bigint;
  n date;
  o timestamp;
  p float;
begin
  let x = 3;
  let y = 'Hello World';
  let z = 1.2;
  let m = 20002;
  let n = '2024-07-08';
  let o = '2024-10-08 23:12:31';
  let p = 3.04;
  insert into t values(:x,:y,:z,:m,:n,:o,:p);
end;
--! expect-query: select col1, col2, col3, col4, col5, col6, col7 from t | 3,Hello World,1.2,20002,2024-07-08,2024-10-08 23:12:31.0,3.04
--! ---
--! id: DECL-003
--! layer: L5
--! req: DECLARE
--! tags: expression
--! setup: create table t(s text)
declare
  y text;
begin
  let y = 'ab';
  let y = :y || :y;
  insert into t values(:y);
end;
--! expect-query: select s from t | abab
--! ---
--! id: DECL-004
--! layer: L5
--! req: DECLARE
--! tags: expression
--! setup: create table t(i int, d double)
declare
  x int;
  z double;
begin
  let x = 3;
  let z = 1.25;
  let x = :x * 2;
  let z = :z * 2;
  insert into t values(:x, :z);
end;
--! expect-query: select i, d from t | 6,2.5
--! ---
--! id: DECL-005
--! layer: L5
--! req: DECLARE
--! tags: identifier
--! setup: create table main.t5(i int)
declare
  x int;
begin
  insert into main.t5 values(10);
  let x = 5;
  insert into main.t5 values(:x);
end;
--! expect-query: select i from main.t5 order by i | 5 ; 10
--! ---
--! id: DECL-006
--! layer: L5
--! req: DECLARE
--! tags: sql
--! setup: create table t(i int, s text)
declare
  x int;
  y text;
begin
  let x = 10;
  let y = 'Hello World';
  insert into t values(:x, :y);
  insert into t values(:x + 1, :y || ' Me');
end;
--! expect-query: select i, s from t order by i | 10,Hello World ; 11,Hello World Me
