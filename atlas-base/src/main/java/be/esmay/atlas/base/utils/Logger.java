package be.esmay.atlas.base.utils;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Logger {

    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String PURPLE = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";
    private static final String WHITE = "\u001B[37m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void printBanner() {
        System.out.println();
        System.out.println(
                CYAN + BOLD +
                        "     ██████╗ ████████╗██╗      █████╗ ███████╗" + RESET
        );
        System.out.println(
                CYAN + BOLD +
                        "    ██╔══██╗╚══██╔══╝██║     ██╔══██╗██╔════╝" + RESET
        );
        System.out.println(
                CYAN + BOLD +
                        "    ███████║   ██║   ██║     ███████║███████╗" + RESET
        );
        System.out.println(
                CYAN + BOLD +
                        "    ██╔══██║   ██║   ██║     ██╔══██║╚════██║" + RESET
        );
        System.out.println(
                CYAN + BOLD +
                        "    ██║  ██║   ██║   ███████╗██║  ██║███████║" + RESET
        );
        System.out.println(
                CYAN + BOLD +
                        "    ╚═╝  ╚═╝   ╚═╝   ╚══════╝╚═╝  ╚═╝╚══════╝" + RESET
        );
        System.out.println();
        System.out.println(
                DIM + "           Minecraft Server Scaler v1.0.0" + RESET
        );
        System.out.println();
    }

    public static void info(String message, Object... args) {
        log("ℹ", BLUE, format(message, args));
    }

    public static void info(String message, Throwable t) {
        log("ℹ", BLUE, message, t);
    }

    public static void success(String message, Object... args) {
        log("✓", GREEN, format(message, args));
    }

    public static void success(String message, Throwable t) {
        log("✓", GREEN, message, t);
    }

    public static void warn(String message, Object... args) {
        log("⚠", YELLOW, format(message, args));
    }

    public static void warn(String message, Throwable t) {
        log("⚠", YELLOW, message, t);
    }

    public static void error(String message, Object... args) {
        log("✗", RED, format(message, args));
    }

    public static void error(String message, Throwable t) {
        log("✗", RED, message, t);
    }

    public static void debug(String message, Object... args) {
        log("◦", PURPLE, format(message, args));
    }

    public static void debug(String message, Throwable t) {
        log("◦", PURPLE, message, t);
    }

    private static void log(String icon, String color, String message) {
        String timestamp = LocalTime.now().format(TIME_FORMAT);
        System.out.printf(
                "%s%s%s %s[%s]%s %s%n",
                DIM, timestamp, RESET,
                color, icon, RESET,
                message
        );
    }

    private static void log(String icon, String color, String message, Throwable t) {
        log(icon, color, message);
        if (t != null) {
            System.out.println(DIM + "    " + t.getClass().getSimpleName() +
                    ": " + t.getMessage() + RESET);
        }
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
}