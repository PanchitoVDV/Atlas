package be.esmay.atlas.base.utils;

import lombok.experimental.UtilityClass;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public final class Logger {

    private static boolean DEBUG_MODE = false;
    private static Terminal TERMINAL = null;
    private static LineReader LINE_READER = null;
    private static FileWriter LOG_FILE_WRITER = null;
    
    private static final ExecutorService LOG_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Atlas-Logger");
        t.setDaemon(true);
        return t;
    });
    
    private static final BlockingQueue<LogEntry> LOG_QUEUE = new ArrayBlockingQueue<>(1000);
    private static volatile boolean LOGGING_ACTIVE = true;
    
    private record LogEntry(String icon, String color, String message, Throwable throwable) {}

    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String PURPLE = "\u001B[35m";

    private static final String BRIGHT_RED = "\u001B[91m";
    private static final String BRIGHT_CYAN = "\u001B[96m";
    private static final String BRIGHT_WHITE = "\u001B[97m";

    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter LOG_FILE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final String[] GRADIENT_COLORS = {
            "\u001B[38;2;0;130;255m",
            "\u001B[38;2;0;150;230m",
            "\u001B[38;2;0;170;210m",
            "\u001B[38;2;0;190;190m",
            "\u001B[38;2;0;210;170m",
            "\u001B[38;2;0;230;150m",
            "\u001B[38;2;0;255;130m"
    };

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final boolean IS_LINUX = System.getProperty("os.name").toLowerCase().contains("linux");
    private static final String VERSION = getProjectVersion();

    static {
        rotateLogFile();
        initializeLogFile();
        startAsyncLogging();
    }
    
    private static void startAsyncLogging() {
        LOG_EXECUTOR.submit(() -> {
            while (LOGGING_ACTIVE || !LOG_QUEUE.isEmpty()) {
                try {
                    LogEntry entry = LOG_QUEUE.poll(1, TimeUnit.SECONDS);
                    if (entry != null) {
                        processLogEntry(entry);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("Error in async logging: " + e.getMessage());
                }
            }
        });
    }

    private static void rotateLogFile() {
        try {
            Path currentLogFile = Paths.get("log.log");
            Path logsDir = Paths.get("logs");

            if (!Files.exists(logsDir)) {
                Files.createDirectories(logsDir);
            }

            if (Files.exists(currentLogFile)) {
                String timestamp = LocalDateTime.now().format(LOG_FILE_DATE_FORMAT);
                Path rotatedFile = logsDir.resolve("atlas-" + timestamp + ".log");
                
                try {
                    Files.move(currentLogFile, rotatedFile);
                } catch (IOException e) {
                    System.err.println("Failed to rotate log file: " + e.getMessage());
                }
            }

            cleanupOldLogFiles(logsDir);
        } catch (IOException e) {
            System.err.println("Failed to setup log rotation: " + e.getMessage());
        }
    }
    
    private static void cleanupOldLogFiles(Path logsDir) {
        try {
            if (!Files.exists(logsDir)) {
                return;
            }
            
            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(30);
            
            Files.list(logsDir)
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith("atlas-"))
                .filter(path -> path.getFileName().toString().endsWith(".log"))
                .forEach(path -> {
                    try {
                        LocalDateTime fileTime = Files.getLastModifiedTime(path).toInstant()
                                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
                        
                        if (fileTime.isBefore(cutoffDate)) {
                            Files.delete(path);
                            System.out.println("Deleted old log file: " + path.getFileName());
                        }
                    } catch (IOException e) {
                        System.err.println("Failed to check/delete log file " + path.getFileName() + ": " + e.getMessage());
                    }
                });
                
        } catch (IOException e) {
            System.err.println("Failed to cleanup old log files: " + e.getMessage());
        }
    }

    private static void initializeLogFile() {
        try {
            LOG_FILE_WRITER = new FileWriter("log.log", true);
        } catch (IOException e) {
            System.err.println("Failed to initialize log file: " + e.getMessage());
        }
    }

    private static String getProjectVersion() {
        try {
            Path currentPath = Paths.get("").toAbsolutePath();
            Path buildFile = findBuildFile(currentPath);

            if (buildFile != null) {
                String content = Files.readString(buildFile);
                Pattern pattern = Pattern.compile("version\\s*=\\s*\"([^\"]+)\"");
                Matcher matcher = pattern.matcher(content);

                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to read version from build.gradle.kts: " + e.getMessage());
        }

        return "1.0.0";
    }

    private static Path findBuildFile(Path startPath) {
        Path buildFile = startPath.resolve("build.gradle.kts");
        if (Files.exists(buildFile)) {
            return buildFile;
        }

        if (startPath.getParent() != null) {
            buildFile = startPath.getParent().resolve("build.gradle.kts");
            if (Files.exists(buildFile)) {
                return buildFile;
            }
        }

        return null;
    }

    private static String getLogIcon(String type) {
        if (IS_WINDOWS || IS_LINUX) {
            return switch (type) {
                case "info" -> "i ";
                case "success" -> "+ ";
                case "warn" -> "! ";
                case "error" -> "x ";
                case "debug" -> ". ";
                default -> "> ";
            };
        }

        return switch (type) {
            case "info" -> "ℹ ";
            case "success" -> "✔ ";
            case "warn" -> "⚠ ";
            case "error" -> "✖ ";
            case "debug" -> "· ";
            default -> "• ";
        };
    }

    public static void printBanner() {
        System.out.println();

        if (IS_WINDOWS) {
            String[] bannerLines = {
                    "    Atlas Scaler v" + VERSION,
                    "    -------------------",
                    "    Java: " + System.getProperty("java.version"),
                    "    OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"),
                    "    Memory: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB"
            };

            for (String line : bannerLines) {
                System.out.println(line);
            }

            System.out.println();
            return;
        }

        String[] bannerLines = {
                "        █████╗ ████████╗██╗      █████╗ ███████╗",
                "       ██╔══██╗╚══██╔══╝██║     ██╔══██╗██╔════╝",
                "       ███████║   ██║   ██║     ███████║███████╗",
                "       ██╔══██║   ██║   ██║     ██╔══██║╚════██║",
                "       ██║  ██║   ██║   ███████╗██║  ██║███████║",
                "       ╚═╝  ╚═╝   ╚═╝   ╚══════╝╚═╝  ╚═╝╚══════╝"
        };

        for (String bannerLine : bannerLines) {
            printGradientLine(bannerLine, BOLD);
        }

        System.out.println();

        String javaVersion = System.getProperty("java.version");
        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        Runtime runtime = Runtime.getRuntime();
        long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);

        int boxWidth = 54;

        String titleLine = String.format("  %s%s%s v%s", BRIGHT_CYAN, BOLD + "Atlas Scaler" + RESET, RESET + DIM, VERSION);
        String javaLine = String.format("  • Java: %s%s%s", BRIGHT_WHITE, javaVersion, RESET + DIM);
        String osLine = String.format("  • OS: %s%s %s%s", BRIGHT_WHITE, osName, osVersion, RESET + DIM);
        String memoryLine = String.format("  • Memory: %s%d MB%s", BRIGHT_WHITE, maxMemoryMB, RESET + DIM);

        String horizontalLine = "─".repeat(boxWidth - 2);
        System.out.println(DIM + "┌" + horizontalLine + "┐" + RESET);
        System.out.println(DIM + "│" + RESET + padRight(titleLine, boxWidth - 2) + DIM + "│" + RESET);
        System.out.println(DIM + "├" + horizontalLine + "┤" + RESET);
        System.out.println(DIM + "│" + RESET + padRight(javaLine, boxWidth - 2) + DIM + "│" + RESET);
        System.out.println(DIM + "│" + RESET + padRight(osLine, boxWidth - 2) + DIM + "│" + RESET);
        System.out.println(DIM + "│" + RESET + padRight(memoryLine, boxWidth - 2) + DIM + "│" + RESET);
        System.out.println(DIM + "└" + horizontalLine + "┘" + RESET);
        System.out.println();
    }

    private static void printGradientLine(String text, String... styles) {
        StringBuilder sb = new StringBuilder();
        int colorCount = GRADIENT_COLORS.length;
        int textLength = text.length();

        for (int i = 0; i < textLength; i++) {
            int colorIndex = Math.min((i * colorCount) / textLength, colorCount - 1);
            sb.append(GRADIENT_COLORS[colorIndex]);

            for (String style : styles) {
                sb.append(style);
            }

            sb.append(text.charAt(i));
            sb.append(RESET);
        }

        System.out.println(sb);
    }

    public static void info(String message, Object... args) {
        log(getLogIcon("info"), BLUE, format(message, args));
    }

    public static void info(String message, Throwable t) {
        log(getLogIcon("info"), BLUE, message, t);
    }

    public static void success(String message, Object... args) {
        log(getLogIcon("success"), GREEN, format(message, args));
    }

    public static void success(String message, Throwable t) {
        log(getLogIcon("success"), GREEN, message, t);
    }

    public static void warn(String message, Object... args) {
        log(getLogIcon("warn"), YELLOW, format(message, args));
    }

    public static void warn(String message, Throwable t) {
        log(getLogIcon("warn"), YELLOW, message, t);
    }

    public static void error(String message, Object... args) {
        log(getLogIcon("error"), RED, format(message, args));
    }

    public static void error(String message, Throwable t) {
        log(getLogIcon("error"), RED, message, t);
    }

    public static void error(String message, Throwable t, Object... args) {
        log(getLogIcon("error"), RED, format(message, args), t);
    }

    public static void debug(String message, Object... args) {
        if (DEBUG_MODE) {
            log(getLogIcon("debug"), PURPLE, format(message, args));
        }
    }

    public static void debug(String message, Throwable t) {
        if (DEBUG_MODE) {
            log(getLogIcon("debug"), PURPLE, message, t);
        }
    }

    private static void writeToFile(String level, String message, Throwable t) {
        if (LOG_FILE_WRITER != null) {
            try {
                String timestamp = LocalDateTime.now().format(FILE_TIME_FORMAT);
                String logLine = String.format("%s [%s] %s - %s%n", timestamp, Thread.currentThread().getName(), level, message);
                LOG_FILE_WRITER.write(logLine);
                
                if (t != null) {
                    PrintWriter pw = new PrintWriter(LOG_FILE_WRITER);
                    t.printStackTrace(pw);
                    pw.flush();
                }
                
                LOG_FILE_WRITER.flush();
            } catch (IOException e) {
                // Don't use Logger here to avoid infinite recursion
                System.err.println("Failed to write to log file: " + e.getMessage());
            }
        }
    }

    private static void log(String icon, String color, String message) {
        LogEntry entry = new LogEntry(icon, color, message, null);
        if (!LOG_QUEUE.offer(entry)) {
            processLogEntry(entry);
        }
    }

    private static String getLogLevel(String color) {
        return switch (color) {
            case BLUE -> "INFO";
            case GREEN -> "SUCCESS";
            case YELLOW -> "WARN";
            case RED -> "ERROR";
            case PURPLE -> "DEBUG";
            default -> "INFO";
        };
    }

    private static void log(String icon, String color, String message, Throwable t) {
        LogEntry entry = new LogEntry(icon, color, message, t);
        if (!LOG_QUEUE.offer(entry)) {
            processLogEntry(entry);
        }
    }
    
    private static void processLogEntry(LogEntry entry) {
        String logLine;
        String timestamp = LocalTime.now().format(TIME_FORMAT);
        if (IS_WINDOWS) {
            logLine = String.format("[%s] %s %s", timestamp, "[INFO]", entry.message);
        } else {
            logLine = String.format(
                    "%s%s%s %s%s%s %s%s%s",
                    DIM, timestamp, RESET,
                    BOLD, entry.color, entry.icon, RESET,
                    BRIGHT_WHITE, entry.message
            );
        }

        synchronized (Logger.class) {
            if (LINE_READER != null) {
                LINE_READER.printAbove(logLine);

                if (entry.throwable != null) {
                    if (IS_WINDOWS) {
                        LINE_READER.printAbove("    Exception: " + entry.throwable.getClass().getSimpleName() + ": " + entry.throwable.getMessage());
                    } else {
                        LINE_READER.printAbove(DIM + "  ┌─ " + entry.throwable.getClass().getSimpleName() + ": " +
                                BRIGHT_RED + entry.throwable.getMessage() + RESET);

                        StackTraceElement[] elements = entry.throwable.getStackTrace();
                        int linesToShow = Math.min(elements.length, 3);

                        for (int i = 0; i < linesToShow; i++) {
                            StackTraceElement element = elements[i];
                            String className = element.getClassName();
                            String methodName = element.getMethodName();
                            int lineNumber = element.getLineNumber();

                            String shortClassName = className.substring(className.lastIndexOf('.') + 1);

                            LINE_READER.printAbove(DIM + "  │  at " + shortClassName + "." +
                                    methodName + "(" + lineNumber + ")" + RESET);
                        }

                        if (elements.length > linesToShow) {
                            LINE_READER.printAbove(DIM + "  └─ ... " + (elements.length - linesToShow) +
                                    " more" + RESET);
                        } else {
                            LINE_READER.printAbove(DIM + "  └─" + RESET);
                        }
                    }
                }
            } else if (TERMINAL != null) {
                TERMINAL.writer().println(logLine);
                if (entry.throwable != null) {
                    if (IS_WINDOWS) {
                        TERMINAL.writer().println("    Exception: " + entry.throwable.getClass().getSimpleName() + ": " + entry.throwable.getMessage());
                    } else {
                        TERMINAL.writer().println(DIM + "  ┌─ " + entry.throwable.getClass().getSimpleName() + ": " +
                                BRIGHT_RED + entry.throwable.getMessage() + RESET);

                        StackTraceElement[] elements = entry.throwable.getStackTrace();
                        int linesToShow = Math.min(elements.length, 3);

                        for (int i = 0; i < linesToShow; i++) {
                            StackTraceElement element = elements[i];
                            String className = element.getClassName();
                            String methodName = element.getMethodName();
                            int lineNumber = element.getLineNumber();

                            String shortClassName = className.substring(className.lastIndexOf('.') + 1);

                            TERMINAL.writer().println(DIM + "  │  at " + shortClassName + "." +
                                    methodName + "(" + lineNumber + ")" + RESET);
                        }

                        if (elements.length > linesToShow) {
                            TERMINAL.writer().println(DIM + "  └─ ... " + (elements.length - linesToShow) +
                                    " more" + RESET);
                        } else {
                            TERMINAL.writer().println(DIM + "  └─" + RESET);
                        }
                    }
                }
                TERMINAL.writer().flush();
            } else {
                System.out.println(logLine);
                if (entry.throwable != null) {
                    if (IS_WINDOWS) {
                        System.out.println("    Exception: " + entry.throwable.getClass().getSimpleName() + ": " + entry.throwable.getMessage());
                    } else {
                        System.out.println(DIM + "  ┌─ " + entry.throwable.getClass().getSimpleName() + ": " +
                                BRIGHT_RED + entry.throwable.getMessage() + RESET);

                        StackTraceElement[] elements = entry.throwable.getStackTrace();
                        int linesToShow = Math.min(elements.length, 3);

                        for (int i = 0; i < linesToShow; i++) {
                            StackTraceElement element = elements[i];
                            String className = element.getClassName();
                            String methodName = element.getMethodName();
                            int lineNumber = element.getLineNumber();

                            String shortClassName = className.substring(className.lastIndexOf('.') + 1);

                            System.out.println(DIM + "  │  at " + shortClassName + "." +
                                    methodName + "(" + lineNumber + ")" + RESET);
                        }

                        if (elements.length > linesToShow) {
                            System.out.println(DIM + "  └─ ... " + (elements.length - linesToShow) +
                                    " more" + RESET);
                        } else {
                            System.out.println(DIM + "  └─" + RESET);
                        }
                    }
                }
            }
        }

        String level = getLogLevel(entry.color);
        writeToFile(level, entry.message, entry.throwable);
    }

    private static String format(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }

        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int last = 0;
        int idx;

        while ((idx = message.indexOf("{}", last)) != -1 && argIndex < args.length) {
            sb.append(message, last, idx);
            sb.append(args[argIndex] != null ? args[argIndex].toString() : "null");
            last = idx + 2;
            argIndex++;
        }

        sb.append(message.substring(last));

        if (argIndex < args.length) {
            sb.append(" [");
            for (int i = argIndex; i < args.length; i++) {
                if (i > argIndex) sb.append(", ");
                sb.append(args[i] != null ? args[i].toString() : "null");
            }
            sb.append("]");
        }
        return sb.toString();
    }

    private static String padRight(String s, int length) {
        int visibleLength = s.replaceAll("\u001B\\[[;\\d]*m", "").length();
        if (visibleLength >= length) return s;

        return s + " ".repeat(length - visibleLength);
    }

    public static void setDebugMode(boolean debugMode) {
        DEBUG_MODE = debugMode;
    }

    public static void setTerminal(Terminal terminal) {
        TERMINAL = terminal;
    }

    public static void setLineReader(LineReader lineReader) {
        LINE_READER = lineReader;
    }

    public static void closeLogFile() {
        LOGGING_ACTIVE = false;
        LOG_EXECUTOR.shutdown();
        try {
            if (!LOG_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOG_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        if (LOG_FILE_WRITER != null) {
            try {
                LOG_FILE_WRITER.close();
            } catch (IOException e) {
                System.err.println("Failed to close log file: " + e.getMessage());
            }
        }
        
        rotateLogFile();
    }
}
