package org.slackerdb.dbcli.help;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code _HELP} 的**唯一**文本来源，同时也是 README 里"命令速查"表的生成器。
 *
 * <h2>为什么要单独一个类</h2>
 * <p>以前 {@code _HELP} 的文案与 README 各写一份，两边会慢慢漂移（本模块已经漂移过一次：
 * {@code _COMPARE} 改退出码、{@code _WHENEVER} 的默认退出码、作业的 {@code .out/.err}）。
 * 现在命令清单与用法只有这一份数据：</p>
 * <ul>
 *   <li>{@link #overview()} 生成 {@code _HELP} 的总览；</li>
 *   <li>{@link #topic(String, int)} 生成 {@code _HELP <命令>} 的详情；</li>
 * </ul>
 *
 * <p><b>这里是英文文案</b>：{@code _HELP} 的输出语言一直是英文，README 的中文说明在各自的
 * 小节里（README 的速查表只列用法，语言无关，因此不需要在每个命令上维护第二份译文）。</p>
 */
public final class HelpCatalog {

    /**
     * 一条命令（或一条用法形态）。
     *
     * @param usage   用法原文（含命令字，例如 {@code "_SET ECHO ON|OFF"}）
     * @param summary 说明行（第一行与 {@code usage} 同行，其余行对齐到说明列；可为空）
     */
    public record Entry(String usage, List<String> summary) {
    }

    /** 总览里说明文字的起始列（0 基）。 */
    private static final int SUMMARY_COLUMN = 31;

    /** README 生成块的标记（测试据此定位；改标记要同时改 README 与测试）。 */
    public static final String BEGIN_MARKER = "// BEGIN GENERATED: help-catalog";
    public static final String END_MARKER = "// END GENERATED: help-catalog";

    /** 命令清单：顺序就是 {@code _HELP} 的打印顺序，也是 README 速查表的行序。 */
    private static final List<Entry> ENTRIES = List.of(
            new Entry("<SQL statement>", List.of(
                    "SELECT/INSERT/UPDATE/DELETE/CREATE/DROP/REPLACE/DECLARE/BEGIN/",
                    "PROCEDURE/COMMIT/ROLLBACK; end with ';' (or a line with '/' for blocks)",
                    "several statements may share one line: 'select 1; select 2;'")),
            new Entry("_ASSERT <l> <op> <r> [, n]", List.of(
                    "evaluate an assertion against the last SQL result")),
            new Entry("_COMPARE <work> <ref>", List.of(
                    "compare two files (see _HELP COMPARE)")),
            new Entry("_CONNECT <host>:<port>/<service>[?k=v]", List.of(
                    "open a connection (no defaults, no user/password)")),
            new Entry("_CONNECT [<user>[/<password>]@]jdbc:slackerdb://<host>:<port>/[<database>]", List.of(
                    "open a connection from the full driver URL (user/password and database optional)")),
            new Entry("_CONNECT [<user>[/<password>]@]jdbc:<scheme>:<url>", List.of(
                    "open a connection with another JDBC driver (the URL is passed through verbatim)")),
            new Entry("_DISCONNECT", List.of(
                    "close the current connection")),
            new Entry("_ECHO ... echo off", List.of(
                    "echo the following lines as data")),
            new Entry("_EXIT [n] | _QUIT [n]", List.of(
                    "stop the script with exit code n")),
            new Entry("_HELP [topic]", List.of(
                    "this list, or details for one command")),
            new Entry("_HOST <command>", List.of(
                    "run a local command; its stdout/stderr lines are printed")),
            new Entry("_JOB ...", List.of(
                    "concurrent jobs (see _HELP JOB)")),
            new Entry("_LOAD JDBCDRIVER <jar>", List.of(
                    "load a JDBC driver from a jar (_CONNECT can then use its URLs)")),
            new Entry("_SESSION SAVE|RESTORE|RELEASE|SAVEURL|SHOW <name>", List.of()),
            new Entry("_SET @name <value>", List.of(
                    "set a script variable")),
            new Entry("_SET ECHO ON|OFF", List.of(
                    "echo each statement/command as it runs (default ON)")),
            new Entry("_SET FEEDBACK ON|OFF", List.of(
                    "print status lines and errors, e.g. 'N rows selected.' (default ON)")),
            new Entry("_SET TERMOUT ON|OFF", List.of(
                    "print result output, also to the log file (default ON)")),
            new Entry("_SET TIMING ON|OFF", List.of(
                    "print '<m> minutes <s> seconds <ms> ms elapsed.' after each",
                    "statement (default OFF; stdout)")),
            new Entry("_SET TIME ON|OFF", List.of(
                    "print 'Current time: yyyy-MM-dd HH:mm:ss.SSS' after each",
                    "statement (default OFF; stdout)")),
            new Entry("_SET OUTFORMAT table|vertical|csv", List.of(
                    "result format (default table)")),
            new Entry("_SET HEADER ON|OFF", List.of(
                    "print the header row (default ON)")),
            new Entry("_SET LINEWIDTH <n>", List.of(
                    "max display columns per line, longer lines wrap (default 200)")),
            new Entry("_SET COLWIDTH <n>", List.of(
                    "max display columns per cell, longer cells end with ...",
                    "(default 40; LINEWIDTH/COLWIDTH do not apply to csv)")),
            new Entry("_SET DIFF_REPORT_HTML ON|OFF", List.of(
                    "also write <work>.html for a differing _COMPARE",
                    "(default OFF; the .dif file is always written)")),
            new Entry("_SET AUTOCOMMIT ON|OFF", List.of(
                    "switch JDBC autocommit")),
            new Entry("_SET TIMEOUT <n>", List.of(
                    "per-statement server timeout in seconds (0 = none)")),
            new Entry("_SLEEP <seconds>", List.of(
                    "sleep")),
            new Entry("_SPOOL <file>", List.of(
                    "start a tee to <file>; _SPOOL OFF stops it (no bare form)")),
            new Entry("_SSH ...", List.of(
                    "remote SSH/SFTP (see _HELP SSH)")),
            new Entry("_START <script> [args...]", List.of(
                    "run a child script; args are ${ARGV1}, ${ARGV2}…")),
            new Entry("_WHENEVER ERROR CONTINUE|EXIT <n>", List.of()));

    /** 总览末尾的变量说明（不是命令，因此不参与 README 速查表）。 */
    private static final List<String> TRAILER = List.of(
            "Variables: ${name}; sources = _SET @name, --var, ${ARGVn}, environment;",
            "  an undefined name falls back to the same-named environment variable, else it is",
            "  left as written (no error, no empty string).");

    private HelpCatalog() {
    }

    /** {@code _HELP} 的总览：{@code "Available commands:"} + 每条用法 + 变量说明。 */
    public static List<String> overview() {
        List<String> lines = new ArrayList<>();
        lines.add("Available commands:");
        for (Entry entry : ENTRIES) {
            lines.add(usageLine(entry));
            for (int index = 1; index < entry.summary().size(); index++) {
                lines.add(" ".repeat(SUMMARY_COLUMN) + entry.summary().get(index));
            }
        }
        lines.addAll(TRAILER);
        return List.copyOf(lines);
    }

    /**
     * {@code _HELP <topic>} 的详情；主题名不区分大小写。
     *
     * @param maxScriptDepth {@code _START} 的嵌套上限（文案里要写具体数字，因此由调用方给）
     * @return 详情行；未知主题返回 null（引擎负责报 {@code Unknown help topic [...]}）
     */
    public static List<String> topic(String name, int maxScriptDepth) {
        if (name == null) {
            return null;
        }
        return TOPICS.get(name.strip().toUpperCase(Locale.ROOT)) == null ? null
                : materialize(TOPICS.get(name.strip().toUpperCase(Locale.ROOT)), maxScriptDepth);
    }

    /** 所有用法（README 速查表与测试用；顺序稳定）。 */
    public static List<String> usages() {
        List<String> usages = new ArrayList<>(ENTRIES.size());
        for (Entry entry : ENTRIES) {
            usages.add(entry.usage());
        }
        return List.copyOf(usages);
    }

    /**
     * README 里的"命令速查"块（含生成标记；内容在标记之间）。
     *
     * <p>只有用法、没有说明：用法是语言无关的，中英两份 README 因此共享同一份清单，
     * 不需要为每条命令再维护一份译文。用法原文**逐字**写进块里（不做 asciidoc 转义），
     * 这样 grep 用法与 README 的一致性时不会被转义字符干扰。</p>
     */
    public static String readmeBlock() {
        StringBuilder block = new StringBuilder();
        block.append(BEGIN_MARKER)
                .append(" (source: help/HelpCatalog.java; checked by HelpCatalogReadmeTest)\n");
        for (String usage : usages()) {
            block.append("* `").append(usage).append("`\n");
        }
        block.append(END_MARKER).append("\n");
        return block.toString();
    }

    // ------------------------------------------------------------------ 内部

    /** 一条用法行：{@code "  <usage><padding><summary 第一行>"}。 */
    private static String usageLine(Entry entry) {
        String head = "  " + entry.usage();
        if (entry.summary().isEmpty()) {
            return head;
        }
        int padding = Math.max(1, SUMMARY_COLUMN - head.length());
        return head + " ".repeat(padding) + entry.summary().get(0);
    }

    /** 把主题文本里的 {@code {depth}} 占位符换成实际嵌套上限。 */
    private static List<String> materialize(List<String> template, int maxScriptDepth) {
        List<String> lines = new ArrayList<>(template.size());
        for (String line : template) {
            lines.add(line.replace("{depth}", String.valueOf(maxScriptDepth)));
        }
        return List.copyOf(lines);
    }

    /** 详情主题（英文；与 {@code _HELP} 的输出一致）。 */
    private static final Map<String, List<String>> TOPICS = buildTopics();

    private static Map<String, List<String>> buildTopics() {
        Map<String, List<String>> topics = new LinkedHashMap<>();
        topics.put("ASSERT", List.of(
                "_ASSERT <selector|literal> <comparator> <literal> [, <label>]",
                "  selectors: RESULT.ERRORCODE / RESULT.ROWCOUNT / RESULT.STATUS / RESULT.ROWS.COUNT",
                "             RESULT.HEADERS[i] / RESULT.COLUMNS[i] / RESULT.ROWS[r][c] (0-based)",
                "             VAR.<name> / ENV.<name> / RUN.EXITCODE",
                "             JOB.<name>.STATUS / JOB.<name>.EXITCODE",
                "  comparators: = == <> != > >= < <= CONTAINS NOTCONTAINS MATCHES EXISTS",
                "  [, <label>] is a name for this assert: it only shows up in the status line",
                "             (Assert [<label>] successful. / fail.), it is not a variable or alias.",
                "  a failing assert counts as an error (so _WHENEVER ERROR applies)."));
        topics.put("COMPARE", List.of(
                "_COMPARE <workFile> <refFile> [MASK|CASE|IGBLANK|TRIM ...]",
                "_COMPARE SET|RESET|SKIPLINE|NOSKIPLINE|MASKLINE|NOMASKLINE ...",
                "  a difference is reported as 'Compare failed.' and counts as an error; it does not",
                "  change the exit code unless _WHENEVER ERROR EXIT is set (--reference does: 1).",
                "  artifacts follow this run's verdict: .dif/.html are removed on a match, .suc on a",
                "  difference (so the previous verdict's files never linger)."));
        topics.put("CONNECT", List.of(
                "_CONNECT <host>:<port>/<service>[?k=v&k2=v2]",
                "_CONNECT [<user>[/<password>]@]jdbc:slackerdb://<host>:<port>/[<database>]",
                "_CONNECT [<user>[/<password>]@]jdbc:<scheme>:<url>",
                "  the short form has no defaults: host, port and service are all required, and the",
                "  client does not accept a user name or a password there.",
                "  the slackerdb form is the driver URL: the scheme must be jdbc:slackerdb:, host and",
                "  port are still required, and the user name/password and the database may be omitted",
                "  (without a database the driver gets '...:port/' and uses its own default database,",
                "  which is currently the user name).",
                "  any other jdbc:<scheme>: URL (jdbc:sqlite:, jdbc:postgresql:, ...) is handed to the",
                "  matching JDBC driver on the classpath exactly as written; the client does not",
                "  interpret it. Without such a driver the connection fails with 'No suitable driver'.",
                "  a URL that contains ';' (the statement separator) must be quoted:",
                "  _CONNECT \"jdbc:h2:mem:t;DB_CLOSE_DELAY=-1\".",
                "  connect parameters (k=v) are passed through in every form.",
                "  other forms are reported as 'Invalid connection string [...]'."));
        topics.put("ECHO", List.of(
                "_ECHO <title>",
                "  <lines to echo>",
                "  echo off",
                "  the lines between _ECHO and 'echo off' are printed as data (variables are resolved)."));
        topics.put("DISCONNECT", List.of(
                "_DISCONNECT",
                "  close the current connection (session names pointing at it expire too)."));
        topics.put("EXIT", List.of(
                "_EXIT [n] | _QUIT [n]",
                "  stop the whole run right away with exit code n (default 0); _QUIT is a synonym."));
        topics.put("HELP", List.of(
                "_HELP [topic]",
                "  without a topic: this list; with one: the details of that command.",
                "  topics: ASSERT COMPARE CONNECT DISCONNECT ECHO EXIT HELP HOST JOB LOAD SESSION SET",
                "          SLEEP SPOOL SSH START WHENEVER"));
        topics.put("LOAD", List.of(
                "_LOAD JDBCDRIVER <jar>",
                "  load a JDBC driver from an external jar into this process; after that _CONNECT",
                "  accepts the URLs that driver understands (the jar stays open for this run).",
                "  the same jar is loaded only once ('already loaded'); a relative path resolves",
                "  against the script working directory, and a path with spaces may be quoted.",
                "  only JDBCDRIVER is supported: other targets are reported as",
                "  'Unsupported _LOAD target [...]'."));
        topics.put("SLEEP", List.of(
                "_SLEEP <seconds>",
                "  sleep for that many seconds (fractions allowed); a job stop request interrupts it."));
        topics.put("HOST", List.of(
                "_HOST <command>",
                "  runs the command through cmd.exe /c (Windows) or /bin/sh -c (elsewhere);",
                "  stdout lines become data, stderr lines become errors (both on stdout); a non-zero",
                "  exit code is an error. A multi-line block (_HOST \"\"\" ... \"\"\") runs one command",
                "  per line (' & ' separates them; a quoted ' & ' is data, not a separator)."));
        topics.put("JOB", List.of(
                "_JOB JOBMANAGER ON|OFF",
                "  start/stop accepting new jobs (jobs that are already running are not touched);",
                "_JOB CREATE <name> script=<file> [k=v...] [mode=thread|process] [timeout=s]",
                "  define a job: a script plus the variables its script can read (${k});",
                "  mode=thread (default) runs it as a thread here, mode=process as its own dbcli process;",
                "  logon/profile/logfile/reference are run options, not variables;",
                "_JOB SET <name> k=v...       redefine a job that is not running",
                "_JOB START <name>            start it (it runs concurrently with this script)",
                "_JOB WAIT <name> [timeout=s]",
                "  wait for a job, or for a timer point (JOB:/TIMER: prefix);",
                "  timeout must be a positive number of seconds (omitting it waits until the job ends);",
                "  a FAILED/TIMEOUT job or a wait timeout counts as an error (the exit code stays 0",
                "  unless _WHENEVER ERROR EXIT is set); a job that fails and is never waited for is",
                "  reported at the end of the script as an error, so it cannot fail silently;",
                "_JOB SHOW <name>             state / thread / elapsed / exitCode",
                "_JOB ABORT <name>            stop it now",
                "_JOB SHUTDOWN <name>         stop it gracefully (waits briefly, then forces)",
                "_JOB TIMER <point>           release a timer point: one barrier shared by every job",
                "  every job writes one file <name>.out (its stdout and stderr, in order); a parent",
                "  script and jobs at any depth can wait for each other at timer points:",
                "  _JOB WAIT TIMER:<point> in the job, _JOB TIMER <point> in the parent."));
        topics.put("SESSION", List.of(
                "_SESSION SAVE|RESTORE|RELEASE|SAVEURL|SHOW <name>",
                "  names are references to sessions: SAVE attaches a name, RESTORE switches to it,",
                "  SAVEURL stores only the URL (RESTORE reconnects), SHOW lists them."));
        topics.put("SET", List.of(
                "_SET                         print the option table",
                "_SET @name <value>           set a script variable (${name})",
                "_SET ECHO ON|OFF             write the executed statements to the log file (default ON;",
                "                             scripts and console alike)",
                "_SET FEEDBACK ON|OFF         print summaries and errors, e.g. 'N rows selected.'",
                "                             (default ON; the exit code still reflects them)",
                "_SET TERMOUT ON|OFF          display result sets (default ON; OFF drops them everywhere,",
                "                             the log file and _SPOOL included)",
                "_SET TIMING ON|OFF           per-statement elapsed time (stdout)",
                "                             ('3 minutes 25 seconds 222 ms elapsed.'; default OFF)",
                "_SET TIME ON|OFF             current system time after each statement (stdout)",
                "                             ('Current time: 2022-01-01 03:33:33.000'; default OFF)",
                "_SET OUTFORMAT table|vertical|csv   result format (default table)",
                "_SET HEADER ON|OFF           print the header row (default ON)",
                "_SET LINEWIDTH <n>           max display columns per line; longer lines wrap",
                "                             (default 200; does not apply to csv)",
                "_SET COLWIDTH <n>            max display columns per cell; longer cells end with '...'",
                "                             (default 40; does not apply to csv)",
                "_SET DIFF_REPORT_HTML ON|OFF also write <work>.html for a differing _COMPARE",
                "                             (default OFF; the .dif file is always written)",
                "_SET AUTOCOMMIT ON|OFF       switch JDBC autocommit",
                "_SET TIMEOUT <n>             per-statement server timeout in seconds (0 = none)"));
        topics.put("SSH", List.of(
                "_SSH CONNECT <host> WITH USER <user> (PASSWORD <pw> | KEYFILE <file>) [PORT <n>]",
                "_SSH EXECUTE <command>        run a remote command; its output lines are data",
                "_SSH SET ENCODING = UTF-8    only UTF-8 is accepted",
                "_SSH SET TIMEOUT = <seconds> limit each remote command (default: no limit; 0 = no limit)",
                "_SSH SAVE|RESTORE <name>      attach a name to / switch to a saved session",
                "_SSH DISCONNECT               close the current SSH session",
                "_SSH SFTP GETCWD|CHDIR|LISTDIR|MKDIR|CHMOD|CHOWN|TRUNCATE|PUT|GET|RENAME|REMOVE ...",
                "  without a connection: 'SSH not connected.' / 'SFTP not connected.'"));
        topics.put("SPOOL", List.of(
                "_SPOOL <file>                tee data and status lines to <file>",
                "_SPOOL OFF                   stop the tee ('off' means stop, not a file; there is",
                "                             no bare _SPOOL form - it is an error)",
                "  to tee into a file whose name is really off, write a path: _SPOOL ./off"));
        topics.put("START", List.of(
                "_START <script> [args...]",
                "  runs the child script (relative to the working directory, then to the current script);",
                "  args are visible as ${ARGV1}, ${ARGV2}…; nesting is limited to {depth} levels."));
        topics.put("WHENEVER", List.of(
                "_WHENEVER ERROR CONTINUE     keep going after an error (default)",
                "_WHENEVER ERROR EXIT <n>     stop right away with exit code <n>",
                "  errors only count unless an explicit exit says otherwise: without it the script",
                "  keeps going and the exit code stays 0 (set _WHENEVER or _EXIT to change that)."));
        return Map.copyOf(topics);
    }
}
