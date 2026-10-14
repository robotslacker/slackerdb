--! id: EDG-SEM-001
--! layer: L9
--! mode: compile
--! req: SEMANTIC
--! tags: dup-decl, error
-- 重复声明变量 → 编译错误
declare
  x int;
  x int;
begin
  null;
end;
--! expect-error: declared twice
--! ---
--! id: EDG-SEM-002
--! layer: L9
--! mode: compile
--! req: SEMANTIC
--! tags: uninit-target, error
-- 赋值目标未声明 → 编译错误
begin
  y := 1;
end;
--! expect-error: has not been declared
--! ---
--! id: EDG-SEM-003
--! layer: L9
--! mode: compile
--! req: SEMANTIC
--! tags: undeclared-expr, error
-- 表达式引用未声明变量 → 编译错误
declare
  x int;
begin
  let x = 1 + z;
end;
--! expect-error: has not been declared
--! ---
--! id: EDG-SEM-004
--! layer: L9
--! mode: compile
--! req: SEMANTIC
--! tags: undeclared-cursor, error
-- OPEN 未声明游标 → 编译错误
begin
  open c;
end;
--! expect-error: Cursor [c] has not been declared
--! ---
--! id: EDG-SEM-005
--! layer: L9
--! mode: compile
--! req: SEMANTIC
--! tags: into-undeclared, error
-- SELECT INTO 目标未声明 → 编译错误
begin
  select a into :tgt from t;
end;
--! expect-error: has not been declared
--! ---
--! id: EDG-SEM-006
--! layer: L9
--! mode: compile
--! req: SEMANTIC
--! tags: if-undeclared, error
-- IF 条件引用未声明变量 → 编译错误
begin
  if n > 0 then
    null;
  end if;
end;
--! expect-error: has not been declared
--! ---
--! id: EDG-SEM-007
--! layer: L9
--! mode: exec
--! req: SEMANTIC
--! tags: bad-type, error
-- 不支持的类型声明 → 运行时报错（42601，不可捕获）
declare
  x weird_type;
begin
  null;
end;
--! expect-error: Unsupported datatype
--! ---
--! id: EDG-SEM-008
--! layer: L9
--! mode: parse
--! req: SEMANTIC
--! tags: for-var-ok
-- FOR 循环变量在体内可读（语义检查通过）
begin
  for i in 1..5 loop
    if i > 2 then
      null;
    end if;
  end loop;
end;
--! ---
--! id: EDG-SEM-009
--! layer: L9
--! mode: parse
--! req: SEMANTIC
--! tags: shadow-ok
-- 内层块遮蔽外层同名变量（可编译）
declare
  x int;
begin
  declare
    x int;
  begin
    null;
  end;
end;
