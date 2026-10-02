-- L0：多语句脚本切分
--! id: DET-023
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
drop table if exists aaa;
$$
begin
  create table aaa(num int);
  insert into aaa values(10);
end;
$$
--! ---
--! id: DET-024
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
$$
begin
  pass;
end;
$$;
select 1
--! ---
--! id: DET-025
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 3
select 1;
$$
begin
  pass;
end;
$$;
select 2
--! ---
--! id: DET-026
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
select 1; select 2
--! ---
--! id: DET-027
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
select 1;;select 2
--! ---
--! id: DET-028
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
select 1;
select 2;
--! ---
--! id: DET-029
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
-- 未包装的块 + 后续语句：块必须靠 BEGIN/END 深度合并，不能被内部分号拆散
drop table if exists aaa;
declare
  x int;
begin
  let x = 1;
  insert into aaa values(:x);
end;
--! ---
--! id: DET-030
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 3
select 1;
do $$
begin
  insert into t values(1);
end;
$$;
select 2
--! ---
--! id: DET-031
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 3
select 1;
declare
  x int;
begin
  begin
    let x = 1;
  end;
  insert into t values(:x);
end;
select 2
--! ---
--! id: DET-032
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
insert into t values('a;b');
select 1
--! ---
--! id: DET-033
--! layer: L0
--! mode: detect
--! expect-kind: SQL
-- 未闭合的分号在字符串里 → 只有一条语句
insert into t values('a;b')
--! ---
--! id: DET-034
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
insert into t values('a''b;c');
select 2
--! ---
--! id: DET-035
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
select "col;x" from t;
select 2
--! ---
--! id: DET-036
--! layer: L0
--! mode: detect
--! expect-kind: SQL
-- 行注释里的分号不算边界
select 1 -- ; 这里的分号在注释里
--! ---
--! id: DET-037
--! layer: L0
--! mode: detect
--! expect-kind: SQL
select /* ; */ 1
--! ---
--! id: DET-038
--! layer: L0
--! mode: detect
--! expect-kind: SQL
select $t$ ; $t$
--! ---
--! id: DET-039
--! layer: L0
--! mode: detect
--! expect-kind: SQL
select (select 1); -- 括号里的分号？这里没有
--! ---
--! id: DET-040
--! layer: L0
--! mode: detect
--! expect-kind: SQL
select case when 1=1 then 'a' else 'b' end
--! ---
--! id: DET-041
--! layer: L0
--! mode: detect
--! expect-kind: SQL
select end_col, if_col, loop_count from t
--! ---
--! id: DET-042
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
select 1;
if_not_a_keyword_col from t
