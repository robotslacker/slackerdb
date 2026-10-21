_connect 127.0.0.1:43175/db1
create table aaa (id int);
insert into aaa values(10);
insert into aaa values(6);
-- [Hint] Order
select * from aaa;
drop table xxx;
delete from aaa where id =6;

select 1 from dual;

-- 正确的Assert语句
_Assert {% 3==3 %};
-- 错误的Assert语句
_Assert {% 1==2 %};

_exit 3
