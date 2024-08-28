![机器人小懒](robotslacker.jpg)

# SlackerDB (DUCKDB Postgres proxy)

## Quick Note
This program implements the JDBC V3 protocol of PG.
The SQL engine and storage engine behind it are both DUCKDB, the new favorite of OLAP.

What we do in this project:
1. make we can view and update duckdb data from the outside process.
2. make we can view and update duckdb data from the network.

## Known Issues
### Time Zone Issues with TimeStamp
  PG clients will always use the current system time zone as a parameter in setTimeStamp, and duckdb's TimeStamp has no concept of time zone.  
  This will cause the data inserted using api setTimeStamp() is different with your inserted.  
  Workaround:   
  The client always uses the UTC time zone
### User and password authorization
  We do not support user password authentication, just for compatibility, keep these two options.  
  you can fill in the password part as you like, it doesn't make sense.  
  The user part will be used by the default schema of the user connection.
### Limited support for duckdb datatype
  Only some duckdb data types are supported, mainly simple types, such as int, number, double, varchar, ... 
  For complex types, some are still under development, and some are not supported by the PG protocol, such as blob, list, map...
  You can refer to sanity01.java to see what we currently support.

## How to use
### Build from source:
```
    # Download JDK11 and maven 3.6+, and install them.
    git clone ...
    cd slackerdb
    mvn clean compile package
    java -jar target\slackerdb-0.0.3.jar --conf <your configuration file> start  
```

### create a configuration file
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
# Since Netty does not recycle the content of each processing thread, setting this value too large will cause memory overflow
max_workers=

# Maximum number of threads used on the DB side
# Default is 50% of the number of cores
threads=

# Maximum memory size used
# Default is 60% of available memory
memory_limit=
```
Note: All parameters are optional.   
You can keep only the parameters you need to modify.   
For parameters that are not configured, means that the default values  will be used.

For max_workers, there are some empirical test results for reference.  
Based on TPCDS 10G, at 20 concurrency, about 110G memory will be consumed.   
At 4 concurrency, about 20G memory will be consumed.   
The actual consumption is related to the size and complexity of the data.   
Due to the characteristics of OLAP queries, too high concurrency will not have practical significance.

### start the database:
``` 
java -jar slacker_xxx.jar --conf <your configuration file> start
```

### stop the database:
```
java -jar slacker_xxx.jar --conf <your configuration file> stop
```

### check the status of database:
```
java -jar slacker_xxx.jar --conf <your configuration file> status
```

## Use IDE tools to connect to the database
Currently, DBeaver is supported to connect to the database by configuring the JDBC driver.  
When configuring the driver, just select the Postgres driver.  
Navicat tool is not supported yet, and other tools have not been tested.  


## Next plan
...
