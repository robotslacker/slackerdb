--! id: GRM-043
--! layer: L1
--! mode: parse
begin
  if x = 1 and y <> 2 or not z then null; end if;
  if x == 1 && y != 2 || z then null; end if;
  if x > 1 and x <= 2 then null; end if;
  if x >= 1 and x < 2 then null; end if;
end;
--! ---
--! id: GRM-044
--! layer: L1
--! mode: parse
begin
  if x is null then null; end if;
  if x is not null then null; end if;
  if x like 'a%' then null; end if;
  if x not like 'a%' then null; end if;
  if x in (1, 2, 3) then null; end if;
  if x not in (1) then null; end if;
  if x between 1 and 3 then null; end if;
  if x not between 1 and 3 then null; end if;
end;
--! ---
--! id: GRM-045
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[loop[exit(when)]],handlers=[])
begin
  loop
    exit when c%found or c%rowcount > 10;
  end loop;
end;
--! ---
--! id: GRM-046
--! layer: L1
--! mode: parse
begin
  let x = 1 + 2 * 3 - 4 / 2;
  let y = (1 + 2) * 3;
  let z = 2 ** 3;
  let m = 10 % 3;
  let s = 'a' || 'b';
  let n = -1;
  let b = not true;
end;
--! ---
--! id: GRM-047
--! layer: L1
--! mode: parse
begin
  let a = upper(name);
  let b = length(name) + 1;
  let c = coalesce(x, 0);
  let d = substr(name, 1, 3);
  let e = date_trunc('day', ts);
end;
--! ---
--! id: GRM-048
--! layer: L1
--! mode: parse
begin
  let c1 = case when x > 0 then 'p' when x = 0 then 'z' else 'n' end;
  let c2 = case when x > 0 then 1 end;
end;
--! ---
--! id: GRM-049
--! layer: L1
--! mode: parse
begin
  x := 1;
  x := :y + 1;
  x := x + 1;
  let x = x * 2;
end;
--! ---
--! id: GRM-050
--! layer: L1
--! mode: parse
declare
  x int := 1 + 2;
  y text := 'a' || 'b';
  z boolean := 1 < 2;
  b boolean default true;
begin
end;
--! ---
--! id: GRM-051
--! layer: L1
--! mode: parse
-- 复杂条件：函数 + 运算 + NULL 比较 + 游标属性
begin
  if length(trim(name)) > 0 and c%found then null; end if;
  while c%rowcount < 10 and not c%notfound loop
    null;
  end loop;
end;
--! ---
--! id: GRM-052
--! layer: L1
--! mode: parse
-- 注释穿插（行注释 / 块注释 / 行尾注释）
declare
  -- 变量说明
  x int; /* 块注释 */
  y int; -- 行尾注释
begin
  insert into t values(:x, :y); -- 语句尾注释
  /* 中间的块注释
     跨多行 */
  insert into t values(1);
end;
--! ---
--! id: GRM-053
--! layer: L1
--! mode: parse
-- 引号标识符与 :name 混用
declare
  "Mixed Name" int;
begin
  insert into t values(:Mixed);
end;
--! ---
--! id: GRM-054
--! layer: L1
--! mode: parse
-- 无包装块的结束分号可省略（容错）
begin
  pass;
end
--! ---
--! id: GRM-055
--! layer: L1
--! mode: parse
-- SELECT 的 INTO 目标不是合法变量列表时**退化为普通 SQL**（交给数据库报错，而不是文法误判）
begin
  select a into from t;
end;
--! ---
--! id: GRM-056
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[if[b=2,stmts=2]],handlers=[])
-- 兼容写法：elseif（无 else）
begin
  if x then
    null;
  elseif y then
    null;
  endif;
end;
