-- EXECUTE IMMEDIATE 的文法（仅解析，不执行）

--! id: DYNG-001
--! layer: L1
--! mode: parse
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, grammar
--! expect-ast: block(decls=[var,var,var,var],stmts=[executeImmediate:into1:using2],handlers=[])
declare
  sql_text text;
  m int;
  x int;
  y int;
begin
  execute immediate sql_text into m using x, y;
end;
--! ---
--! id: DYNG-002
--! layer: L1
--! mode: parse
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, grammar
--! expect-ast: block(decls=[var,var],stmts=[executeImmediate:into2],handlers=[])
declare
  m int;
  s text;
begin
  execute immediate 'select 1, ''a''' into m, s;
end;
--! ---
--! id: DYNG-003
--! layer: L1
--! mode: parse
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, grammar
--! expect-ast: block(decls=[],stmts=[executeImmediate],handlers=[])
begin
  execute immediate 'commit';
end;
--! ---
--! id: DYNG-004
--! layer: L1
--! mode: parse
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, grammar
--! expect-ast: block(decls=[var,var,var],stmts=[executeImmediate:into1:using1],handlers=[])
declare
  sql_text text;
  m int;
  x int;
begin
  execute immediate sql_text || ' where i = ?' into m using x;
end;
--! ---
-- 未跟 IMMEDIATE 的 EXECUTE 不是动态 SQL，仍按普通（被遮罩的）SQL 处理
--! id: DYNG-005
--! layer: L1
--! mode: parse
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, grammar
--! expect-ast: block(decls=[],stmts=[sql],handlers=[])
begin
  execute some_prepared(1);
end;
--! ---
