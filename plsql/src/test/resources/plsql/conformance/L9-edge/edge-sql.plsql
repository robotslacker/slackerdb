--! id: EDG-SQL-001
--! layer: L9
--! req: BIND
--! tags: null-bind
-- :name 绑定 NULL → SQL NULL
--! setup: create table t(v int)
declare
  x int;
begin
  insert into t values(:x);
end;
--! expect-query: select v from t | null
--! ---
--! id: EDG-SQL-002
--! layer: L9
--! req: BIND
--! tags: multi-bind
-- 多条绑定按顺序
--! setup: create table t(v int)
declare
  a int;
  b int;
begin
  let a = 1;
  let b = 2;
  insert into t values(:a + :b);
  insert into t values(:b - :a);
end;
--! expect-query: select v from t order by v | 1 ; 3
--! ---
--! id: EDG-SQL-003
--! layer: L9
--! req: BIND
--! tags: bind-keyword-name
-- 变量名与 SQL 关键字同名仍可绑定（下面用非关键字名避免歧义）
--! setup: create table t(v int)
declare
  x int;
begin
  let x = 5;
  insert into t values(:x);
end;
--! expect-query: select v from t | 5
--! ---
--! id: EDG-SQL-004
--! layer: L9
--! req: BIND
--! tags: undeclared-bind, error
-- SQL 里引用未声明 :name → 报错
--! setup: create table t(v int)
begin
  insert into t values(:nosuch);
end;
--! expect-error: has not been declared
--! ---
--! id: EDG-SQL-005
--! layer: L9
--! req: BIND
--! tags: bind-in-where
-- 绑定用于 WHERE 过滤
--! setup: create table t(v int)
--! setup: insert into t values (1),(2),(3)
declare
  x int;
begin
  let x = 2;
  delete from t where v = :x;
end;
--! expect-query: select v from t order by v | 1 ; 3
--! ---
--! id: EDG-SQL-006
--! layer: L9
--! req: BIND
--! tags: string-var-bind
-- 文本变量绑定到 SQL 字符串比较
--! setup: create table t(s text)
--! setup: insert into t values ('a'),('b')
declare
  s text;
begin
  let s = 'b';
  delete from t where s = :s;
end;
--! expect-query: select s from t | a
--! ---
--! id: EDG-SQL-007
--! layer: L9
--! req: EXECUTE IMMEDIATE
--! tags: using-simple
-- EXECUTE IMMEDIATE 用 USING 绑定一个值
--! setup: create table t(v int)
declare
  v int;
begin
  let v = 42;
  execute immediate 'insert into t values (?)' using :v;
end;
--! expect-query: select v from t | 42
--! ---
--! id: EDG-SQL-008
--! layer: L9
--! req: EXECUTE IMMEDIATE
--! tags: using-multi, error
-- USING 值多于占位符 → 报错
--! setup: create table t(v int)
declare
  a int;
begin
  execute immediate 'insert into t values (1)' using :a;
end;
--! expect-error: extra value
--! ---
--! id: EDG-SQL-009
--! layer: L9
--! req: EXECUTE IMMEDIATE
--! tags: using-missing, error
-- USING 值少于占位符 → 报错
--! setup: create table t(v int)
declare
  a int;
  b int;
begin
  execute immediate 'insert into t values (?,?)' using :a;
end;
--! expect-error: missing USING value
--! ---
--! id: EDG-SQL-010
--! layer: L9
--! req: EXECUTE IMMEDIATE
--! tags: into-select
-- EXECUTE IMMEDIATE 取一行到变量
--! setup: create table src(a int)
--! setup: insert into src values (7)
--! setup: create table t(v int)
declare
  x int;
begin
  execute immediate 'select a from src' into :x;
  insert into t values(:x);
end;
--! expect-query: select v from t | 7
--! ---
--! id: EDG-SQL-011
--! layer: L9
--! req: EXECUTE IMMEDIATE
--! tags: string-concat
-- 动态 SQL 运行时拼接的字符串常量
--! setup: create table t(s text)
begin
  execute immediate 'insert into t select ''a'' || ''b''';
end;
--! expect-query: select s from t | ab
--! ---
--! id: EDG-SQL-012
--! layer: L9
--! req: EXECUTE IMMEDIATE
--! tags: placeholder-in-string-only
-- 动态 SQL 字符串内的 :x 是字面文本，不是占位符
--! setup: create table t(s text)
begin
  execute immediate 'insert into t select '':x''';
end;
--! expect-query: select s from t | ":x"
