![机器人小懒](robotslacker.jpg)

# SlackerDB (DUCKDB 封装器)
## 快速说明

这个程序是一个实现了PG的JDBC V3协议，一个PG数据库的高仿产品。
后面的SQL引擎、存储引擎都是是OLAP新宠DUCKDB.

这么做的目的是：
1. 解决DUCKDB无法在运行过程中从外部查看数据的问题
2. 解决DUCKDB不支持可以多个客户端（多个进程）并发访问的问题
3. 解决DUCKDB不支持网络访问的问题

## 已知问题
#### TimeStamp的时区问题
PG的客户端会恒定把系统当前时区作为参数出现在setTimeStamp中，duckdb的TimeStamp并无时区概念。
这会导致使用setTimeStamp插入的数据在查询后相差一定数值。
解决办法：
    客户端恒定使用UTC时区

## 使用方法
```
    git clone ...
    mvn clean compile package
    java -jar slackerdb-0.0.1.jar -p 3175 start
    
    随后就可以用DBEAVER之类的工具连接到数据库上，进行你需要的访问
```