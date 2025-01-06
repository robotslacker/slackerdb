![机器人小懒](robotslacker.jpg)

# SlackerDB (DuckDB Postgres proxy)

## Quick Note
This program implements the JDBC V3 protocol of PG.  
The SQL engine and storage engine behind it are both DuckDB.

Slackerdb is currently has three parts:  
* A proxy program, used to forward PG protocol to remote.  
1.  PG protocol proxy, so that you can connect to multi db services distributed on same or different machines through a unified service port.
2.  If necessary, you can also use this proxy to forward ordinary PG requests, which is no different.
3.  Remote user/password/serviceName can different with proxy if you think this is what your want.
* A plsql parser(experimental version), you can write plsql code.
1.  Support Begin .... Exception ... End;
2.  Support Loop, For;
3.  Support Declare cursor, fetch;
4.  Support variable. let or select ... into ...;
5.  If necessary, you can also use this parser to execute plsql against other database.
* A service program, used to implement PG communication protocol based on DuckDB.  
1.  make we can view and update duckdb data from the process outside.
2.  make we can view and update duckdb data from the network.

## Usage
### Build from source:
```
    # Download JDK17 and maven 3.6+, and install them.
    # Download source code
    git clone ...
    # compile it
    cd slackerdb
    mvn clean compile package
```
### Start db server
``` 
    java -jar dbserver/target/slackerdb-dbserver-0.1.1-standalone.jar start
```
### Start db proxy
``` 
    java -jar dbproxy/target/slackerdb-dbproxy-0.1.1-standalone.jar start
```
### Stop db server
``` 
    java -jar dbserver/target/slackerdb-dbserver-0.1.1-standalone.jar stop
```
### Stop db proxy
``` 
    java -jar dbproxy/target/slackerdb-dbproxy-0.1.1-standalone.jar stop
```

### Server configuration file template
``` 
# Database name
data=slackerdb

# Path where data files are saved
# Empty means it is placed in memory (i.e.: memory:) , after db restart, all data will lost.
data_dir=:memory:

# The location of temporary files during database operation.
# Disk mode:   If not set, it defaults to the same as data_dir.
# Memory mode: If not set, it defaults to system temporary setting.
# It is strongly recommended to use a high-performance disk as a temporary directory.
# If memory resources are sufficient, you can also configure memory_limit to -1.
# -1 means all temporary files will exist only in the memory (only valid for memory mode)
temp_dir=

# Location of system extension plugin files.
# If not set, it defaults to $HOME/.duckdb/extensions
extension_dir=

# PID file. Used to describe the process id of running server process.
# When server is running, the file will be exclusively locked.
# If this file is locked by other process, server will abort and not continue start.
# If not configured, this file will not be generated and the lock will not be checked.
pid=

# The location where the log file is saved
# CONSOLE means output to the console, and others mean output to a file
# Multiple logs can be output at the same time, separated by commas, for example, console, logs/xx.log
log=CONSOLE,logs/slackerdb.log

# Log level
log_level=INFO

# Service port
# 0 means the system will randomly assign a port
# -1 means no port will be opened
# default is 0, means random port.
port=0

# Service binding host
bind=0.0.0.0

# Client idle timeout (in seconds)
client_timeout=600

# Database opening mode
access_mode=READ_WRITE

# The maximum number of client that can connect to server at the same time
# The default is 256
max_connections=

# The maximum number of threads that the service layer can process at the same time
# The default is the number of CPU cores
max_workers=

# Maximum number of threads used on the DB side
# Default is 50% of the number of cores
# Best Practice: Aim for 5-10 GB memory per thread
threads=

# Maximum memory size used
# Default is 60% of available memory
memory_limit=

# Initialization script. Maybe file or directory.
# If it is a file, the file will be used.
# If it is a directory, all files with the .sql suffix in the directory will be used.
# The script(s in the directory) will be executed only when the in-memory database or file database is opened for the first time.
init_schema=

# locale. The default language set of the program. If not set, it will be marked as the system default
locale=

# sql_history.  used to enable or disable the SQL history feature.
# Possible values:
# - ON: Enables SQL history tracking.
# - OFF: Disables SQL history tracking (default).
# When set to OFF (default), SQL execution history will not be recorded.
sql_history=OFF

# Specifies the minimum number of idle database connections that the connection pool maintains at any time.
# These connections are pre-established and kept available to handle incoming requests, helping to reduce latency during operations.
# Default Value: 3
connection_pool_minimum_idle=3

# Defines the maximum number of idle database connections that the connection pool can retain.
# Connections exceeding this limit will be closed to conserve system resources.
# Default Value: 10
connection_pool_maximum_idle=10

# Determines the maximum lifecycle time (in milliseconds) for a connection in the pool, regardless of whether it is active or idle.
# Once the lifecycle time is exceeded, the connection is closed and removed from the pool.
# Default Value: 900000 (15 minutes)
connection_pool_maximum_lifecycle_time=900000

# SQL statement used to validate the health of a connection before it is handed over to the application.
# If left empty, the validation process will not start.
# Default Value: Empty ('')
connection_pool_validation_sql=

```
Note: All parameters are optional.   
You can keep only the parameters you need to modify.   
For parameters that are not configured, means default values  will be used.

### Proxy configuration file template
``` 
# The location where the log file is saved
# CONSOLE means output to the console, and others mean output to a file
# Multiple logs can be output at the same time, separated by commas, for example, console, logs/xx.log
log=CONSOLE,logs/slackerdb.log

# Log level
log_level=INFO

# Service port
# 0 means the system will randomly assign a port
# default is 0
port=0

# Service binding host
bind=0.0.0.0

# Client idle timeout (in seconds)
client_timeout=600

# The maximum number of threads that the service layer can process at the same time
# The default is the number of CPU cores
max_workers=

# locale. The default language set of the program. If not set, it will be marked as the system default
locale=

# PID file. Used to describe the process id of running server process.
# When server is running, the file will be exclusively locked.
# If this file is locked by other process, server will abort and not continue start.
# If not configured, this file will not be generated and the lock will not be checked.
pid=

```
Note: All parameters are optional.   
You can keep only the parameters you need to modify.   
For parameters that are not configured, means default values  will be used.

### Embed the db server in your code
``` 
  // create configuration,  and update as your need
  ServerConfiguration serverConfiguration = new ServerConfiguration();
  serverConfiguration1.setPort(4309);
  serverConfiguration1.setData("data1");
  
  // init database
  DBInstance dbInstance= new DBInstance(serverConfiguration1);
  
  // startup database
  dbInstance1.start();
  
  // shutdown database
  dbInstance.stop();
  
  // We currently supports starting multiple instances running at the same time.
  // But each instance must has his own port and instance name.

```

### Embed the db proxy in your code
``` 
    ServerConfiguration proxyConfiguration = new ServerConfiguration();
    proxyConfiguration.setPort(dbPort);
    proxyInstance = new ProxyInstance(proxyConfiguration);
    proxyInstance.start();

    // Waiting for server ready
    while (!proxyInstance.instanceState.equalsIgnoreCase("RUNNING")) {
        Sleeper.sleep(1000);
    }

    // add proxy rule
    proxyInstance.createAlias("mem1", false);
    proxyInstance.addAliasTarget("mem1",
            "127.0.0.1:" +
            dbInstance.serverConfiguration.getPort() + "/" +
            dbInstance.serverConfiguration.getData(), 200);


```

### Jdbc program example 
``` 
    // "db1" is your database name in your configuration file.
    // 3175  is your database port in your configuration file.
    // If you are connecting for the first time, there will be no other users except the default public
    String  connectURL = "jdbc:postgresql://127.0.0.1:3175/db1";
    Connection pgConn = DriverManager.getConnection(connectURL, "public", "");
    pgConn.setAutoCommit(false);
   
    // .... Now you can execute your business logic.
```

### Odbc and python program
``` 
    It also support ODBC and Python connection. 
```

## Use IDE tools to connect to the database
Currently, DBeaver is only supported to connect to the database by configuring the JDBC driver.  
When configuring the driver, select the Postgres driver.  
We can confirm : Navicat, pgAdmin are not supported yet.


## Known Issues
### 1. Time Zone Issues with TimeStamp
PG clients will always use the current system time zone as a parameter in setTimeStamp, and duckdb TimeStamp has no concept of time zone.  
This will cause the data inserted using api setTimeStamp() is different with your inserted.  
Workaround:   
The client always uses the UTC time zone
### 2. User and password authorization
We do not support user password authentication, just for compatibility, keep these two options.  
you can fill in the password part as you like, it doesn't make sense.  
The user part will be used by the default schema of the user connection.
### 3. Limited support for duckdb datatype
Only some duckdb data types are supported, mainly simple types, such as int, number, double, varchar, ...
For complex types, some are still under development, and some are not supported by the PG protocol, such as blob, list, map...
You can refer to sanity01.java to see what we currently support.
### 4. DBeaver issue
In some DBeaver versions, When running queries, the Discover Owner Entity task takes 30+ seconds to complete.  
As a workaround you can disable Read table metadata and Read Table references options in Preferences:  
Preferences -> Editors -> Data Editor -> Disable "Read table metadata" and "Read table references".
### 5. postgresql-fdw
fdw will use "Declare CURSOR" to fetch remote data, while duck doesn't support this.

## Next plan
...
