![机器人小懒](robotslacker.jpg)

#    ！！！代码开发中，目前不可使用！！！

# SlackerDB (DUCKDB 封装器)
## 快速说明

这个程序是一个实现了POSTGRES的JDBC V3协议，模仿一个POSTGRES服务器。
后面的SQL引擎实现是DUCKDB.

这么做的目的是：
1. 解决DUCKDB无法在运行过程中查看数据的问题
2. 可以多个客户端（多个进程）并发访问同一个DUCKDB
3. DUCKDB不一定要在某一个本机访问，也可以通过远程来访问

## 协议文档
https://www.postgresql.org/docs/15/protocol-message-formats.html

## 已知问题
计划完全重构之前的开源代码（主要是看不懂其混乱的逻辑，及非常不友好的框架）  
部分Duckdb的数据类型PG中并不存在，无法支持  
计划在本项目完成后，重写一个定制的客户端代码，便于后续扩展

## 后续工作
1. 本机的虚拟机环境上安装Postgres15以及tshark
2. 通过tshark分析获得的协议内容，分析每个数据类型的序列化方式，完成variousType的测试
3. 添加对BindRequest的解析，目前不支持
4. 添加对addBatch,executeBatch的支持
5. 添加对BLOB操作的支持
6. 对多个Preparement并发操作的支持

## 时间表
1. 24-08-01 完成对常见各种数据类型的支持
2. 24-08-07 完成对addBatch,executeBatch的支持
3. 24-08-10 完成对多个preparement并发操作的支持
4. 24-08-15 重新整理代码结构，删除之前参考用的代码部分


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