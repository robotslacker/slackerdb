package org.slackerdb.dbserver.server;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.slackerdb.common.exceptions.ServerException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GroovyInstance {
    private GroovyShell groovyShell = null;
    private Binding binding = null;

    private final DBInstance dbInstance;

    public GroovyInstance(DBInstance pDbInstance)
    {
        dbInstance = pDbInstance;
    }

    private void initInstance(Binding pBinding) throws ServerException
    {
        this.binding = pBinding;
        groovyShell = new GroovyShell(pBinding);
        // 首先加载系统预定义的函数文件
        try {
            InputStream resourceAsStream = DBInstance.class.getResourceAsStream("DBBuiltInFunctions.groovy");
            if (resourceAsStream != null) {
                // 通过 InputStreamReader 读取 groovy 脚本
                groovyShell.evaluate(new InputStreamReader(resourceAsStream, StandardCharsets.UTF_8));
                resourceAsStream.close();
            }
            // 首先加载程序参数文件中预定义的函数
            if (!dbInstance.serverConfiguration.getPlsql_func_dir().isEmpty()) {
                List<String> initScriptFiles = new ArrayList<>();
                if (new File(dbInstance.serverConfiguration.getPlsql_func_dir()).isFile()) {
                    initScriptFiles.add(new File(dbInstance.serverConfiguration.getPlsql_func_dir()).getAbsolutePath());
                } else if (new File(dbInstance.serverConfiguration.getPlsql_func_dir()).isDirectory()) {
                    File[] files = new File(dbInstance.serverConfiguration.getPlsql_func_dir()).listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.isFile() && file.getName().endsWith(".groovy")) {
                                initScriptFiles.add(file.getAbsolutePath());
                            }
                        }
                    }
                } else {
                    throw new ServerException("Init groovy [" + dbInstance.serverConfiguration.getPlsql_func_dir() + "] does not exist!");
                }
                Collections.sort(initScriptFiles);
                for (String initScriptFile : initScriptFiles) {
                    groovyShell.evaluate(new File(initScriptFile));
                }
            }
        } catch (IOException ie) {
            throw new ServerException("Init groovy [" + dbInstance.serverConfiguration.getPlsql_func_dir() + "] error!", ie);
        }
    }

    public Object evaluate(String pExpression, Binding pBinding) throws ServerException
    {
        if (groovyShell == null)
        {
            initInstance(pBinding);
        }
        else {
            for (Object obj : pBinding.getVariables().keySet()) {
                String variableName = (String) obj;
                binding.setVariable(variableName, pBinding.getVariable(variableName));
            }
        }
        return groovyShell.evaluate(pExpression);
    }
}
