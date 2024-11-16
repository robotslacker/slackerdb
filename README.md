![机器人小懒](robotslacker.jpg)

# SlackerDB (DUCKDB Postgres proxy)

## Quick Note
This program implements the JDBC V3 protocol of PG.  
The SQL engine and storage engine behind it are both DUCKDB.

Slackerdb is currently has two parts:  
* A proxy program, used to forward PG protocol to remote.  
1.  PG protocol proxy, so that you can connect to multi db services distributed on same or different machines through a unified service port.
* A service program, used to implement PG communication protocol based on DuckDB.  
1.  make we can view and update duckdb data from the process outside.
2.  make we can view and update duckdb data from the network.

## Usage
### Build from source:
```
    # Download JDK11 and maven 3.6+, and install them.
    # Download source code
    git clone ...
    # compile it
    cd slackerdb
    mvn clean compile package
```
### Start db server
``` 
    java -jar slackerdb-dbserver-0.0.9-standalone.jar start
```
### Start db proxy
``` 
    java -jar slackerdb-dbproxy-0.0.9-standalone.jar start
```
### Stop db server
``` 
    java -jar slackerdb-dbserver-0.0.9-standalone.jar stop
```
### Stop db proxy
``` 
    java -jar slackerdb-dbproxy-0.0.9-standalone.jar stop
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

# Location of the external function definition, used to execute in the plsql script.
# If not set, it defaults to the same as data_dir
# Disk mode:   If not set, it defaults to the same as data_dir.
# Memory mode: If not set, it defaults to "data".
plsql_func_dir=

# The location where the log file is saved
# CONSOLE means output to the console, and others mean output to a file
# Multiple logs can be output at the same time, separated by commas, for example, console, logs/xx.log
log=CONSOLE,logs/slackerdb.log

# PID file. Used to describe the process id of running server process.
# When server is running, the file will be exclusively locked.
# If this file is locked by other process, server will abort and not continue start.
# If not configured, this file will not be generated and the lock will not be checked.
pid=

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

# The maximum number of threads that the service layer can process at the same time
# The default is the number of CPU cores
max_workers=

# Maximum number of threads used on the DB side
# Default is 50% of the number of cores
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

# SQL execution history database name
# Contents of sql history:
#     Client IP/Port, Execution start time, execution duration, SQL statement, execution result code, number of affected rows
# Storage is h2 format, you can use any sql tool to query the sql execution history.
# default is blank, means disable this feature.
sql_history=

# The TCP port opened by SQL History to the outside, In order to read the SQL history execution during the running process
# 0 means the system will randomly assign a port
# -1 means no port will be opened
# default is 0, means random port.
sql_history_port=

# SQL execution history file default placed directory
# Disk mode:   If not set, it defaults to the same as data_dir.
# Memory mode: If not set, it defaults to save it in memory.
sql_history_dir=
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

### Embed the dbserver in your code
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

### Embed the dbproxy in your code
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
