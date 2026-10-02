--! id: GRM-E01
--! layer: L2
--! mode: parse
--! expect-error: missing 'THEN' @3:5
begin
  if x > 0
    null;
  end if;
end;
--! ---
--! id: GRM-E02
--! layer: L2
--! mode: parse
--! expect-error: missing 'IF' @4:4
begin
  if x > 0 then
    null;
end;
--! ---
--! id: GRM-E03
--! layer: L2
--! mode: parse
--! expect-error: extraneous input 'end' expecting ';' @3:1
begin
  null
end;
--! ---
--! id: GRM-E04
--! layer: L2
--! mode: parse
--! expect-error: missing 'LOOP' @3:5
begin
  while x < 3
    null;
  end loop;
end;
--! ---
--! id: GRM-E05
--! layer: L2
--! mode: parse
--! expect-error: mismatched input 'while' expecting 'LOOP' @4:7
begin
  for i in 1 TO 5 loop
    null;
  end while;
end;
--! ---
--! id: GRM-E06
--! layer: L2
--! mode: parse
--! expect-error: missing 'LOOP' @4:6
begin
  loop
    null;
  end;
end;
--! ---
--! id: GRM-E07
--! layer: L2
--! mode: parse
--! expect-error: mismatched input 'elsif' @6:3
begin
  if x > 0 then
    null;
  else
    null;
  elsif y then
    null;
  end if;
end;
--! ---
--! id: GRM-E08
--! layer: L2
--! mode: parse
--! expect-error: missing 'IN' @2:9
begin
  for i 1..5 loop
    null;
  end loop;
end;
--! ---
--! id: GRM-E09
--! layer: L2
--! mode: parse
--! expect-error: mismatched input 'loop' @2:14
begin
  for i in 1 loop
    null;
  end loop;
end;
--! ---
--! id: GRM-E10
--! layer: L2
--! mode: parse
--! expect-error: mismatched input ';' expecting IDENTIFIER @2:4
declare
  x;
begin
  null;
end;
--! ---
--! id: GRM-E11
--! layer: L2
--! mode: parse
--! expect-error: mismatched input 'select' expecting {'IS', '('} @2:12
declare
  cursor c select a from t;
begin
  null;
end;
--! ---
--! id: GRM-E12
--! layer: L2
--! mode: parse
--! expect-error: missing {QUOTED_IDENTIFIER, COLON_IDENTIFIER, IDENTIFIER} at ';' @2:15
begin
  fetch c into;
end;
--! ---
--! id: GRM-E13
--! layer: L2
--! mode: parse
--! expect-error: mismatched input ';' expecting {'NULL' @2:12
begin
  exit when;
end;
--! ---
--! id: GRM-E14
--! layer: L2
--! mode: parse
--! expect-error: mismatched input ';' expecting {'NULL' @2:8
begin
  x := ;
end;
--! ---
--! id: GRM-E15
--! layer: L2
--! mode: parse
--! expect-error: extraneous input 'then' @2:6
begin
  if then
    null;
  end if;
end;
--! ---
--! id: GRM-E16
--! layer: L2
--! mode: parse
--! expect-error: unterminated string at line 2 @2:1
begin
  insert into t values('abc);
end;
--! ---
--! id: GRM-E17
--! layer: L2
--! mode: parse
--! expect-error: unterminated block comment at line 2 @2:1
begin
  /* 未闭合
  null;
end;
--! ---
--! id: GRM-E18
--! layer: L2
--! mode: parse
--! expect-error: mismatched input ';' expecting {'THEN', 'OR'} @2:24
begin
  exception when others;
  null;
end;
--! ---
--! id: GRM-E19
--! layer: L2
--! mode: parse
--! expect-error: no viable alternative at input 'others' @2:13
begin
  exception others then
    null;
end;
--! ---
--! id: GRM-E20
--! layer: L2
--! mode: parse
--! expect-error: missing IDENTIFIER at ';' @2:7
begin
  open;
end;
--! ---
--! id: GRM-E21
--! layer: L2
--! mode: parse
--! expect-error: missing IDENTIFIER at ';' @2:8
begin
  close;
end;
--! ---
--! id: GRM-E22
--! layer: L2
--! mode: parse
--! expect-error: missing '=' @2:9
begin
  let x 1;
end;
--! ---
--! id: GRM-E23
--! layer: L2
--! mode: parse
--! expect-error: missing ';' @5:3
begin
  if x > 0 then
    null;
  end if
  end loop;
end;
--! ---
--! id: SEM-E01
--! layer: L2
--! mode: compile
-- 编译期语义检查：未声明变量，带精确位置，且不可被 EXCEPTION 捕获
--! expect-error: Variable [x] has not been declared @3:6
begin
  if x > 0 then
    null;
  end if;
end;
--! ---
--! id: SEM-E02
--! layer: L2
--! mode: compile
--! expect-error: Cursor [c] has not been declared @2:1
begin
  fetch c into x;
end;
--! ---
--! id: SEM-E03
--! layer: L2
--! mode: compile
--! expect-error: declared twice @3:1
declare
  x int;
  x int;
begin
  null;
end;
--! ---
--! id: SEM-E04
--! layer: L2
--! mode: compile
--! expect-error: Variable [y] has not been declared @4:11
declare
  x int;
begin
  let x = :y + 1;
end;
