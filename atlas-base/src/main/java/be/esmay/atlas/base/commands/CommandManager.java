package be.esmay.atlas.base.commands;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.commands.impl.StopCommand;
import be.esmay.atlas.base.utils.Logger;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public final class CommandManager {

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String BRIGHT_CYAN = "\u001B[96m";
    private static final String BRIGHT_WHITE = "\u001B[97m";
    private static final String DIM = "\u001B[2m";
    private static final String BOLD = "\u001B[1m";

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name").toLowerCase().contains("win");

    private final AtlasBase atlasBase;
    private final Map<String, AtlasCommand> commands = new ConcurrentHashMap<>();
    private final Map<String, AtlasCommand> primaryCommands = new LinkedHashMap<>();
    private volatile boolean running = false;
    private Thread commandThread;
    private Scanner scanner;

    public void initialize() {
        Logger.info("Enabling command manager...");

        this.registerCommand(new StopCommand(this.atlasBase));

        this.running = true;
        this.scanner = new Scanner(System.in);
        this.commandThread = new Thread(this::handleInput, "Atlas-Commands");
        this.commandThread.setDaemon(true);
        this.commandThread.start();
    }

    public void shutdown() {
        this.running = false;

        if (this.scanner != null) {
            this.scanner.close();
        }

        if (this.commandThread != null && !this.commandThread.isInterrupted()) {
            this.commandThread.interrupt();
        }

        Logger.info("Command manager shutdown complete");
    }

    public void registerCommand(AtlasCommand command) {
        this.primaryCommands.put(command.getName(), command);
        this.commands.put(command.getName().toLowerCase(), command);

        for (String alias : command.getAliases()) {
            this.commands.put(alias.toLowerCase(), command);
        }

        Logger.success("Registered command: {}", command.getName());
    }

    private void handleInput() {
        while (this.running && !Thread.currentThread().isInterrupted()) {
            try {
                this.printPrompt();

                if (!this.scanner.hasNextLine())
                    break;

                String input = this.scanner.nextLine().trim();

                if (input.isEmpty())
                    continue;

                this.processCommand(input);
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted())
                    Logger.error("Command handling error", e);

                break;
            }
        }
    }

    private void printPrompt() {
        if (IS_WINDOWS) {
            System.out.print("Atlas> ");
            return;
        }

        System.out.print(BRIGHT_CYAN + BOLD + "Atlas" + RESET + DIM + " › " + RESET);
    }

    private void processCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String commandName = parts[0].toLowerCase();
        String[] args = parts.length > 1 ? parts[1].split("\\s+") : new String[0];

        if ("help".equals(commandName) || "?".equals(commandName)) {
            this.showHelp(args);
            return;
        }

        AtlasCommand command = this.commands.get(commandName);
        if (command == null) {
            this.showCommandNotFound(commandName);
            return;
        }

        try {
            command.execute(args);
        } catch (Exception e) {
            Logger.error("Command '{}' execution failed", e, commandName);
        }
    }

    private void showHelp(String[] args) {
        if (args.length > 0) {
            this.showCommandHelp(args[0]);
            return;
        }

        if (IS_WINDOWS) {
            System.out.println();
            System.out.println("Available Commands:");
            System.out.println("==================");

            this.primaryCommands.values().forEach(cmd -> {
                System.out.printf("  %-12s - %s%n", cmd.getName(), cmd.getDescription());
                if (!cmd.getAliases().isEmpty()) {
                    System.out.printf("               Aliases: %s%n",
                            String.join(", ", cmd.getAliases()));
                }
            });

            System.out.println();
            System.out.println("Type 'help <command>' for detailed information about a specific command.");
            System.out.println();
            return;
        }

        System.out.println();

        String title = BRIGHT_CYAN + BOLD + "Available Commands" + RESET;
        String separator = DIM + "─".repeat(50) + RESET;

        System.out.println("  " + title);
        System.out.println("  " + separator);
        System.out.println();

        this.primaryCommands.values().forEach(cmd -> {
            String cmdName = BRIGHT_WHITE + BOLD + cmd.getName() + RESET;
            String desc = DIM + cmd.getDescription() + RESET;

            System.out.printf("  %s%s %s%n",
                    GREEN + "▸ " + RESET, cmdName, desc);

            if (!cmd.getAliases().isEmpty()) {
                String aliases = cmd.getAliases().stream()
                        .collect(Collectors.joining(DIM + ", " + RESET + YELLOW, YELLOW, RESET));
                System.out.printf("    %sAliases: %s%n", DIM, aliases);
            }

            System.out.println();
        });

        System.out.println(DIM + "  Type " + BRIGHT_CYAN + "help <command>" + RESET +
                DIM + " for detailed information" + RESET);
        System.out.println();
    }

    private void showCommandHelp(String commandName) {
        AtlasCommand command = this.commands.get(commandName.toLowerCase());

        if (command == null) {
            this.showCommandNotFound(commandName);
            return;
        }

        if (IS_WINDOWS) {
            System.out.println();
            System.out.println("Command: " + command.getName());
            System.out.println("Description: " + command.getDescription());

            if (!command.getAliases().isEmpty()) {
                System.out.println("Aliases: " + String.join(", ", command.getAliases()));
            }

            System.out.println("Usage: " + command.getUsage());
            System.out.println();
            return;
        }

        System.out.println();

        String cmdName = BRIGHT_WHITE + BOLD + command.getName() + RESET;
        System.out.println("  " + GREEN + "▸ " + RESET + "Command: " + cmdName);
        System.out.println("  " + DIM + "  Description: " + command.getDescription() + RESET);

        if (!command.getAliases().isEmpty()) {
            String aliases = command.getAliases().stream()
                    .collect(Collectors.joining(DIM + ", " + RESET + YELLOW, YELLOW, RESET));
            System.out.println("  " + DIM + "  Aliases: " + aliases);
        }

        System.out.println("  " + DIM + "  Usage: " + BRIGHT_CYAN +
                command.getUsage() + RESET);
        System.out.println();
    }

    private void showCommandNotFound(String commandName) {
        if (IS_WINDOWS) {
            System.out.println("Unknown command: " + commandName +
                    ". Type 'help' for available commands.");
            return;
        }

        List<String> suggestions = this.findSimilarCommands(commandName);

        String message = "Unknown command: " + RED + commandName + RESET;
        System.out.println("  " + RED + "✖ " + RESET + message);

        if (!suggestions.isEmpty()) {
            String suggestionText = suggestions.stream()
                    .collect(Collectors.joining(RESET + DIM + ", " + BRIGHT_CYAN,
                            BRIGHT_CYAN, RESET));
            System.out.println("  " + DIM + "  Did you mean: " + suggestionText + RESET);
        }

        System.out.println("  " + DIM + "  Type " + BRIGHT_CYAN + "help" + RESET +
                DIM + " for available commands" + RESET);
        System.out.println();
    }

    private List<String> findSimilarCommands(String input) {
        return this.primaryCommands.keySet().stream()
                .filter(cmd -> cmd.toLowerCase().startsWith(input.toLowerCase()) ||
                        levenshteinDistance(cmd.toLowerCase(), input.toLowerCase()) <= 2)
                .limit(3)
                .collect(Collectors.toList());
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(Math.min(dp[i - 1][j], dp[i][j - 1]),
                            dp[i - 1][j - 1]);
                }
            }
        }

        return dp[a.length()][b.length()];
    }

    public Set<String> getRegisteredCommands() {
        return Collections.unmodifiableSet(this.primaryCommands.keySet());
    }

    public int getCommandCount() {
        return this.primaryCommands.size();
    }
}