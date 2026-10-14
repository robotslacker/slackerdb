--! id: EDG-TYP-001
--! layer: L9
--! req: TYPES
--! tags: num-to-text
-- 数字赋给文本变量→文本
--! setup: create table t(s text)
declare
  s text;
begin
  let s = 42;
  insert into t values(:s);
end;
--! expect-query: select s from t | 42
--! ---
--! id: EDG-TYP-002
--! layer: L9
--! req: TYPES
--! tags: text-to-num
-- 数字文本赋给整数变量→整数
--! setup: create table t(v int)
declare
  v int;
begin
  let v = '123';
  insert into t values(:v);
end;
--! expect-query: select v from t | 123
--! ---
--! id: EDG-TYP-003
--! layer: L9
--! req: TYPES
--! tags: bad-text-to-num, error
-- 非数字文本赋给整数变量→报错
--! setup: create table t(v int)
declare
  v int;
begin
  let v = 'abc';
  insert into t values(9);
exception
  when others then
    insert into t values(8);
end;
--! expect-query: select v from t | 8
--! ---
--! id: EDG-TYP-004
--! layer: L9
--! req: TYPES
--! tags: null-var
-- 未初始化整数变量是 NULL；插入 NULL
--! setup: create table t(v int)
declare
  v int;
begin
  insert into t values(:v);
end;
--! expect-query: select v from t | null
--! ---
--! id: EDG-TYP-005
--! layer: L9
--! req: TYPES
--! tags: rounding
-- 文本赋整数变量时小数四舍五入取整（HALF_UP）
--! setup: create table t(v int)
declare
  v int;
begin
  let v = '3.6';
  insert into t values(:v);
end;
--! expect-query: select v from t | 4
--! ---
--! id: EDG-TYP-006
--! layer: L9
--! req: TYPES
--! tags: int-overflow, error
-- 超出 INT 范围赋给 INT→报错
--! setup: create table t(v int)
declare
  v int;
begin
  let v = 2147483648;
  insert into t values(9);
exception
  when others then
    insert into t values(8);
end;
--! expect-query: select v from t | 8
--! ---
--! id: EDG-TYP-007
--! layer: L9
--! req: TYPES
--! tags: concat
-- || 文本拼接
--! setup: create table t(s text)
declare
  a text;
begin
  let a = 'foo';
  insert into t values(:a || 'bar');
end;
--! expect-query: select s from t | foobar
--! ---
--! id: EDG-TYP-008
--! layer: L9
--! req: TYPES
--! tags: concat-null
-- 文本拼接含 NULL→NULL（Oracle 语义）
--! setup: create table t(s text)
declare
  a text;
  b text;
begin
  let b = 'x';
  insert into t values(:a || :b);
  insert into t values('ok');
end;
--! expect-query: select s from t | null ; ok
--! ---
--! id: EDG-TYP-009
--! layer: L9
--! req: TYPES
--! tags: boolean
-- 布尔变量赋值与条件
--! setup: create table t(v int)
declare
  flag boolean;
begin
  let flag = true;
  if :flag then
    insert into t values(1);
  else
    insert into t values(2);
  end if;
end;
--! expect-query: select v from t | 1
--! ---
--! id: EDG-TYP-010
--! layer: L9
--! req: TYPES
--! tags: decode-varchar-len, error
-- VARCHAR(2) 赋值超长→报错
--! setup: create table t(v text)
declare
  s varchar(2);
begin
  let s = 'toolong';
  insert into t values(9);
exception
  when others then
    insert into t values(8);
end;
--! expect-query: select v from t | 8
--! ---
--! id: EDG-TYP-011
--! layer: L9
--! req: TYPES
--! tags: decimal-round
-- DECIMAL(p,s) 按标度舍入后转文本
--! setup: create table t(s text)
declare
  d decimal(6,2);
  s text;
begin
  let d = 3.456;
  let s = :d;
  insert into t values(:s);
end;
--! expect-query: select s from t | 3.46
