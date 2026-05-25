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

/**
 * 类操作工具类。
 * 提供类加载、反射调用、类路径扫描等功能的实用方法。
 *
 * <p>该类主要用于动态加载类、执行反射操作、查找包中的类以及提取类路径资源。</p>
 *
 * <p>功能包括：</p>
 * <ul>
 *   <li>创建自定义类加载器加载JAR包中的类</li>
 *   <li>从类路径提取资源文件到文件系统</li>
 *   <li>通过反射调用类的方法</li>
 *   <li>查找指定包中继承特定父类的所有子类</li>
 *   <li>扫描包中的所有类（支持文件系统和JAR包）</li>
 * </ul>
 *
 * <p>注意：部分方法依赖于Spring框架的Resource相关类。</p>
 */
public class ClassUtil {
    /**
     * 创建用于加载指定JAR包中类的新类加载器。
     *
     * <p>该方法创建一个{@link URLClassLoader}，专门用于加载指定JAR文件中的类。
     * 注意：该实现假设JAR文件是Spring Boot可执行JAR格式，类文件位于BOOT-INF/classes/目录下。</p>
     *
     * <p>使用示例：</p>
     * <pre>
     * URLClassLoader loader = ClassUtil.newClassLoader("/path/to/app.jar");
     * Class<?> clazz = loader.loadClass("com.example.MyClass");
     * </pre>
     *
     * @param packagePath JAR文件的路径
     * @return 配置好的URLClassLoader实例
     * @throws IOException 如果指定的JAR文件不存在或无法访问
     * @throws FileNotFoundException 如果指定的文件不存在
     */
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

    /**
     * 从类路径中提取资源文件到指定目录。
     *
     * <p>该方法扫描类路径中匹配指定模式的所有资源，并将它们复制到目标目录中。
     * 如果目标文件已存在，会通过比较文件大小和CRC32校验和来判断是否需要更新。</p>
     *
     * <p>支持的模式：</p>
     * <ul>
     *   <li>classpath*:path/to/resources - 扫描所有类路径（包括JAR文件）</li>
     *   <li>classpath:path/to/resources - 仅扫描当前类加载器的类路径</li>
     *   <li>path/to/resources - 自动添加classpath*:前缀</li>
     * </ul>
     *
     * <p>该方法会保持资源在类路径中的相对路径结构，并跳过目录（仅提取文件）。</p>
     *
     * @param packagePath 类路径模式，指定要提取的资源位置
     * @param toDir 目标目录，资源将被提取到此目录
     * @throws IOException 如果IO操作失败
     * @throws URISyntaxException 如果资源URI格式无效
     */
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

    /**
     * 通过反射调用指定类的无参方法。
     *
     * <p>该方法会创建类的实例（通过默认构造函数），然后调用指定的方法。
     * 注意：当前实现假设方法是无参数的，但参数数组被传递给调用（可能存在不一致）。</p>
     *
     * <p>典型用法：</p>
     * <pre>
     * Object result = ClassUtil.invokeMethod(MyClass.class, "myMethod", new Object[]{});
     * </pre>
     *
     * <p>注意：该方法会封装所有异常为RuntimeException。</p>
     *
     * @param clazz 要调用的类
     * @param methodName 方法名称
     * @param parameters 方法参数数组（当前实现中方法查找使用无参getMethod，但调用时传递参数）
     * @return 方法调用的返回值
     * @throws RuntimeException 如果反射调用失败（如方法不存在、构造函数不可访问等）
     */
    public static Object invokeMethod(Class<?> clazz, String methodName, Object[] parameters)
    {
        try {
            // 根据参数类型查找方法
            Class<?>[] parameterTypes = parameters != null
                    ? Arrays.stream(parameters).map(Object::getClass).toArray(Class<?>[]::new)
                    : new Class<?>[0];
            Method invokeMethod = clazz.getMethod(methodName, parameterTypes);
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

    /**
     * 查找指定包中继承指定父类的所有子类。
     *
     * <p>该方法首先获取包中的所有类，然后过滤出继承自指定父类（包括间接继承）的类，
     * 排除父类自身。</p>
     *
     * <p>使用示例：</p>
     * <pre>
     * List<Class<?>> plugins = ClassUtil.findSubClasses("com.example.plugins", Plugin.class);
     * </pre>
     *
     * @param packageName 要搜索的包名
     * @param parentClass 父类（接口或抽象类）
     * @return 继承自父类的所有子类列表
     * @throws Exception 如果类加载或包扫描失败
     * @see #getClassesInPackage(String)
     */
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

    /**
     * 获取指定包中的所有类。
     *
     * <p>该方法扫描类路径，查找指定包中的所有类文件。支持从文件系统和JAR包中加载类。</p>
     *
     * <p>实现原理：</p>
     * <ol>
     *   <li>将包名转换为路径格式（如com.example → com/example）</li>
     *   <li>通过当前线程的上下文类加载器获取该路径的所有资源URL</li>
     *   <li>根据URL协议（file或jar）调用相应的处理方法</li>
     * </ol>
     *
     * @param packageName 要扫描的包名（如"com.example.utils"）
     * @return 包中所有类的列表
     * @throws IOException 如果IO操作失败
     * @throws ClassNotFoundException 如果类文件存在但无法加载
     * @see #getClassesFromDirectory(File, String)
     * @see #getClassesFromJar(String, String)
     */
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

    /**
     * 从文件系统目录中递归获取指定包下的所有类。
     *
     * <p>该方法递归遍历目录，查找所有.class文件，并将其转换为完整的类名后加载。</p>
     *
     * @param directory 要扫描的目录
     * @param packageName 当前扫描的包名（递归过程中会变化）
     * @return 目录中所有类的列表
     * @throws ClassNotFoundException 如果类文件存在但无法加载
     */
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

    /**
     * 从JAR文件中获取指定包下的所有类。
     *
     * <p>该方法打开JAR文件，遍历所有条目，筛选出指定包路径下的.class文件，
     * 并将其转换为完整的类名后加载。</p>
     *
     * <p>注意：该方法会处理JAR URL的格式，如"file:/path/to.jar!/com/example"。</p>
     *
     * @param jarPath JAR文件的路径（可能包含"!/"分隔符）
     * @param packageName 要扫描的包名
     * @return JAR包中指定包下的所有类
     * @throws IOException 如果JAR文件无法读取
     * @throws ClassNotFoundException 如果类文件存在但无法加载
     */
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
