--! id: IF-001
--! layer: L5
--! req: IF
--! tags: smoke
--! setup: create table t(v int)
begin
  if 5 > 3 then
    insert into t values(1);
  else
    insert into t values(2);
  end if;
end;
--! expect-query: select v from t | 1
--! ---
--! id: IF-002
--! layer: L5
--! req: IF
--! setup: create table t(v int)
begin
  if 3 > 5 then
    insert into t values(1);
  else
    insert into t values(2);
  end if;
end;
--! expect-query: select v from t | 2
--! ---
--! id: IF-003
--! layer: L5
--! req: IF
--! tags: nested
--! setup: create table t(v int)
begin
  if 5 > 3 then
    if 1 > 2 then
      insert into t values(1);
    else
      insert into t values(2);
    end if;
  end if;
end;
--! expect-query: select v from t | 2
--! ---
--! id: IF-004
--! layer: L5
--! req: IF
--! tags: if-in-loop, bugfix
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in [1,2,3] loop
    if 1 > 0 then
      insert into t values(8);
    end if;
  end loop;
end;
--! expect-query: select count(*) from t | 3
--! ---
--! id: IF-005
--! layer: L5
--! req: IF
--! tags: if-in-loop, bugfix
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in [1,2,3] loop
    if 1 > 2 then
      insert into t values(8);
    else
      insert into t values(6);
    end if;
  end loop;
end;
--! expect-query: select v from t order by v | 6 ; 6 ; 6
--! ---
--! id: IF-006
--! layer: L5
--! req: IF
--! tags: if-in-loop, cursor, bugfix
--! setup: create table src(a int)
--! setup: insert into src values (1),(2)
--! setup: create table t(v int)
declare
  cursor c is select a from src order by a;
  x int;
begin
  open c;
  loop
    fetch c into :x;
    exit when c%notfound;
    if :x > 1 then
      insert into t values(1);
    else
      insert into t values(0);
    end if;
  end loop;
  close c;
end;
--! expect-query: select v from t order by v | 0 ; 1
--! ---
--! id: IF-007
--! layer: L5
--! req: IF, EXCEPTION
--! tags: if-in-exception, bugfix
-- 处理块的第一条语句必须是 ROLLBACK：DuckDB 在语句失败后 abort 事务，
-- 而 IF 条件求值会新建 Statement，在 abort 状态下会直接报错。
--! setup: create table t(i int)
--! setup: insert into t values(1)
begin
  insert into t values(9);
  update t set i = 'abc';
exception:
  rollback;
  if 1 > 0 then
    insert into t values(5);
  end if;
end;
--! expect-query: select i from t order by i | 1 ; 5
--! ---
--! id: LOP-001
--! layer: L5
--! req: LOOP
--! tags: smoke, cursor
--! setup: create table src(a int)
--! setup: insert into src values (1),(2),(3)
--! setup: create table t(v int)
declare
  cursor c is select a from src order by a;
  x int;
begin
  open c;
  loop
    fetch c into :x;
    exit when c%notfound;
    insert into t values(:x);
  end loop;
  close c;
end;
--! expect-query: select count(*) from t | 3
--! expect-query: select v from t order by v | 1 ; 2 ; 3
--! ---
--! id: LOP-002
--! layer: L5
--! req: LOOP
--! tags: for-list
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in ['3','4','5'] loop
    insert into t values(9);
  end loop;
end;
--! expect-query: select count(*) from t | 3
--! ---
--! id: LOP-003
--! layer: L5
--! req: LOOP
--! tags: break, if-in-loop, bugfix
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in [1,2,3,4,5] loop
    if 1 > 0 then
      break;
    end if;
    insert into t values(9);
  end loop;
  insert into t values(7);
end;
--! expect-query: select v from t | 7
--! ---
--! id: LOP-004
--! layer: L5
--! req: LOOP
--! tags: nested, cursor
-- 嵌套循环必须写在内部 BEGIN...END 块里：legacy 文法不支持 loop 直接套 loop（P4 文法修复）。
--! setup: create table src(a int)
--! setup: insert into src values (1),(2)
--! setup: create table t(v int)
declare
  cursor c1 is select a from src order by a;
  cursor c2 is select a from src order by a;
  x int;
begin
  open c1;
  loop
    fetch c1 into :x;
    exit when c1%notfound;
    begin
      open c2;
      loop
        fetch c2 into :x;
        exit when c2%notfound;
        insert into t values(:x);
      end loop;
      close c2;
    end;
  end loop;
  close c1;
end;
--! expect-query: select count(*) from t | 4
--! ---
--! id: LOP-005
--! layer: L5
--! req: WHILE
--! tags: flipped-p6
-- 需求要求 WHILE；legacy 完全不支持（while 被当作 SQL 标识符），P4/P6 起支持。
--! setup: create table t(v int)
declare
  i int;
begin
  let i = 0;
  while :i < 3 loop
    insert into t values(:i);
    let i = :i + 1;
  end loop;
end;
--! expect-query: select v from t order by v | 0 ; 1 ; 2
--! ---
--! id: LOP-006
--! layer: L5
--! req: WHILE
--! tags: while-false
-- WHILE 条件恒假：0 次迭代
--! setup: create table t(v int)
declare
  i int;
begin
  while :i > 10 loop
    insert into t values(1);
  end loop;
  insert into t values(9);
end;
--! expect-query: select v from t | 9
--! ---
--! id: LOP-007
--! layer: L5
--! req: WHILE
--! tags: while-null
-- WHILE 条件为 NULL 按 false → 0 次迭代
--! setup: create table t(v int)
declare
  x int;
begin
  while :x = 1 loop
    insert into t values(1);
  end loop;
  insert into t values(9);
end;
--! expect-query: select v from t | 9
--! ---
--! id: LOP-008
--! layer: L5
--! req: FOR
--! tags: for-closed-interval
-- FOR 上界是闭区间
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 1..3 loop
    insert into t values(:i);
  end loop;
  for :j in reverse 5..4 loop
    insert into t values(:j);
  end loop;
end;
--! expect-query: select v from t order by v | 1 ; 2 ; 3 ; 4 ; 5
--! ---
--! id: LOP-009
--! layer: L5
--! req: LOOP
--! tags: exit-inner-only
-- EXIT 只退出最内层循环
--! setup: create table t(v int)
declare
  i int;
begin
  for :i in 1..2 loop
    loop
      insert into t values(:i);
      exit;
    end loop;
    insert into t values(9);
  end loop;
end;
--! expect-query: select v from t order by v | 1 ; 2 ; 9 ; 9
--! ---
--! id: LOP-010
--! layer: L5
--! req: LOOP
--! tags: infinite-loop-budget
-- 死循环由语句预算拦下（54000）。循环体用纯赋值，避免每轮一次数据库往返。
--! setup: create table t(v int)
declare
  x int;
begin
  loop
    let x = 1;
  end loop;
end;
--! expect-error: budget exceeded
