![机器人小懒](robotslacker.jpg)

# SlackerDB (DUCKDB Postgres proxy)

## Quick Note
This program implements the JDBC V3 protocol of PG.
The SQL engine and storage engine behind it are both DUCKDB, the new favorite of OLAP.

What we do in this project:
1. make we can view and update duckdb data from the process outside.
2. make we can view and update duckdb data from the network.

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

## How to use
### Build from source:
```
    # Download JDK11 and maven 3.6+, and install them.
    # Download source code
    git clone ...
    # compile it
    cd slackerdb
    mvn clean compile package
```

### Create a configuration file
``` 
# Database name
data=slackerdb

# Path where data files are saved
# Empty means it is placed in memory (i.e.: memory:) , after db restart, all data will lost.
data_dir=:memory:

# The location of temporary files during database operation, the default is under data_dir
# It is strongly recommended to use a high-performance disk as a temporary directory.
# If memory resources are sufficient, you can also configure memory_limit to -1.
# -1 means all temporary files will exist only in the memory (only valid for memory mode)
temp_dir=

# Location of system extension plugin files
extension_dir=

# The location where the log file is saved
# CONSOLE means output to the console, and others mean output to a file
# Multiple logs can be output at the same time, separated by commas, for example, console, logs/xx.log
log=CONSOLE,logs/slackerdb.log

# Log level
log_level=INFO

# Service port
port=4309

# Service binding host
bind=0.0.0.0

# Client idle timeout (in seconds)
clientTimeout=600

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

# Initialization Script. Maybe file or directory.
# If it is a file, the file will be used.
# If it is a directory, all files with the .sql suffix in the directory will be used.
# The script(s in the directory) will be executed only when the in-memory database or file database is opened for the first time.
init_schema=


# SQL execution history
# Client IP/Port, Execution start time, execution duration, SQL statement, execution result code, number of affected rows
# True will save it in a separated db file, default is blank, means disable this feature.
# default placed directory is same with data_dir
sql_history=

# SQL execution history file default placed directory
# default same with data_dir
sql_history_dir=

```
Note: All parameters are optional.   
You can keep only the parameters you need to modify.   
For parameters that are not configured, means default values  will be used.

### Start the database:
``` 
java -jar slacker_xxx.jar start
```

### Stop the database:
```
java -jar slacker_xxx.jar stop
```

### Check the status of database:
```
java -jar slacker_xxx.jar status
```

### Kill running session:
```
java -jar slacker_xxx.jar kill <sessionId>
```

### Jdbc program
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
Currently, DBeaver is supported to connect to the database by configuring the JDBC driver.  
When configuring the driver, just select the Postgres driver.  
Navicat tool is not supported yet, and other tools have not been tested.  

## Next plan
...
