--! id: GRM-001
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[var],stmts=[sql],handlers=[])
declare
  x int;
begin
  insert into t values(1);
end;
--! ---
--! id: GRM-002
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[var,var],stmts=[],handlers=[])
declare
  x int;
  y text;
begin
end;
--! ---
--! id: GRM-003
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[var],stmts=[],handlers=[])
declare
  x int := 5;
begin
end;
--! ---
--! id: GRM-004
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[var!],stmts=[],handlers=[])
declare
  x int not null := 5;
begin
end;
--! ---
--! id: GRM-005
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[var],stmts=[],handlers=[])
declare
  x numeric(10,2);
begin
end;
--! ---
--! id: GRM-006
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[var],stmts=[],handlers=[])
declare
  v varchar(32);
begin
end;
--! ---
--! id: GRM-007
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[var],stmts=[],handlers=[])
declare
  ts timestamp with time zone;
begin
end;
--! ---
--! id: GRM-008
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[cursor],stmts=[],handlers=[])
declare
  cursor c is select a from t;
begin
end;
--! ---
--! id: GRM-009
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[cursor],stmts=[],handlers=[])
declare
  cursor c(p_id int, p_name text) is select a from t where a = p_id;
begin
end;
--! ---
--! id: GRM-010
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[var,cursor],stmts=[sql],handlers=[])
declare
  x int := 0;
  cursor c is
     select a, b
     from t
     where a > 0;
begin
  insert into t values(:x);
end;
--! ---
--! id: GRM-011
--! layer: L1
--! mode: parse
begin
  pass;
end;
--! ---
--! id: GRM-012
--! layer: L1
--! mode: parse
begin
  null;
end;
--! ---
--! id: GRM-013
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[sql,sql],handlers=[])
begin
  create or replace table t(i int);
  insert into t values(1);
end;
--! ---
--! id: GRM-014
--! layer: L1
--! mode: parse
-- SQL 里的 CASE...END、字符串内分号、$$ 都不应影响结构解析
begin
  insert into t select case when 1=1 then 'a;b' else 'c' end;
  insert into t values('$$');
end;
--! ---
--! id: GRM-015
--! layer: L1
--! mode: parse
begin
  update t set a = 1 where b = 2;
  delete from t where a = 1;
  truncate table t;
end;
--! ---
--! id: GRM-016
--! layer: L1
--! mode: parse
-- 关键字大小写混写
DeClArE
  X Int;
BeGiN
  PaSs;
EnD;
--! ---
--! id: GRM-017
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[nested[sql]],handlers=[])
begin
  begin
    insert into t values(1);
  end;
end;
--! ---
--! id: GRM-018
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[nested[sql]],handlers=[])
begin
  declare
    x int;
  begin
    insert into t values(:x);
  end;
end;
--! ---
--! id: GRM-019
--! layer: L1
--! mode: parse
begin
  commit;
  rollback;
end;
--! ---
--! id: GRM-020
--! layer: L1
--! mode: parse
begin
  return;
end;
