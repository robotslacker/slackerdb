package org.slackerdb.common.utils;

import org.apache.commons.io.FileUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.CRC32;

public class ClassUtil {
    public static URLClassLoader newClassLoader(String packagePath) throws IOException {
        File packageFile = new File(packagePath);
        if (!packageFile.exists())
        {
            throw new FileNotFoundException(packagePath);
        }
        return
                new URLClassLoader(
                        new URL[]
                                {
                                        new URL("jar:file:" + packageFile.getAbsolutePath() + "!/BOOT-INF/classes/")
                                },
                        null
                );
    }

    public static void extractFromClasspathToFile(String packagePath, File toDir)
            throws IOException, URISyntaxException
    {
        String locationPattern;
        if (packagePath.startsWith("classpath")) {
            locationPattern = packagePath;
        }
        else
        {
            locationPattern = "classpath*:" + packagePath;
        }
        ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
        String resourceBasePath = Objects.requireNonNull(ResourcePatternResolver.class.getResource("/"))
                .toURI().toString();

        Resource[] resources = resourcePatternResolver.getResources(locationPattern);
        for (Resource resource : resources) {
            if (resource.isReadable()) {
                // Skip hidden or system files
                final URL url = resource.getURL();
                String path = url.toString();
                if (!path.endsWith("/")) {
                    String relativePath;
                    if (resource instanceof ClassPathResource) {
                        // 使用 ClassPathResource 提供的路径，该路径是类路径内的相对路径
                        relativePath = ((ClassPathResource) resource).getPath();
                    } else {
                        // 回退到基于 resourceBasePath 的提取
                        String canonicalPath = path.replace(resourceBasePath, "");
                        // 如果替换未改变路径，说明不匹配，尝试从 URL 路径中提取
                        if (canonicalPath.equals(path)) {
                            // 尝试从 jar:file:...!/ 中提取路径
                            if (path.startsWith("jar:file:")) {
                                int bang = path.indexOf("!/");
                                if (bang != -1) {
                                    relativePath = path.substring(bang + 2);
                                } else {
                                    relativePath = resource.getFilename();
                                }
                            } else {
                                relativePath = resource.getFilename();
                            }
                        } else {
                            relativePath = canonicalPath;
                        }
                    }
                    // 确保相对路径是规范的（去除前导斜杠）
                    if (relativePath != null && relativePath.startsWith("/")) {
                        relativePath = relativePath.substring(1);
                    }
                    File targetFile = Paths.get(toDir.toString(), relativePath).toFile();
                    long len = resource.contentLength();
                    if (!targetFile.exists() || targetFile.length() != len)
                    {
                        FileUtils.copyURLToFile(url, targetFile);
                    }
                    else {
                        if (!targetFile.getParentFile().exists()) {
                            FileUtils.forceMkdir(targetFile.getParentFile());
                        }
                        CRC32 targetCRC32 = new CRC32();
                        try (FileInputStream fis = new FileInputStream(targetFile)) {
                            byte[] byteArray = new byte[1024];
                            int bytesRead;
                            while ((bytesRead = fis.read(byteArray)) != -1) {
                                targetCRC32.update(byteArray, 0, bytesRead);
                            }
                        }
                        CRC32 source32 = new CRC32();
                        // 获取输入流
                        try (InputStream inputStream = resource.getInputStream()) {
                            byte[] buffer = new byte[1024];  // 缓冲区
                            int bytesRead;
                            // 读取文件内容并更新CRC32
                            while ((bytesRead = inputStream.read(buffer)) != -1) {
                                source32.update(buffer, 0, bytesRead);
                            }
                        }
                        if (source32.getValue() != targetCRC32.getValue()) {
                            FileUtils.copyURLToFile(url, targetFile);
                        }
                    }
                }
            }
        }
    }

    public static Object invokeMethod(Class<?> clazz, String methodName, Object[] parameters)
    {
        try {
            // 查找方法
            Method invokeMethod = clazz.getMethod(methodName);
            // 创建实例
            Object instance = clazz.getDeclaredConstructor().newInstance();
            // 调用方法
            return invokeMethod.invoke(instance, parameters);
        }
        catch (Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }

    public static List<Class<?>> findSubClasses(String packageName, Class<?> parentClass) throws Exception {
        List<Class<?>> subTypes = new ArrayList<>();
        List<Class<?>> allClasses = getClassesInPackage(packageName);

        for (Class<?> clazz : allClasses) {
            if (parentClass.isAssignableFrom(clazz) && !clazz.equals(parentClass)) {
                subTypes.add(clazz);
            }
        }
        return subTypes;
    }

    public static List<Class<?>> getClassesInPackage(String packageName) throws IOException, ClassNotFoundException {
        List<Class<?>> classes = new ArrayList<>();
        String path = packageName.replace('.', '/');
        Enumeration<URL> resources = Thread.currentThread().getContextClassLoader().getResources(path);

        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            if (resource.getProtocol().equals("file")) {
                classes.addAll(getClassesFromDirectory(new File(resource.getFile()), packageName));
            } else if (resource.getProtocol().equals("jar")) {
                classes.addAll(getClassesFromJar(resource.getPath(), packageName));
            }
        }
        return classes;
    }

    private static List<Class<?>> getClassesFromDirectory(File directory, String packageName)
            throws ClassNotFoundException {
        List<Class<?>> classes = new ArrayList<>();
        if (!directory.exists()) {
            return classes;
        }

        File[] files = directory.listFiles();
        if (files == null) return classes;

        for (File file : files) {
            if (file.isDirectory()) {
                classes.addAll(getClassesFromDirectory(file, packageName + "." + file.getName()));
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + '.' + file.getName().substring(0, file.getName().length() - 6);
                classes.add(Class.forName(className));
            }
        }
        return classes;
    }

    private static List<Class<?>> getClassesFromJar(String jarPath, String packageName)
            throws IOException, ClassNotFoundException {
        List<Class<?>> classes = new ArrayList<>();
        String jarFilePath = jarPath.replaceFirst("[.]jar!.*", ".jar").replace("file:", "");
        try (JarFile jarFile = new JarFile(jarFilePath)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            String packagePath = packageName.replace('.', '/');

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entryName.startsWith(packagePath) && entryName.endsWith(".class")) {
                    String className = entryName.replace('/', '.').replace(".class", "");
                    classes.add(Class.forName(className));
                }
            }
        }
        return classes;
    }
}
