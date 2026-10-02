-- L0：词法错误与边界
--! id: DET-043
--! layer: L0
--! mode: detect
--! expect-error: unterminated string
select 'abc
--! ---
--! id: DET-044
--! layer: L0
--! mode: detect
--! expect-error: unterminated block comment
select 1 /* 未闭合
--! ---
--! id: DET-045
--! layer: L0
--! mode: detect
--! expect-error: unterminated dollar-quoted string
DO $$
begin
  pass;
end;
--! ---
--! id: DET-046
--! layer: L0
--! mode: detect
--! expect-error: unterminated dollar-quoted string
select 1;
$$
begin
  pass;
end;
--! ---
--! id: DET-047
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
-- $$ 体内不能出现 $$（PG 语义：遇到相同 tag 即结束），因此这里必须用 $b$ 包装
DO $b$
begin
  insert into t values('$$');
  insert into t values('$a$');
end;
$b$
--! ---
--! id: DET-048
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
-- 包装块之后还能继续写语句（简单查询协议下按顺序执行）——旧正则会把包装块之外的内容丢掉
DO $$
begin
  pass;
end;
$$;
select 1
--! ---
--! id: DET-049
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
--! expect-statements: 1
/* 前缀注释 */ DO $x$ begin pass; end; $x$
--! ---
--! id: DET-050
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
begin
  if 1 > 0 then
    begin
      insert into t values(1);
    end;
  end if;
end;
--! ---
--! id: DET-051
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
declare
  x int;
begin
  loop
    insert into t values(1);
    exit when x%notfound;
  end loop;
end;
--! ---
--! id: DET-052
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
-- 块内 SQL 含 CASE...END 与分号字符串，均不得影响分类
declare
  x int;
begin
  insert into t select case when 1=1 then 'a;b' else 'c' end;
end;
--! ---
--! id: DET-053
--! layer: L0
--! mode: detect
--! expect-kind: SQL
--! expect-statements: 1
-- 只有一行超长 SQL，不应出现性能问题
select 1111111111111111111111111111111111111111
--! ---
--! id: DET-054
--! layer: L0
--! mode: detect
--! expect-kind: SQL
select 1
--! ---
--! id: DET-055
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 3
select 1;
select 2;;
select 3
--! ---
--! id: DET-056
--! layer: L0
--! mode: detect
--! expect-kind: SCRIPT
--! expect-statements: 2
COPY t FROM STDIN;
select 1
--! ---
--! id: DET-057
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
do $body$
declare
  x int;
begin
  insert into t values('$tag$');
end;
$body$
--! ---
--! id: DET-058
--! layer: L0
--! mode: detect
--! expect-kind: BLOCK
BEGIN
  insert into t values(1);
END;
--! ---
--! id: DET-059
--! layer: L0
--! mode: detect
--! expect-kind: SQL
begin work;
--! ---
--! id: DET-060
--! layer: L0
--! mode: detect
--! expect-kind: EMPTY
--! ---
--! id: DET-061
--! layer: L0
--! mode: detect
--! expect-kind: SQL
-- 关键字大小写混写
SeLeCt 1
