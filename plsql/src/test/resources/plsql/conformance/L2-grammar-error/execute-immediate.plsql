-- EXECUTE IMMEDIATE 的编译期语义错误（不执行）

--! id: DYNE-001
--! layer: L2
--! mode: compile
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, semantic
--! expect-error: has not been declared
declare
  sql_text text;
begin
  execute immediate sql_text into missing_var;
end;
--! ---
--! id: DYNE-002
--! layer: L2
--! mode: compile
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, semantic
--! expect-error: has not been declared
declare
  sql_text text;
begin
  execute immediate sql_text using missing_val;
end;
--! ---
--! id: DYNE-003
--! layer: L2
--! mode: compile
--! req: EXECUTE IMMEDIATE
--! tags: dynamic, semantic
--! expect-error: has not been declared
declare
begin
  execute immediate missing_sql;
end;
--! ---
