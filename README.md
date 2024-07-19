![机器人小懒](robotslacker.jpg)

# SlackerDB (DUCKDB 封装器)
## 快速说明

这个程序是一个实现了POSTGRES的JDBC V3协议，模仿一个POSTGRES服务器。
后面的SQL引擎实现是DUCKDB.

这么做的目的是：
1. 解决DUCKDB无法在运行过程中查看数据的问题
2. 可以多个客户端（多个进程）并发访问同一个DUCKDB
3. DUCKDB不一定要在某一个本机访问，也可以通过远程来访问


## 已知问题
1. getBlob函数会出现错误
2. 长时间运行后，Executor的线程服务不能及时回收
3. 短时间内高并发访问，会出现事务没有有效active的信息（DuckDB的Bug？）

## 后续计划
重构业务逻辑层
去掉JAVA11的NIO
支持Extensions的自动添加
V$SESSION表的建立


## 使用方法
```
    git clone ...
    mvn clean compile package
    java -jar slackerdb-0.0.1.jar -p 3175 start
    
    随后就可以用DBEAVER之类的工具连接到数据库上，进行你需要的访问
```