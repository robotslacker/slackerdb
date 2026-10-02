--! id: GRM-F01
--! layer: L2
--! mode: parse
--! expect-error: extraneous input 'loop' expecting @2:12
begin
  for i in loop
    null;
  end loop;
end;
--! ---
--! id: GRM-F02
--! layer: L2
--! mode: parse
--! expect-error: extraneous input 'loop' expecting @2:9
begin
  while loop
    null;
  end loop;
end;
--! ---
--! id: GRM-F03
--! layer: L2
--! mode: parse
--! expect-error: missing 'LOOP' @4:6
begin
  loop
    null;
  end;
end;
--! ---
--! id: GRM-F04
--! layer: L2
--! mode: parse
--! expect-error: extraneous input 'end' expecting ';' @5:1
begin
  if x then
    null;
  end if
end;
--! ---
--! id: GRM-F05
--! layer: L2
--! mode: parse
--! expect-error: extraneous input 'end' expecting ';' @3:1
begin
  x := 1
end;
--! ---
--! id: GRM-F06
--! layer: L2
--! mode: parse
--! expect-error: mismatched input ';' expecting SQL_SEGMENT @2:14
declare
  cursor c is;
begin
  null;
end;
--! ---
--! id: GRM-F07
--! layer: L2
--! mode: parse
--! expect-error: mismatched input ';' expecting @2:12
declare
  x int := ;
begin
  null;
end;
--! ---
--! id: GRM-F08
--! layer: L2
--! mode: parse
--! expect-error: missing ')' @2:11
begin
  open c(1;
end;
--! ---
--! id: GRM-F09
--! layer: L2
--! mode: parse
--! expect-error: mismatched input ';' expecting 'INTO' @2:10
begin
  fetch c;
end;
--! ---
--! id: GRM-F10
--! layer: L2
--! mode: parse
--! expect-error: missing IDENTIFIER @2:8
begin
  close;
end;
--! ---
--! id: GRM-F11
--! layer: L2
--! mode: parse
--! expect-error: extraneous input '1' expecting @2:9
begin
  raise 1;
end;
--! ---
--! id: GRM-F12
--! layer: L2
--! mode: parse
--! expect-error: extraneous input '<EOF>' expecting @3:9
begin
  insert into t values(1)
end;
--! ---
--! id: GRM-F13
--! layer: L2
--! mode: parse
--! expect-error: mismatched input ';' expecting @2:16
begin
  continue when;
end;
--! ---
--! id: GRM-F14
--! layer: L2
--! mode: parse
--! expect-error: extraneous input 'else' expecting @3:3
begin
  null;
  else null;
end;
--! ---
--! id: GRM-F15
--! layer: L2
--! mode: parse
--! expect-error: mismatched input 'if' expecting @2:7
begin
  end if;
end;
--! ---
--! id: GRM-F16
--! layer: L2
--! mode: parse
--! expect-error: mismatched input 'loop' expecting 'IF' @4:7
begin
  if x then
    null;
  end loop;
end;
--! ---
--! id: SEM-F01
--! layer: L2
--! mode: compile
--! expect-error: declared twice @3:1
declare
  x int;
  x text;
begin
  null;
end;
--! ---
--! id: SEM-F02
--! layer: L2
--! mode: compile
--! expect-error: has not been declared @5:1
begin
  for i in 1..3 loop
    null;
  end loop;
  x := 1;
end;
--! ---
--! id: SEM-F03
--! layer: L2
--! mode: compile
--! expect-error: Cursor [c] has not been declared @2:1
begin
  open c;
end;
--! ---
--! id: SEM-F04
--! layer: L2
--! mode: compile
--! expect-error: Variable [nope] has not been declared @4:1
declare
  cursor c is select 1;
begin
  fetch c into nope;
end;
--! ---
--! id: SEM-F05
--! layer: L2
--! mode: compile
--! expect-error: has not been declared @4:1
declare
  x int;
begin
  select a into y from t;
end;
