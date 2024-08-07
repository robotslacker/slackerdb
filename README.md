![机器人小懒](robotslacker.jpg)

# SlackerDB (DUCKDB 封装器)
## 快速说明

这个程序是一个实现了POSTGRES的JDBC V3协议，模仿一个POSTGRES服务器。
后面的SQL引擎实现是DUCKDB.

这么做的目的是：
1. 解决DUCKDB无法在运行过程中查看数据的问题
2. 可以多个客户端（多个进程）并发访问同一个DUCKDB
3. DUCKDB不一定要在某一个本机访问，也可以通过远程来访问

## 文档
PG协议文档参考：  
https://www.postgresql.org/docs/15/protocol-message-formats.html
TCPDUMP工具:  
tcpdump -v -i any \(src X.X.X.X and dst port 5432 \) or \( dst X.X.X.X and src port 5432 \) -X

## 已知问题
#### TimeStamp的时区问题
PG的客户端会恒定把系统当前时区作为参数出现在setTimeStamp中，duckdb的TimeStamp并无时区概念。
这会导致使用setTimeStamp插入的数据在查询后相差一定数值。
解决办法：
    客户端恒定使用UTC时区


## 时间表
1. 缺少pg_database表（缺少dataloowconn, datistemplate)，导致dbeaver无法连接
2. not implemented column type VARCHAR[]
3. Extensions的支持
4. DBEaver中看不到表名和字段信息
5. 所有不支持的操作均要明确错误信息
6. 文档处理


## Postgres通讯步序图
``` 
客户端                             服务器
  |                                  |
  |------------StartupMessage------->|
  |                                  |
  |<--------AuthenticationRequest----|
  |                                  |
  |-----------PasswordMessage------->|
  |                                  |
  |---------AuthenticationOk---------|
  |                                  |
  |---------ParameterStatus----------|
  |                                  |
  |----------BackendKeyData----------|
  |                                  |
  |---------ReadyForQuery------------|
  |                                  |
  |--------------Parse-------------->|
  |                                  |
  |<----------ParseComplete----------|
  |                                  |
  |--------------Bind--------------->|
  |                                  |
  |<-----------BindComplete----------|
  |                                  |
  |------------Describe------------->|
  |                                  |
  |<---------RowDescription----------|
  |                                  |
  |------------Execute-------------->|
  |                                  |
  |<-----------DataRow---------------|
  |                                  |
  |<---------CommandComplete---------|
  |                                  |
  |--------------Sync--------------->|
  |                                  |
  |---------ReadyForQuery------------|
  |                                  |

```

## 使用方法
```
    git clone ...
    mvn clean compile package
    java -jar slackerdb-0.0.1.jar -p 3175 start
    
    随后就可以用DBEAVER之类的工具连接到数据库上，进行你需要的访问
```