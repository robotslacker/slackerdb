package org.slackerdb.common.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 进程管理工具类，支持在 Windows 和 Linux 上查找和终止进程。
 * 提供了基于进程名、命令行参数等多种查找方式。
 */
public class ProcessUtil {

    /**
     * 根据进程名查找进程 ID（Windows 和 Linux 通用）
     * @param processName 进程名（例如：java.exe, notepad.exe）
     * @return 进程 ID 列表
     */
    public static List<Long> findProcessIdsByName(String processName) throws IOException, InterruptedException {
        if (OSUtil.isWindows()) {
            return findProcessIdsByNameWindows(processName);
        } else {
            return findProcessIdsByNameLinux(processName);
        }
    }

    /**
     * 根据命令行参数查找进程 ID（Windows 和 Linux 通用）
     * @param commandLineArg 命令行参数（部分匹配）
     * @return 进程 ID 列表
     */
    public static List<Long> findProcessIdsByCommandLine(String commandLineArg) throws IOException, InterruptedException {
        if (OSUtil.isWindows()) {
            return findProcessIdsByCommandLineWindows(commandLineArg);
        } else {
            return findProcessIdsByCommandLineLinux(commandLineArg);
        }
    }

    /**
     * 终止指定进程 ID 的进程
     * @param pid 进程 ID
     * @return 是否成功终止
     */
    public static boolean killProcess(long pid) throws IOException, InterruptedException {
        if (OSUtil.isWindows()) {
            return killProcessWindows(pid);
        } else {
            return killProcessLinux(pid);
        }
    }

    /**
     * 终止所有匹配进程名的进程
     * @param processName 进程名
     * @return 成功终止的进程数量
     */
    public static int killProcessesByName(String processName) throws IOException, InterruptedException {
        List<Long> pids = findProcessIdsByName(processName);
        int killed = 0;
        for (Long pid : pids) {
            if (killProcess(pid)) {
                killed++;
            }
        }
        return killed;
    }

    /**
     * 终止所有匹配命令行参数的进程
     * @param commandLineArg 命令行参数
     * @return 成功终止的进程数量
     */
    public static int killProcessesByCommandLine(String commandLineArg) throws IOException, InterruptedException {
        List<Long> pids = findProcessIdsByCommandLine(commandLineArg);
        int killed = 0;
        for (Long pid : pids) {
            if (killProcess(pid)) {
                killed++;
            }
        }
        return killed;
    }

    // Windows 实现
    private static List<Long> findProcessIdsByNameWindows(String processName) throws IOException, InterruptedException {
        List<Long> pids = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH");
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                // CSV 格式: "java.exe","1234","Console","1","100,000 K"
                String[] parts = line.split("\",\"");
                if (parts.length >= 2) {
                    String name = parts[0].replace("\"", "");
                    if (name.equalsIgnoreCase(processName)) {
                        String pidStr = parts[1].replace("\"", "");
                        try {
                            pids.add(Long.parseLong(pidStr));
                        } catch (NumberFormatException e) {
                            // 忽略格式错误
                        }
                    }
                }
            }
        }
        process.waitFor();
        return pids;
    }

    private static List<Long> findProcessIdsByCommandLineWindows(String commandLineArg) throws IOException, InterruptedException {
        List<Long> pids = new ArrayList<>();
        // 使用 wmic 获取进程命令行
        ProcessBuilder pb = new ProcessBuilder("wmic", "process", "get", "processid,commandline");
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.contains("CommandLine")) continue;
                // 格式: "java.exe -jar myapp.jar" 1234
                int lastSpace = line.lastIndexOf(' ');
                if (lastSpace > 0) {
                    String cmdLine = line.substring(0, lastSpace).trim();
                    String pidStr = line.substring(lastSpace).trim();
                    if (cmdLine.toLowerCase().contains(commandLineArg.toLowerCase())) {
                        try {
                            pids.add(Long.parseLong(pidStr));
                        } catch (NumberFormatException e) {
                            // 忽略格式错误
                        }
                    }
                }
            }
        }
        process.waitFor();
        return pids;
    }

    private static boolean killProcessWindows(long pid) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/PID", String.valueOf(pid));
        Process process = pb.start();
        int exitCode = process.waitFor();
        return exitCode == 0;
    }

    // Linux 实现
    private static List<Long> findProcessIdsByNameLinux(String processName) throws IOException, InterruptedException {
        List<Long> pids = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder("ps", "-e", "-o", "pid,comm");
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            reader.readLine(); // 跳过标题行
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+", 2);
                if (parts.length >= 2) {
                    String name = parts[1].trim();
                    if (name.equals(processName) || name.contains(processName)) {
                        try {
                            pids.add(Long.parseLong(parts[0].trim()));
                        } catch (NumberFormatException e) {
                            // 忽略格式错误
                        }
                    }
                }
            }
        }
        process.waitFor();
        return pids;
    }

    private static List<Long> findProcessIdsByCommandLineLinux(String commandLineArg) throws IOException, InterruptedException {
        List<Long> pids = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder("ps", "-e", "-o", "pid,args");
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            reader.readLine(); // 跳过标题行
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+", 2);
                if (parts.length >= 2) {
                    String cmdLine = parts[1].trim();
                    if (cmdLine.contains(commandLineArg)) {
                        try {
                            pids.add(Long.parseLong(parts[0].trim()));
                        } catch (NumberFormatException e) {
                            // 忽略格式错误
                        }
                    }
                }
            }
        }
        process.waitFor();
        return pids;
    }

    private static boolean killProcessLinux(long pid) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("kill", "-9", String.valueOf(pid));
        Process process = pb.start();
        int exitCode = process.waitFor();
        return exitCode == 0;
    }

    /**
     * 使用 Java 9+ 的 ProcessHandle API 查找进程（更高效，但需要 Java 9+）
     * @param processName 进程名
     * @return 进程 ID 列表
     */
    public static List<Long> findProcessIdsByNameModern(String processName) {
        return ProcessHandle.allProcesses()
                .filter(ph -> ph.info().command().map(cmd -> cmd.contains(processName)).orElse(false))
                .map(ProcessHandle::pid)
                .collect(Collectors.toList());
    }

    /**
     * 使用 Java 9+ 的 ProcessHandle API 终止进程
     * @param pid 进程 ID
     * @return 是否成功终止
     */
    public static boolean killProcessModern(long pid) {
        Optional<ProcessHandle> ph = ProcessHandle.of(pid);
        return ph.map(ProcessHandle::destroy).orElse(false);
    }

    /**
     * 示例用法
     */
    public static void main(String[] args) throws Exception {
        System.out.println("=== 进程管理工具示例 ===");
        
        // 1. 查找所有 java.exe 进程
        System.out.println("查找 java.exe 进程...");
        List<Long> javaPids = findProcessIdsByName("java.exe");
        System.out.println("找到 " + javaPids.size() + " 个进程: " + javaPids);
        
        // 2. 查找包含特定命令行参数的进程
        System.out.println("查找包含 'myapp.jar' 的进程...");
        List<Long> appPids = findProcessIdsByCommandLine("myapp.jar");
        System.out.println("找到 " + appPids.size() + " 个进程: " + appPids);
        
        // 3. 终止进程（示例，实际使用时请谨慎）
        if (!javaPids.isEmpty()) {
            long pid = javaPids.get(0);
            System.out.println("尝试终止进程 " + pid + "...");
            boolean success = killProcess(pid);
            System.out.println("终止结果: " + (success ? "成功" : "失败"));
        }
        
        // 4. 使用现代 API（Java 9+）
        System.out.println("使用现代 API 查找进程...");
        List<Long> modernPids = findProcessIdsByNameModern("java");
        System.out.println("现代 API 找到 " + modernPids.size() + " 个进程");
    }
}