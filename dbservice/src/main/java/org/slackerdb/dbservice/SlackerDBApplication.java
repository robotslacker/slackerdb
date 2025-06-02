package org.slackerdb.dbservice;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.TimeZone;

@SpringBootApplication
@EnableWebMvc
public class SlackerDBApplication {
    public static ConfigurableApplicationContext context;

    public static void main(String[] args) {
        disableAccessWarnings();

        // 设置程序的默认时区，避免TimeZone的问题
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // 启动应用程序
        context = SpringApplication.run(SlackerDBApplication.class, args);
    }

    @SuppressWarnings("rawtypes")
    public static void disableAccessWarnings() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);

            Method putObjectVolatile = unsafeClass.getDeclaredMethod("putObjectVolatile", Object.class, long.class, Object.class);
            Method staticFieldOffset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field.class);

            Class loggerClass = Class.forName("jdk.internal.module.IllegalAccessLogger");
            Field loggerField = loggerClass.getDeclaredField("logger");
            Long offset = (Long) staticFieldOffset.invoke(unsafe, loggerField);
            putObjectVolatile.invoke(unsafe, loggerClass, offset, null);
        } catch (Exception ignored) {
        }
    }
}
