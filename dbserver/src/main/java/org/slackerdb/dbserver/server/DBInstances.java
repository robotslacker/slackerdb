package org.slackerdb.dbserver.server;

import org.slackerdb.dbserver.configuration.ServerConfiguration;
import org.slackerdb.common.exceptions.ServerException;
import org.slackerdb.common.utils.Utils;

import java.util.HashMap;
import java.util.Map;

public class DBInstances {
    public static final Map<String, DBInstance> dbInstanceMap = new HashMap<>();

    public static synchronized DBInstance createInstance(ServerConfiguration serverConfiguration) throws ServerException {
        String instanceName = serverConfiguration.getData();

        // 检查实例名是否已经重复
        if (dbInstanceMap.containsKey(instanceName))
        {
            throw new ServerException(
                    "SLACKERDB-00009",
                    "[SERVER] " + Utils.getMessage("SLACKERDB-00009", instanceName));
        }
        // 检查端口是否会发生重复
        for (DBInstance dbInstance : dbInstanceMap.values())
        {
            if (serverConfiguration.getPort() == dbInstance.serverConfiguration.getPort())
            {
                throw new ServerException(
                        "SLACKERDB-00010",
                        "[SERVER] " + Utils.getMessage("SLACKERDB-00010", dbInstance.serverConfiguration.getData()));
            }
        }
        DBInstance dbInstance = new DBInstance(serverConfiguration);
        dbInstanceMap.put(instanceName, dbInstance);

        return dbInstance;
    }

    public static synchronized void destroyInstance(String instanceName) throws ServerException
    {
        if (!dbInstanceMap.containsKey(instanceName))
        {
            throw new ServerException(
                    "SLACKERDB-00012",
                    "[SERVER] " + Utils.getMessage("SLACKERDB-00012", instanceName));
        }
        if (!dbInstanceMap.get(instanceName).instanceState.equalsIgnoreCase("IDLE"))
        {
            throw new ServerException(
                    "SLACKERDB-00011",
                    "[SERVER] " + Utils.getMessage("SLACKERDB-00011"));
        }
        DBInstances.dbInstanceMap.remove(instanceName);
    }
}
