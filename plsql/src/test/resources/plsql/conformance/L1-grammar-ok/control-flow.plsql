--! id: GRM-021
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[if[b=1,stmts=1]],handlers=[])
begin
  if x > 0 then
    insert into t values(1);
  end if;
end;
--! ---
--! id: GRM-022
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[if[b=2,else=1,stmts=2]],handlers=[])
begin
  if x > 0 then
    insert into t values(1);
  elsif x = 0 then
    insert into t values(2);
  else
    insert into t values(3);
  end if;
end;
--! ---
--! id: GRM-023
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[if[b=3,stmts=3]],handlers=[])
begin
  if a then
    null;
  elsif b then
    null;
  elsif c then
    null;
  end if;
end;
--! ---
--! id: GRM-024
--! layer: L1
--! mode: parse
-- 兼容写法：ELSEIF / ENDIF / == / && 
begin
  if x == 1 && y != 2 then
    null;
  elseif z then
    null;
  endif;
end;
--! ---
--! id: GRM-025
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[loop[sql,sql]],handlers=[])
begin
  loop
    insert into t values(1);
    insert into t values(2);
  end loop;
end;
--! ---
--! id: GRM-026
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[while[sql]],handlers=[])
begin
  while x < 3 loop
    insert into t values(1);
  end loop;
end;
--! ---
--! id: GRM-027
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[sql,for[sql],sql],handlers=[])
begin
  insert into t values(0);
  for i in 1..5 loop
    insert into t values(1);
  end loop;
  insert into t values(9);
end;
--! ---
--! id: GRM-028
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[forr[sql]],handlers=[])
begin
  for :i in reverse 5..1 loop
    insert into t values(1);
  end loop;
end;
--! ---
--! id: GRM-029
--! layer: L1
--! mode: parse
-- 兼容写法：TO、变量边界
begin
  for i in v_start TO v_end loop
    null;
  end loop;
end;
--! ---
--! id: GRM-030
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[loop[exit(when)]],handlers=[])
begin
  loop
    exit when c%notfound;
  end loop;
end;
--! ---
--! id: GRM-031
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[loop[exit(break),exit(label)]],handlers=[])
begin
  loop
    break;
    exit outer_loop;
  end loop;
end;
--! ---
--! id: GRM-032
--! layer: L1
--! mode: parse
begin
  loop
    continue;
    continue when x > 1;
    exit when x > 9;
  end loop;
end;
--! ---
--! id: GRM-033
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[open:c,open:c(args),fetch:c/2,close],handlers=[])
begin
  open c;
  open c(1, 'a');
  fetch c into x, y;
  close c;
end;
--! ---
--! id: GRM-034
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[var,var],stmts=[into:2],handlers=[])
declare
  x int;
  y text;
begin
  select a, b into x, y from t where a > 0;
end;
--! ---
--! id: GRM-035
--! layer: L1
--! mode: parse
-- INTO 目标用 :name 写法、多行 SQL
declare
  x int;
  y int;
begin
  select a, b
    into :x, :y
    from t;
end;
--! ---
--! id: GRM-036
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[raise,raise:no_data_found],handlers=[])
begin
  raise;
  raise no_data_found;
end;
--! ---
--! id: GRM-037
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[sql],handlers=[OTHERS[sql]])
begin
  insert into t values(1);
exception
  when others then
    insert into t values(2);
end;
--! ---
--! id: GRM-038
--! layer: L1
--! mode: parse
--! expect-ast: block(decls=[],stmts=[sql],handlers=[NO_DATA_FOUND[null],ZERO_DIVIDE|OTHERS[rollback]])
begin
  insert into t values(1);
exception
  when no_data_found then
    null;
  when zero_divide or others then
    rollback;
end;
--! ---
--! id: GRM-039
--! layer: L1
--! mode: parse
-- 兼容写法：EXCEPTION: 冒号
begin
  insert into t values(1);
exception:
  when others then
    null;
end;
--! ---
--! id: GRM-040
--! layer: L1
--! mode: parse
-- 嵌套块的独立异常处理
begin
  begin
    insert into t values(1);
  exception
    when others then
      insert into t values(2);
  end;
exception
  when others then
    null;
end;
--! ---
--! id: GRM-041
--! layer: L1
--! mode: parse
-- 循环体内嵌 IF/嵌套块（历史缺陷：循环体内 IF 曾被静默跳过）
begin
  loop
    if x > 0 then
      begin
        insert into t values(1);
      end;
    else
      exit when c%notfound;
    end if;
  end loop;
end;
--! ---
--! id: GRM-042
--! layer: L1
--! mode: parse
-- 空体与多级嵌套
begin
  loop
    loop
      while x < 1 loop
        null;
      end loop;
    end loop;
  end loop;
end;
