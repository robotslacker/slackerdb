-- L0：包装识别与前缀噪声
--! id: DET-001
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
--! expect-statements: 1
DO $$
begin
  pass;
end;
$$
--! ---
--! id: DET-002
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
do $$
begin
  pass;
end;
$$
--! ---
--! id: DET-003
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
DO $body$
begin
  insert into t values('$$');
end;
$body$
--! ---
--! id: DET-004
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
$$
begin
  pass;
end;
$$
--! ---
--! id: DET-005
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
$$declare
  x int;
begin
  let x = 1;
end;$$
--! ---
--! id: DET-006
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
DO $$ begin pass; end; $$;
--! ---
--! id: DET-007
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
-- 前导行注释
DO $$
begin
  pass;
end;
$$
--! ---
--! id: DET-008
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
/* 前导块注释 */
-- 再来一行
   DO $$
   begin
     pass;
   end;
   $$
--! ---
--! id: DET-009
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
declare
  x int;
begin
  let x = 1;
end;
--! ---
--! id: DET-010
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
declare
  cursor c is select 1;
begin
  open c;
  close c;
end;
--! ---
--! id: DET-011
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
begin
  insert into t values(1);
end;
--! ---
--! id: DET-012
--! layer: L0
--! mode: detect
--! expect-kind: SQL
begin;
--! ---
--! id: DET-013
--! layer: L0
--! mode: detect
--! expect-kind: SQL
BEGIN WORK
--! ---
--! id: DET-014
--! layer: L0
--! mode: detect
--! expect-kind: SQL
begin transaction;
--! ---
--! id: DET-015
--! layer: L0
--! mode: detect
--! expect-kind: SQL
start transaction
--! ---
--! id: DET-016
--! layer: L0
--! mode: detect
--! expect-kind: SQL
select 1
--! ---
--! id: DET-017
--! layer: L0
--! mode: detect
--! expect-kind: EMPTY
--! ---
--! id: DET-018
--! layer: L0
--! mode: detect
--! expect-kind: EMPTY
-- 只有注释
/* 什么都没有 */
--! ---
--! id: DET-019
--! layer: L0
--! mode: detect
--! expect-kind: EMPTY

   
--! ---
--! id: DET-020
--! layer: L0
--! mode: detect
--! expect-kind: SQL
insert into t values(1)
--! ---
--! id: DET-021
--! layer: L0
--! mode: detect
--! expect-kind: SQL
create table t(i int)
--! ---
--! id: DET-022
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
--! expect-statements: 1
DO $$
declare
  x int;
begin
  -- 块内出现 begin/end 关键字（字符串里）
  insert into t values('begin');
  insert into t values('end');
end;
$$
