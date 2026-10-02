--! id: GRM-101
--! layer: L1
--! mode: parse
-- 运算符优先级与结合性（文法层面：全部能吃下且结构正确）
begin
  let x = 1 + 2 * 3 - 4 / 2 % 3;
  let y = 2 ** 3 ** 2;
  let z = -(-1);
  let m = not (1 > 2 and 3 < 4 or 5 = 5);
end;
--! ---
--! id: GRM-102
--! layer: L1
--! mode: parse
begin
  let a = 'x' || 'y' || 'z';
  let b = 1 + 2 || 's';
  let c = (1 + 2) * (3 - 4);
  let d = ((1));
end;
--! ---
--! id: GRM-103
--! layer: L1
--! mode: parse
begin
  if x = 1 then null; end if;
  if x == 1 then null; end if;
  if x <> 1 then null; end if;
  if x != 1 then null; end if;
  if x < 1 then null; end if;
  if x <= 1 then null; end if;
  if x > 1 then null; end if;
  if x >= 1 then null; end if;
end;
--! ---
--! id: GRM-104
--! layer: L1
--! mode: parse
begin
  if x is null then null; end if;
  if x is not null then null; end if;
  if x like 'a%b_c' then null; end if;
  if x not like 'a' then null; end if;
  if x in (1) then null; end if;
  if x in (1, 2, 3, 4) then null; end if;
  if x not in ('a', 'b') then null; end if;
  if x between 1 and 2 then null; end if;
  if x not between 1 and 2 then null; end if;
end;
--! ---
--! id: GRM-105
--! layer: L1
--! mode: parse
begin
  if a and b and c then null; end if;
  if a or b or c then null; end if;
  if a and b or c and d then null; end if;
  if not a and not b then null; end if;
  if a and (b or c) then null; end if;
  if a && b || !c then null; end if;
end;
--! ---
--! id: GRM-106
--! layer: L1
--! mode: parse
begin
  let v = case when a then 1 end;
  let v = case when a then 1 when b then 2 when c then 3 end;
  let v = case when a then 'x' else 'y' end;
  let v = case when a then case when b then 1 else 2 end else 3 end;
end;
--! ---
--! id: GRM-107
--! layer: L1
--! mode: parse
begin
  let a = f();
  let b = f(1);
  let c = f(1, 2);
  let d = f(g(1), h('x', 2), 3);
  let e = ns.f(1);
end;
--! ---
--! id: GRM-108
--! layer: L1
--! mode: parse
begin
  if c%found then null; end if;
  if c%notfound then null; end if;
  if c%rowcount > 0 then null; end if;
  if c%isopen then null; end if;
  let n = c%rowcount + 1;
end;
--! ---
--! id: GRM-109
--! layer: L1
--! mode: parse
declare
  x int := 1;
  y int default 2;
  z text := 'a' || 'b';
  w boolean := true;
  n int not null := 0;
  d double precision;
  ts timestamp with time zone;
  tm timestamp without time zone;
  iv interval;
  big bigint;
  small smallint;
  dec decimal(18, 4);
  num numeric(10, 2);
  vc varchar(255);
  ch char(1);
begin
end;
--! ---
--! id: GRM-110
--! layer: L1
--! mode: parse
declare
  cursor c1 is select 1;
  cursor c2 is select a, b from t where a > 0 order by a;
  cursor c3(p1 int, p2 text) is select p1, p2;
begin
  open c1;
  open c3(1, 'x');
  close c1;
  close c3;
end;
--! ---
--! id: GRM-111
--! layer: L1
--! mode: parse
begin
  <<outer_loop>>
  loop
    <<inner_loop>>
    loop
      exit inner_loop;
    end loop;
    exit outer_loop when x > 0;
  end loop outer_loop;
end;
--! ---
--! id: GRM-112
--! layer: L1
--! mode: parse
begin
  for i in 1..3 loop null; end loop;
  for i in reverse 3..1 loop null; end loop;
  for i in 1 TO 3 loop null; end loop;
  for i in [1, 2, 3] loop null; end loop;
  for i in [] loop null; end loop;
end;
--! ---
--! id: GRM-113
--! layer: L1
--! mode: parse
begin
  begin
    declare
      x int;
      cursor c is select 1;
    begin
      open c;
      fetch c into x;
      close c;
    exception
      when others then
        null;
    end;
  exception
    when no_data_found or too_many_rows then
      null;
  end;
end;
--! ---
--! id: GRM-114
--! layer: L1
--! mode: parse
begin
  select 1 into x;
  select a, b into x, y from t;
  select a, b, c into :x, :y, :z from t where a > 0;
  select count(*) into n from t;
  select a into x from t group by a having count(*) > 0 order by a limit 1;
end;
--! ---
--! id: GRM-115
--! layer: L1
--! mode: parse
begin
  merge into t using s on t.id = s.id when matched then update set a = 1;
  copy t from 'file.csv';
  explain select 1;
  pragma something;
  grant select on t to u;
end;
--! ---
--! id: GRM-116
--! layer: L1
--! mode: parse
begin
  let s = '中文与 emoji 😀';
  let e = '';
  let q = 'it''s';
  let dq = "quoted""ident";
end;
--! ---
--! id: GRM-117
--! layer: L1
--! mode: parse
begin
  if true then null; end if;
  if false then null; end if;
  if null then null; end if;
  let b = true and false or null;
end;
--! ---
--! id: GRM-118
--! layer: L1
--! mode: parse
begin
  while c%rowcount < 10 loop
    fetch c into x;
    exit when c%notfound;
    continue when x is null;
  end loop;
end;
--! ---
--! id: GRM-119
--! layer: L1
--! mode: parse
-- 注释与空白的各种穿插
declare -- 声明
  x int; -- 变量
  /* 块注释
     多行 */
  y int;
begin
  -- 只注释
  null;
  /* 语句前的块注释 */ insert into t values(1); -- 尾注释
end;
--! ---
--! id: GRM-120
--! layer: L1
--! mode: parse
-- 嵌套深度：块/IF/循环各 5 层
begin
  begin begin begin begin begin
    if a then if b then if c then if d then if e then
      loop loop loop loop loop
        null;
      end loop; end loop; end loop; end loop; end loop;
    end if; end if; end if; end if; end if;
  end; end; end; end; end;
end;
