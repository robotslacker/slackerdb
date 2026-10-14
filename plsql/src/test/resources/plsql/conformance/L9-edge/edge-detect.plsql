-- L9：检测/切分边界
--! id: EDG-DET-001
--! layer: L9
--! mode: detect
--! expect-kind: BLOCK
-- 单块里含 IF（END IF 不应算作块结束）
begin
  if x > 0 then
    null;
  end if;
end;
--! ---
--! id: EDG-DET-002
--! layer: L9
--! mode: detect
--! expect-kind: BLOCK
-- 单块里含 WHILE（END LOOP 不应算作块结束）
begin
  while x < 3 loop
    null;
  end loop;
end;
--! ---
--! id: EDG-DET-003
--! layer: L9
--! mode: detect
--! expect-kind: BLOCK
-- 单块里含 FOR（END LOOP 不应算作块结束）
begin
  for i in 1..5 loop
    null;
  end loop;
end;
--! ---
--! id: EDG-DET-004
--! layer: L9
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 3
-- 块出现在中间：块不能被内部分号拆散
select 1;
begin
  insert into t values(1);
  insert into t values(2);
end;
select 2
--! ---
--! id: EDG-DET-005
--! layer: L9
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
-- 块 + 后续语句（无末尾分号）
begin
  pass;
end;
select 2
--! ---
--! id: EDG-DET-006
--! layer: L9
--! mode: detect
--! expect-kind: BLOCK
-- BEGIN 后跟词 END 是空块（不是事务）
begin end;
--! ---
--! id: EDG-DET-007
--! layer: L9
--! mode: detect
--! expect-kind: SQL
-- BEGIN WORK 是事务控制，不是块
begin work;
--! ---
--! id: EDG-DET-008
--! layer: L9
--! mode: detect
--! expect-kind: SQL
-- BEGIN READ ONLY 是事务控制
begin read only;
--! ---
--! id: EDG-DET-009
--! layer: L9
--! mode: detect
--! expect-kind: BLOCK
-- 单块内含内层块：整体是一条 BLOCK
begin
  begin
    pass;
  end;
  pass;
end;
--! ---
--! id: EDG-DET-010
--! layer: L9
--! mode: detect
--! expect-kind: EMPTY
-- 只有注释（含分号）→ EMPTY
-- just a comment; with a semicolon
--! ---
--! id: EDG-DET-011
--! layer: L9
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
-- 分号后的空语句被忽略，不增加计数
select 1;;select 2;
--! ---
--! id: EDG-DET-012
--! layer: L9
--! mode: detect
--! expect-kind: BLOCK
-- DECLARE 开头的块（无显式 BEGIN 之前的声明段）
declare
  x int;
begin
  let x = 1;
end;
