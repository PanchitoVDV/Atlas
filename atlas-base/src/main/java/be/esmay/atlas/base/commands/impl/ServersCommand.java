package be.esmay.atlas.base.commands.impl;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.commands.AtlasCommand;
import be.esmay.atlas.base.provider.ServiceProvider;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.server.ServerManager;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.models.ServerInfo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ServersCommand implements AtlasCommand {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ServerManager serverManager;

    private final Map<String, String> activeWatches = new ConcurrentHashMap<>();

    public ServersCommand() {
        this.serverManager = AtlasBase.getInstance().getServerManager();
    }

    @Override
    public String getName() {
        return "servers";
    }

    @Override
    public List<String> getAliases() {
        return List.of("server", "srv");
    }

    @Override
    public String getDescription() {
        return "Manage and view information about servers across all groups.";
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            this.showHelp();
            return;
        }

        String subcommand = args[0].toLowerCase();
        switch (subcommand) {
            case "list" -> this.handleList(args);
            case "status" -> this.handleStatus(args);
            case "create" -> this.handleCreate(args);
            case "remove" -> this.handleRemove(args);
            case "logs" -> this.handleLogs(args);
            case "watch" -> this.handleToggleWatch(args);
            case "start" -> this.handleStart(args);
            case "stop" -> this.handleStop(args);
            case "restart" -> this.handleRestart(args);
            case "help" -> this.showHelp();
            default -> {
                Logger.error("Unknown subcommand: " + subcommand);
                this.showHelp();
            }
        }
    }

    @Override
    public String getUsage() {
        return "servers <subcommand> [args]";
    }

    private void showHelp() {
        Logger.info("Usage: servers <subcommand> [args]");
        Logger.info("");
        Logger.info("Subcommands:");
        Logger.info("  list [group]                List servers (all or by group)");
        Logger.info("  status <server-id|name>     Show detailed server information");
        Logger.info("  create <group> [count]      Create manual servers");
        Logger.info("  remove <server-id|name>     Remove a specific server");
        Logger.info("  logs <server-id|name> [n]   Show last n lines of server logs");
        Logger.info("  watch <server-id|name>      Toggle real-time log watching for a server");
        Logger.info("  start <server-id|name>      Start a stopped server");
        Logger.info("  stop <server-id|name>       Stop a running server");
        Logger.info("  restart <server-id|name>    Restart a server");
        Logger.info("");
        Logger.info("Examples:");
        Logger.info("  servers list");
        Logger.info("  servers list lobby");
        Logger.info("  servers status lobby-1");
        Logger.info("  servers create lobby 2");
        Logger.info("  servers remove lobby-1");
        Logger.info("  servers logs lobby-1 50");
        Logger.info("  servers watch lobby-1");
        Logger.info("  servers start lobby-1");
        Logger.info("  servers stop lobby-1");
        Logger.info("  servers restart lobby-1");
    }

    private CompletableFuture<Optional<ServerInfo>> findServerByIdOrName(ServiceProvider provider, String identifier) {
        return provider.getServer(identifier).thenCompose(serverOpt -> {
            if (serverOpt.isPresent()) {
                return CompletableFuture.completedFuture(serverOpt);
            }

            return provider.getAllServers()
                    .thenApply(servers -> servers.stream()
                            .filter(server -> server.getName().equals(identifier))
                            .findFirst());
        });
    }

    private void handleList(String[] args) {
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();
        CompletableFuture<List<ServerInfo>> serversFuture;

        if (args.length > 1) {
            String group = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            serversFuture = provider.getServersByGroup(group);
        } else {
            serversFuture = provider.getAllServers();
        }

        AtlasBase.getInstance().runAsync(() -> {
            try {
                List<ServerInfo> servers = serversFuture.get();

                if (servers.isEmpty()) {
                    Logger.info("No servers found.");
                    return;
                }

                Logger.info("Servers (" + servers.size() + " total):");
                Logger.info("");

                String format = "%-15s %-10s %-10s %-8s %-12s %-8s %s";
                Logger.info(String.format(format, "Name", "Group", "Status", "Players", "Type", "Manual", "Address"));
                Logger.info("-".repeat(85));

                for (ServerInfo server : servers) {
                    String players = server.getOnlinePlayers() + "/" + server.getMaxPlayers();
                    String address = server.getAddress() + ":" + server.getPort();
                    String manual = server.isManuallyScaled() ? "Yes" : "No";

                    Logger.info(String.format(format,
                            server.getName(),
                            server.getGroup(),
                            server.getStatus(),
                            players,
                            server.getType(),
                            manual,
                            address
                    ));
                }
            } catch (Exception e) {
                Logger.error("Failed to retrieve servers: " + e.getMessage());
            }
        });
    }

    private void handleStatus(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: servers status <server-id|name>");
            return;
        }

        String identifier = args[1];
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        AtlasBase.getInstance().runAsync(() -> {
            try {
                Optional<ServerInfo> serverOpt = this.findServerByIdOrName(provider, identifier).get();

                if (serverOpt.isEmpty()) {
                    Logger.error("Server not found: " + identifier);
                    return;
                }

                ServerInfo server = serverOpt.get();
                Logger.info("Server Information: " + server.getName());
                Logger.info("");
                Logger.info("  ID: " + server.getServerId());
                Logger.info("  Name: " + server.getName());
                Logger.info("  Group: " + server.getGroup());
                Logger.info("  Status: " + server.getStatus());
                Logger.info("  Type: " + server.getType());
                Logger.info("  Address: " + server.getAddress() + ":" + server.getPort());
                Logger.info("  Players: " + server.getOnlinePlayers() + "/" + server.getMaxPlayers());
                Logger.info("  Manually Scaled: " + (server.isManuallyScaled() ? "Yes" : "No"));
                Logger.info("  Service Provider: " + server.getServiceProviderId());

                LocalDateTime createdAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(server.getCreatedAt()), ZoneId.systemDefault());
                Logger.info("  Created: " + createdAt.format(DATE_FORMATTER));

                LocalDateTime lastHeartbeat = LocalDateTime.ofInstant(Instant.ofEpochMilli(server.getLastHeartbeat()), ZoneId.systemDefault());
                Logger.info("  Last Heartbeat: " + lastHeartbeat.format(DATE_FORMATTER));

                if (server.getOnlinePlayerNames() != null && !server.getOnlinePlayerNames().isEmpty()) {
                    Logger.info("  Online Players: " + String.join(", ", server.getOnlinePlayerNames()));
                }
            } catch (Exception e) {
                Logger.error("Failed to retrieve server status: " + e.getMessage());
            }
        });
    }

    private void handleCreate(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: servers create <group> [count]");
            return;
        }

        String tempGroupName;
        int count = 1;

        if (args.length > 2) {
            try {
                count = Integer.parseInt(args[args.length - 1]);
                if (count <= 0) {
                    Logger.error("Count must be a positive number");
                    return;
                }

                tempGroupName = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));
            } catch (NumberFormatException e) {
                tempGroupName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            }
        } else {
            tempGroupName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        }

        String groupName = tempGroupName;

        Scaler scaler = AtlasBase.getInstance().getScalerManager().getScaler(groupName);
        if (scaler == null) {
            Logger.error("Group not found: " + groupName);
            return;
        }

        Logger.info("Creating " + count + " server(s) in group: " + groupName);

        for (int i = 0; i < count; i++) {
            AtlasBase.getInstance().runAsync(() -> {
                try {
                    scaler.upscale().get();
                    Logger.info("Server created successfully in group: " + groupName);
                } catch (Exception e) {
                    Logger.error("Failed to create server: " + e.getMessage());
                }
            });
        }
    }

    private void handleRemove(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: servers remove <server-id|name>");
            return;
        }

        String identifier = args[1];
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        AtlasBase.getInstance().runAsync(() -> {
            try {
                Optional<ServerInfo> serverOpt = this.findServerByIdOrName(provider, identifier).get();

                if (serverOpt.isEmpty()) {
                    Logger.error("Server not found: " + identifier);
                    return;
                }

                ServerInfo server = serverOpt.get();

                this.serverManager.removeServer(server).exceptionally(throwable -> {
                    Logger.error("Failed to remove server: " + throwable.getMessage());
                    return null;
                });
            } catch (Exception e) {
                Logger.error("Failed to remove server: " + e.getMessage());
            }
        });
    }

    private void handleLogs(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: servers logs <server-id|name> [lines]");
            return;
        }

        String identifier = args[1];
        int lines = 20;

        if (args.length > 2) {
            try {
                lines = Integer.parseInt(args[2]);
                if (lines <= 0) {
                    Logger.error("Lines must be a positive number");
                    return;
                }
            } catch (NumberFormatException e) {
                Logger.error("Invalid line count: " + args[2]);
                return;
            }
        }

        int finalLines = lines;
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        AtlasBase.getInstance().runAsync(() -> {
            try {
                Optional<ServerInfo> serverOpt = this.findServerByIdOrName(provider, identifier).get();

                if (serverOpt.isEmpty()) {
                    Logger.error("Server not found: " + identifier);
                    return;
                }

                ServerInfo server = serverOpt.get();
                List<String> logLines = provider.getServerLogs(server.getServerId(), finalLines).get();

                if (logLines.isEmpty()) {
                    Logger.info("No logs available for server: " + server.getName());
                    return;
                }

                Logger.info("Logs for server: " + server.getName() + " (last " + logLines.size() + " lines)");
                Logger.info("-".repeat(80));

                for (String line : logLines) {
                    Logger.info(line);
                }
            } catch (Exception e) {
                Logger.error("Failed to retrieve server logs: " + e.getMessage());
            }
        });
    }

    private void handleToggleWatch(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: servers watch <server-id|name>");
            return;
        }

        String identifier = args[1];
        String subscriptionId = this.activeWatches.get(identifier);

        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        if (subscriptionId != null) {
            AtlasBase.getInstance().runAsync(() -> {
                try {
                    boolean success = provider.stopLogStream(subscriptionId).get();

                    if (success) {
                        this.activeWatches.remove(identifier);
                        Logger.info("-".repeat(60));
                        Logger.info("STOPPED watching logs for server: " + identifier);
                    } else {
                        Logger.error("Failed to stop watching logs for server: " + identifier);
                    }
                } catch (Exception e) {
                    Logger.error("Failed to stop watching server logs: " + e.getMessage());
                }
            });
        } else {
            AtlasBase.getInstance().runAsync(() -> {
                try {
                    Optional<ServerInfo> serverOpt = this.findServerByIdOrName(provider, identifier).get();

                    if (serverOpt.isEmpty()) {
                        Logger.error("Server not found: " + identifier);
                        return;
                    }

                    ServerInfo server = serverOpt.get();
                    Logger.info("STARTED watching logs for server: " + server.getName());
                    Logger.info("Run 'servers watch " + identifier + "' again to stop watching.");
                    Logger.info("-".repeat(60));

                    String newSubscriptionId = provider.streamServerLogs(server.getServerId(), logLine -> {
                        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                        Logger.info("[" + timestamp + "] [" + server.getName() + "] " + logLine);
                    }).get();

                    this.activeWatches.put(identifier, newSubscriptionId);
                } catch (Exception e) {
                    Logger.error("Failed to start watching server logs: " + e.getMessage());
                }
            });
        }
    }

    private void handleStart(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: servers start <server-id|name>");
            return;
        }

        String identifier = args[1];
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        AtlasBase.getInstance().runAsync(() -> {
            try {
                Optional<ServerInfo> serverOpt = this.findServerByIdOrName(provider, identifier).get();

                if (serverOpt.isEmpty()) {
                    Logger.error("Server not found: " + identifier);
                    return;
                }

                ServerInfo server = serverOpt.get();

                this.serverManager.startServer(server).exceptionally(throwable -> {
                    Logger.error("Failed to start server: " + throwable.getMessage());
                    return null;
                });
            } catch (Exception e) {
                Logger.error("Failed to start server: " + e.getMessage());
            }
        });
    }

    private void handleStop(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: servers stop <server-id|name>");
            return;
        }

        String identifier = args[1];
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        AtlasBase.getInstance().runAsync(() -> {
            try {
                Optional<ServerInfo> serverOpt = this.findServerByIdOrName(provider, identifier).get();

                if (serverOpt.isEmpty()) {
                    Logger.error("Server not found: " + identifier);
                    return;
                }

                ServerInfo server = serverOpt.get();

                this.serverManager.stopServer(server).exceptionally(throwable -> {
                    Logger.error("Failed to stop server: " + throwable.getMessage());
                    return null;
                });
            } catch (Exception e) {
                Logger.error("Failed to stop server: " + e.getMessage());
            }
        });
    }

    private void handleRestart(String[] args) {
        if (args.length < 2) {
            Logger.error("Usage: servers restart <server-id|name>");
            return;
        }

        String identifier = args[1];
        ServiceProvider provider = AtlasBase.getInstance().getProviderManager().getProvider();

        AtlasBase.getInstance().runAsync(() -> {
            try {
                Optional<ServerInfo> serverOpt = this.findServerByIdOrName(provider, identifier).get();

                if (serverOpt.isEmpty()) {
                    Logger.error("Server not found: " + identifier);
                    return;
                }

                ServerInfo server = serverOpt.get();

                this.serverManager.restartServer(server).exceptionally(throwable -> {
                    Logger.error("Failed to restart server: " + throwable.getMessage());
                    return null;
                });
            } catch (Exception e) {
                Logger.error("Failed to restart server: " + e.getMessage());
            }
        });
    }
}