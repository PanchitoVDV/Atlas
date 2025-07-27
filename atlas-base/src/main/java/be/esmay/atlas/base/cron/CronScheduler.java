package be.esmay.atlas.base.cron;

import be.esmay.atlas.base.AtlasBase;
import be.esmay.atlas.base.backup.BackupManager;
import be.esmay.atlas.base.config.impl.ScalerConfig;
import be.esmay.atlas.base.scaler.Scaler;
import be.esmay.atlas.base.server.ServerManager;
import be.esmay.atlas.base.utils.Logger;
import be.esmay.atlas.common.models.AtlasServer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Getter
public final class CronScheduler {

    private final AtlasBase atlasBase;
    private final BackupManager backupManager;
    private ScheduledExecutorService executor;
    private final Map<String, ScheduledFuture<?>> scheduledJobs = new ConcurrentHashMap<>();
    private volatile boolean isShuttingDown = false;

    public CronScheduler(AtlasBase atlasBase) {
        this.atlasBase = atlasBase;
        this.backupManager = new BackupManager();
    }

    public void initialize() {
        this.executor = new ScheduledThreadPoolExecutor(2, r -> {
            Thread thread = new Thread(r, "Atlas-CronScheduler");
            thread.setDaemon(true);
            return thread;
        });

        Logger.info("CronScheduler initialized");
        this.scheduleAllCronJobs();
    }

    public void scheduleAllCronJobs() {
        for (Scaler scaler : this.atlasBase.getScalerManager().getScalers()) {
            this.scheduleCronJobsForScaler(scaler);
        }
    }

    public void scheduleCronJobsForScaler(Scaler scaler) {
        ScalerConfig.Group group = scaler.getScalerConfig().getGroup();
        List<ScalerConfig.CronJob> cronJobs = group.getCronJobs();

        if (cronJobs == null || cronJobs.isEmpty()) {
            return;
        }

        String groupName = scaler.getGroupName();
        Logger.info("Scheduling {} cron jobs for group: {}", cronJobs.size(), groupName);

        for (ScalerConfig.CronJob cronJob : cronJobs) {
            if (!cronJob.isEnabled()) {
                Logger.debug("Skipping disabled cron job: {} in group: {}", cronJob.getName(), groupName);
                continue;
            }

            this.scheduleCronJob(groupName, cronJob);
        }
    }

    private void scheduleCronJob(String groupName, ScalerConfig.CronJob cronJob) {
        String jobKey = groupName + ":" + cronJob.getName();

        try {
            CronExpression cronExpression = new CronExpression(cronJob.getSchedule());
            long nextExecutionTime = cronExpression.getNextExecutionTime();
            long currentTime = System.currentTimeMillis();
            long delay = nextExecutionTime - currentTime;

            if (delay < 0) {
                Logger.warn("Cron job {} has invalid schedule, skipping", jobKey);
                return;
            }

            ScheduledFuture<?> scheduledFuture = this.executor.schedule(() -> {
                this.executeCronJob(groupName, cronJob);
                this.rescheduleCronJob(groupName, cronJob);
            }, delay, TimeUnit.MILLISECONDS);

            this.scheduledJobs.put(jobKey, scheduledFuture);

            LocalDateTime nextDateTime = LocalDateTime.now().plusSeconds(delay / 1000);
            String nextExecution = nextDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            Logger.info("Scheduled cron job: {} for group: {} - Next execution: {}", 
                    cronJob.getName(), groupName, nextExecution);

        } catch (Exception e) {
            Logger.error("Failed to schedule cron job: {} for group: {}", cronJob.getName(), groupName, e);
        }
    }

    private void rescheduleCronJob(String groupName, ScalerConfig.CronJob cronJob) {
        if (this.isShuttingDown) {
            return;
        }

        this.scheduleCronJob(groupName, cronJob);
    }

    private void executeCronJob(String groupName, ScalerConfig.CronJob cronJob) {
        if (this.isShuttingDown) {
            return;
        }

        try {
            Logger.info("Executing cron job: {} for group: {}", cronJob.getName(), groupName);

            List<ScalerConfig.CronStep> steps = cronJob.getSteps();
            if (steps == null || steps.isEmpty()) {
                Logger.warn("No steps specified for cron job: {} in group: {}", cronJob.getName(), groupName);
                return;
            }

            this.executeSteps(groupName, cronJob, steps, 0);

        } catch (Exception e) {
            Logger.error("Error executing cron job: {} for group: {}", cronJob.getName(), groupName, e);
        }
    }

    private void executeSteps(String groupName, ScalerConfig.CronJob cronJob, List<ScalerConfig.CronStep> steps, int stepIndex) {
        if (stepIndex >= steps.size()) {
            Logger.info("Completed all steps for cron job: {} in group: {}", cronJob.getName(), groupName);
            return;
        }

        ScalerConfig.CronStep currentStep = steps.get(stepIndex);
        int delay = currentStep.getDelay();

        if (delay > 0) {
            Logger.debug("Scheduling step {} of cron job '{}' with delay of {} seconds", 
                    stepIndex + 1, cronJob.getName(), delay);
            
            this.executor.schedule(() -> {
                this.executeStep(groupName, cronJob, currentStep, stepIndex + 1);
                this.executeSteps(groupName, cronJob, steps, stepIndex + 1);
            }, delay, TimeUnit.SECONDS);
        } else {
            this.executeStep(groupName, cronJob, currentStep, stepIndex + 1);
            this.executeSteps(groupName, cronJob, steps, stepIndex + 1);
        }
    }

    private void executeStep(String groupName, ScalerConfig.CronJob cronJob, ScalerConfig.CronStep step, int stepNumber) {
        String actionType = step.getActionType();
        if (actionType == null) {
            Logger.warn("No action type specified for step {} in cron job: {} in group: {}", 
                    stepNumber, cronJob.getName(), groupName);
            return;
        }

        Logger.debug("Executing step {} ({}) for cron job: {} in group: {}", 
                stepNumber, actionType, cronJob.getName(), groupName);

        switch (actionType.toLowerCase()) {
            case "server-control" -> this.executeServerControlAction(groupName, cronJob, step);
            case "server-command" -> this.executeServerCommandAction(groupName, cronJob, step);
            case "backup" -> this.executeBackupAction(groupName, cronJob, step);
            default -> Logger.warn("Unknown action type: {} for step {} in cron job: {} in group: {}", 
                    actionType, stepNumber, cronJob.getName(), groupName);
        }
    }

    private void executeServerControlAction(String groupName, ScalerConfig.CronJob cronJob, ScalerConfig.CronStep step) {
        ScalerConfig.ServerControlAction serverControl = step.getServerControl();
        if (serverControl == null || serverControl.getAction() == null) {
            Logger.warn("No server control action specified for cron job: {} in group: {}", 
                    cronJob.getName(), groupName);
            return;
        }

        String action = serverControl.getAction().toLowerCase();
        String target = cronJob.getTarget();

        if (target.equals("servers")) {
            CompletableFuture<List<AtlasServer>> serversFuture = this.atlasBase.getProviderManager().getProvider().getServersByGroup(groupName);
            serversFuture.thenAccept(servers -> {
                Logger.debug("Found {} servers in group '{}' for action '{}'", servers.size(), groupName, action);
                for (AtlasServer server : servers) {
                    Logger.debug("Server found: {} (ID: {}, Status: {})", 
                            server.getName(), server.getServerId(), 
                            server.getServerInfo() != null ? server.getServerInfo().getStatus() : "NO_INFO");
                }
                
                if (servers.isEmpty()) {
                    Logger.warn("No servers found in group '{}' for cron job '{}'", groupName, cronJob.getName());
                    return;
                }
                
                for (AtlasServer server : servers) {
                    try {
                        switch (action) {
                            case "start" -> this.atlasBase.getServerManager().startServer(server);
                            case "stop" -> this.atlasBase.getServerManager().stopServer(server);
                            case "restart" -> this.atlasBase.getServerManager().restartServer(server);
                            default -> Logger.warn("Unknown server control action: {} for cron job: {} in group: {}", 
                                    action, cronJob.getName(), groupName);
                        }
                        
                        Logger.info("Executed {} action on server: {} for cron job: {}", 
                                action, server.getName(), cronJob.getName());
                    } catch (Exception e) {
                        Logger.error("Failed to execute {} action on server: {} for cron job: {}", 
                                action, server.getName(), cronJob.getName(), e);
                    }
                }
            }).exceptionally(throwable -> {
                Logger.error("Failed to get servers for group '{}' in cron job: {}", groupName, cronJob.getName(), throwable);
                return null;
            });
        } else {
            Logger.warn("Target '{}' not supported for server control actions in cron job: {} in group: {}", 
                    target, cronJob.getName(), groupName);
        }
    }

    private void executeServerCommandAction(String groupName, ScalerConfig.CronJob cronJob, ScalerConfig.CronStep step) {
        ScalerConfig.ServerCommandAction serverCommand = step.getServerCommand();
        if (serverCommand == null || serverCommand.getCommand() == null) {
            Logger.warn("No server command specified for cron job: {} in group: {}", 
                    cronJob.getName(), groupName);
            return;
        }

        String command = serverCommand.getCommand();
        String target = cronJob.getTarget();

        if (target.equals("servers")) {
            List<AtlasServer> servers = this.atlasBase.getScalerManager().getServersByGroupFromTracking(groupName);
            
            for (AtlasServer server : servers) {
                try {
                    CompletableFuture<Void> commandFuture = this.atlasBase.getServerManager().sendCommand(server.getServerId(), command);
                    CompletableFuture<Void> acceptFuture = commandFuture.thenAccept(v -> 
                            Logger.info("Executed command '{}' on server: {} for cron job: {}", 
                                    command, server.getName(), cronJob.getName()));
                    acceptFuture.exceptionally(throwable -> {
                        Logger.error("Failed to execute command '{}' on server: {} for cron job: {}", 
                                command, server.getName(), cronJob.getName(), throwable);
                        return null;
                    });
                } catch (Exception e) {
                    Logger.error("Failed to send command '{}' to server: {} for cron job: {}", 
                            command, server.getName(), cronJob.getName(), e);
                }
            }
        } else {
            Logger.warn("Target '{}' not supported for server command actions in cron job: {} in group: {}", 
                    target, cronJob.getName(), groupName);
        }
    }

    private void executeBackupAction(String groupName, ScalerConfig.CronJob cronJob, ScalerConfig.CronStep step) {
        ScalerConfig.BackupAction backupAction = step.getBackup();
        if (backupAction == null) {
            Logger.warn("No backup action specified for cron job: {} in group: {}", 
                    cronJob.getName(), groupName);
            return;
        }

        String target = cronJob.getTarget();

        if (target.equals("servers")) {
            List<AtlasServer> servers = this.atlasBase.getScalerManager().getServersByGroupFromTracking(groupName);
            
            for (AtlasServer server : servers) {
                try {
                    Logger.info("Starting backup for server: {} from cron job: {}", 
                            server.getName(), cronJob.getName());
                    
                    CompletableFuture<Void> backupFuture = this.backupManager.executeServerBackup(server, backupAction, cronJob.getName());
                    backupFuture.exceptionally(throwable -> {
                        Logger.error("Failed to backup server: {} for cron job: {}", 
                                server.getName(), cronJob.getName(), throwable);
                        return null;
                    });
                    
                } catch (Exception e) {
                    Logger.error("Failed to start backup for server: {} for cron job: {}", 
                            server.getName(), cronJob.getName(), e);
                }
            }
        } else if (target.equals("group")) {
            try {
                List<AtlasServer> servers = this.atlasBase.getScalerManager().getServersByGroupFromTracking(groupName);
                
                Logger.info("Starting group backup for group: {} from cron job: {}", 
                        groupName, cronJob.getName());
                
                CompletableFuture<Void> backupFuture = this.backupManager.executeGroupBackup(groupName, servers, backupAction, cronJob.getName());
                backupFuture.exceptionally(throwable -> {
                    Logger.error("Failed to backup group: {} for cron job: {}", 
                            groupName, cronJob.getName(), throwable);
                    return null;
                });
                
            } catch (Exception e) {
                Logger.error("Failed to start group backup for group: {} for cron job: {}", 
                        groupName, cronJob.getName(), e);
            }
        } else {
            Logger.warn("Target '{}' not supported for backup actions in cron job: {} in group: {}", 
                    target, cronJob.getName(), groupName);
        }
    }

    public void unscheduleCronJobsForGroup(String groupName) {
        this.scheduledJobs.entrySet().removeIf(entry -> {
            String jobKey = entry.getKey();
            if (jobKey.startsWith(groupName + ":")) {
                ScheduledFuture<?> future = entry.getValue();
                future.cancel(false);
                Logger.debug("Unscheduled cron job: {}", jobKey);
                return true;
            }
            return false;
        });
    }

    public void reloadCronJobs() {
        Logger.info("Reloading all cron jobs");
        
        for (ScheduledFuture<?> future : this.scheduledJobs.values()) {
            future.cancel(false);
        }
        this.scheduledJobs.clear();
        
        this.scheduleAllCronJobs();
        Logger.info("Cron jobs reloaded successfully");
    }

    public void shutdown() {
        Logger.info("Shutting down CronScheduler");
        this.isShuttingDown = true;

        for (ScheduledFuture<?> future : this.scheduledJobs.values()) {
            future.cancel(false);
        }
        this.scheduledJobs.clear();

        if (this.executor != null) {
            this.executor.shutdown();
            try {
                boolean terminated = this.executor.awaitTermination(5, TimeUnit.SECONDS);
                if (!terminated) {
                    this.executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                this.executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        this.backupManager.shutdown();
    }

    public boolean executeJobManually(String groupName, String jobName) {
        Scaler scaler = this.atlasBase.getScalerManager().getScaler(groupName);
        if (scaler == null) {
            Logger.error("Group not found: {}", groupName);
            return false;
        }

        ScalerConfig.Group group = scaler.getScalerConfig().getGroup();
        List<ScalerConfig.CronJob> cronJobs = group.getCronJobs();
        
        if (cronJobs == null || cronJobs.isEmpty()) {
            Logger.error("No cron jobs found for group: {}", groupName);
            return false;
        }

        ScalerConfig.CronJob targetJob = cronJobs.stream()
                .filter(job -> job.getName().equals(jobName))
                .findFirst()
                .orElse(null);

        if (targetJob == null) {
            Logger.error("Cron job '{}' not found in group: {}", jobName, groupName);
            return false;
        }

        if (!targetJob.isEnabled()) {
            Logger.warn("Cron job '{}' is disabled in group: {}, executing anyway", jobName, groupName);
        }

        try {
            String actualGroupName = scaler.getGroupName();
            Logger.info("Manually executing cron job: {} for group: {}", jobName, actualGroupName);
            this.executeCronJob(actualGroupName, targetJob);
            return true;
        } catch (Exception e) {
            Logger.error("Failed to manually execute cron job: {} for group: {}", jobName, groupName, e);
            return false;
        }
    }

    public List<String> listCronJobs(String groupName) {
        Scaler scaler = this.atlasBase.getScalerManager().getScaler(groupName);
        if (scaler == null) {
            return List.of();
        }

        ScalerConfig.Group group = scaler.getScalerConfig().getGroup();
        List<ScalerConfig.CronJob> cronJobs = group.getCronJobs();
        
        if (cronJobs == null || cronJobs.isEmpty()) {
            return List.of();
        }

        return cronJobs.stream()
                .map(job -> {
                    List<ScalerConfig.CronStep> steps = job.getSteps();
                    String stepInfo = steps != null && !steps.isEmpty() 
                            ? steps.size() + " steps" 
                            : "no steps";
                    return String.format("%s (%s) - %s - %s", 
                            job.getName(), 
                            job.getSchedule(),
                            stepInfo,
                            job.isEnabled() ? "enabled" : "disabled");
                })
                .toList();
    }

    public List<String> listAllCronJobs() {
        List<String> allJobs = new ArrayList<>();
        
        for (Scaler scaler : this.atlasBase.getScalerManager().getScalers()) {
            String groupName = scaler.getGroupName();
            List<String> groupJobs = this.listCronJobs(groupName);
            
            for (String job : groupJobs) {
                allJobs.add(groupName + ": " + job);
            }
        }
        
        return allJobs;
    }
}