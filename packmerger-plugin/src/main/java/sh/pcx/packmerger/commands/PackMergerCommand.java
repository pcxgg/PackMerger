package sh.pcx.packmerger.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.pcx.packmerger.PackMergerBootstrap;
import sh.pcx.packmerger.config.MessageManager;
import sh.pcx.packmerger.merge.MergeProvenance;
import sh.pcx.packmerger.merge.PackValidator;
import sh.pcx.packmerger.remote.FetchResult;
import sh.pcx.packmerger.remote.RemotePackManager;
import sh.pcx.packmerger.remote.RemoteSpec;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Registers and handles all PackMerger admin commands using Paper's Brigadier command API.
 *
 * <p>Commands are registered via Paper's lifecycle event system rather than {@code plugin.yml},
 * which provides better tab completion and argument parsing. All commands require the
 * {@code packmerger.admin} permission (default: op).</p>
 *
 * <p>Available subcommands:</p>
 * <ul>
 *   <li>{@code /packmerger reload} — reloads config, re-initializes the upload provider,
 *       and triggers a full merge-upload cycle</li>
 *   <li>{@code /packmerger validate} — runs validation on the current merged pack</li>
 *   <li>{@code /packmerger status} — displays current plugin state (server name, provider,
 *       last merge time, pack URL, hash, size, etc.)</li>
 *   <li>{@code /packmerger apply [player]} — force-sends the pack to a specific player
 *       or all online players, bypassing the cache</li>
 * </ul>
 *
 * <p>The command is also registered with the alias {@code /pm} for convenience.</p>
 *
 * @see PackMerger
 */
@SuppressWarnings("UnstableApiUsage") // Paper's Brigadier API is marked as experimental
public class PackMergerCommand {

    /** Reference to the owning plugin for component access. */
    private final PackMergerBootstrap plugin;

    /**
     * Creates the command handler and registers all commands.
     *
     * <p>Registration happens immediately in the constructor via Paper's lifecycle
     * event system.</p>
     *
     * @param plugin the owning PackMergerBootstrap plugin
     */
    public PackMergerCommand(PackMergerBootstrap plugin) {
        this.plugin = plugin;
        register();
    }

    /**
     * Registers the {@code /packmerger} command tree with Paper's Brigadier registrar.
     *
     * <p>The command tree structure:</p>
     * <pre>
     * /packmerger (requires packmerger.admin)
     *   ├── reload    — reload config and re-merge
     *   ├── merge     — re-stage plugin packs, merge and upload (no config reload)
     *   ├── validate  — validate current merged pack
     *   ├── status    — show plugin status
     *   └── apply     — force-send pack
     *       └── [player] — optional player selector
     * </pre>
     */
    private void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            commands.register(
                    Commands.literal("packmerger")
                            .requires(source -> source.getSender().hasPermission("packmerger.admin"))
                            .then(Commands.literal("reload")
                                    .executes(this::handleReload))
                            .then(Commands.literal("merge")
                                    .executes(this::handleMerge))
                            .then(Commands.literal("validate")
                                    .executes(this::handleValidate))
                            .then(Commands.literal("status")
                                    .executes(this::handleStatus))
                            .then(Commands.literal("apply")
                                    .executes(this::handleApplyAll)
                                    .then(Commands.argument("player", ArgumentTypes.player())
                                            .executes(this::handleApplyPlayer)))
                            .then(Commands.literal("inspect")
                                    .executes(this::handleInspectSummary)
                                    .then(Commands.literal("collisions")
                                            .executes(this::handleInspectCollisions))
                                    .then(Commands.literal("export")
                                            .executes(this::handleInspectExport))
                                    .then(Commands.argument("pack", StringArgumentType.string())
                                            .executes(this::handleInspectPack)))
                            .then(Commands.literal("fetch")
                                    .executes(this::handleFetchAll)
                                    .then(Commands.argument("alias", StringArgumentType.string())
                                            .executes(this::handleFetchOne)))
                            .then(Commands.literal("profile")
                                    .executes(this::handleProfileList)
                                    .then(Commands.literal("list")
                                            .executes(this::handleProfileList))
                                    .then(Commands.literal("switch")
                                            .then(Commands.argument("name", StringArgumentType.string())
                                                    .executes(this::handleProfileSwitch))))
                            .then(Commands.literal("priority")
                                    .then(Commands.literal("list")
                                            .executes(this::handlePriorityList))
                                    .then(Commands.literal("up")
                                            .then(Commands.argument("pack", StringArgumentType.string())
                                                    .executes(ctx -> handlePriorityMove(ctx, "up"))))
                                    .then(Commands.literal("down")
                                            .then(Commands.argument("pack", StringArgumentType.string())
                                                    .executes(ctx -> handlePriorityMove(ctx, "down"))))
                                    .then(Commands.literal("top")
                                            .then(Commands.argument("pack", StringArgumentType.string())
                                                    .executes(ctx -> handlePriorityMove(ctx, "top"))))
                                    .then(Commands.literal("bottom")
                                            .then(Commands.argument("pack", StringArgumentType.string())
                                                    .executes(ctx -> handlePriorityMove(ctx, "bottom"))))
                                    .then(Commands.literal("set")
                                            .then(Commands.argument("pack", StringArgumentType.string())
                                                    .then(Commands.argument("position", IntegerArgumentType.integer(1))
                                                            .executes(this::handlePrioritySet)))))
                            .build(),
                    "PackMerger admin commands",
                    List.of("pm") // Register /pm as an alias for /packmerger
            );
        });
    }

    /**
     * Handles {@code /packmerger reload} — reloads the config and triggers a full
     * merge-upload cycle.
     *
     * @param ctx the Brigadier command context
     * @return 1 (success)
     */
    private int handleReload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();
        sender.sendMessage(msg.getMessage("reload.starting"));

        plugin.reload();
        plugin.mergeAndUpload(sender);

        return 1;
    }

    /**
     * Handles {@code /packmerger merge} — runs the merge + upload pipeline without
     * reloading the config. Plugin packs are re-staged as part of the merge, so
     * this picks up a plugin's freshly rebuilt pack.
     *
     * @param ctx the Brigadier command context
     * @return 1 (success)
     */
    private int handleMerge(CommandContext<CommandSourceStack> ctx) {
        plugin.mergeAndUpload(ctx.getSource().getSender());
        return 1;
    }

    /**
     * Handles {@code /packmerger validate} — runs pack validation asynchronously
     * and reports results to the sender.
     *
     * <p>Validation runs on an async thread to avoid blocking the main thread for
     * large packs. Results are dispatched back to the main thread for messaging.</p>
     *
     * @param ctx the Brigadier command context
     * @return 1 if validation started, 0 if no merged pack exists
     */
    private int handleValidate(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();

        File outputFile = plugin.getOutputFile();
        if (!outputFile.exists()) {
            sender.sendMessage(msg.getMessage("validate.no-pack"));
            return 0;
        }

        sender.sendMessage(msg.getMessage("validate.starting"));

        // Run validation off the main thread so large packs don't block ticks.
        // Adventure's sendMessage is thread-safe, so we can message the sender
        // directly from the async task — no main-thread hop needed.
        Bukkit.getAsyncScheduler().runNow(plugin.getLoader(), asyncTask -> {
            PackValidator.ValidationResult result = plugin.getValidator().validate(outputFile);
            sender.sendMessage(msg.getMessage("validate.complete",
                    "warnings", String.valueOf(result.warnings()),
                    "errors", String.valueOf(result.errors())));
            if (!result.messages().isEmpty()) {
                for (String line : result.messages()) {
                    if (line.startsWith("ERROR:")) {
                        sender.sendMessage(msg.getMessage("validate.error-line", "message", line));
                    } else {
                        sender.sendMessage(msg.getMessage("validate.warning-line", "message", line));
                    }
                }
            }
        });

        return 1;
    }

    /**
     * Handles {@code /packmerger status} — displays comprehensive plugin state information.
     *
     * @param ctx the Brigadier command context
     * @return 1 (success)
     */
    private int handleStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();

        sender.sendMessage(msg.getMessage("status.header"));
        sender.sendMessage(msg.getMessage("status.server",
                "server", plugin.getConfigManager().getServerName()));
        sender.sendMessage(msg.getMessage("status.upload-provider",
                "provider", plugin.getConfigManager().getUploadProvider()));
        sender.sendMessage(msg.getMessage("status.last-merge",
                "time", plugin.getFormattedLastMergeTime()));

        String url = plugin.getCurrentPackUrl();
        sender.sendMessage(msg.getMessage("status.pack-url",
                "url", url != null ? url : "N/A"));

        String hash = plugin.getCurrentPackHashHex();
        sender.sendMessage(msg.getMessage("status.sha1",
                "hash", hash != null ? hash : "N/A"));

        // Display the output file size if it exists
        File outputFile = plugin.getOutputFile();
        if (outputFile.exists()) {
            long size = outputFile.length();
            String sizeStr;
            if (size < 1024) sizeStr = size + " B";
            else if (size < 1024 * 1024) sizeStr = String.format("%.1f KB", size / 1024.0);
            else sizeStr = String.format("%.1f MB", size / (1024.0 * 1024));
            sender.sendMessage(msg.getMessage("status.pack-size",
                    "size", sizeStr));
        }

        sender.sendMessage(msg.getMessage("status.merging",
                "status", plugin.isMerging() ? "Yes" : "No"));

        // Count and display the number of packs in the packs folder
        File packsFolder = plugin.getPacksFolder();
        File[] packs = packsFolder.listFiles();
        if (packs != null) {
            int count = 0;
            for (File f : packs) {
                // Skip override files and hidden files
                if (f.getName().equals("pack.mcmeta") || f.getName().equals("pack.png")) continue;
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory() || f.getName().endsWith(".zip")) count++;
            }
            sender.sendMessage(msg.getMessage("status.packs-in-folder",
                    "count", String.valueOf(count)));
        }

        if (plugin.getPluginPackManager() != null && plugin.getConfigManager().isPluginPacksEnabled()) {
            List<String> merged = plugin.getPluginPackManager().getMergedAliases();
            sender.sendMessage(msg.getMessage("status.plugin-packs",
                    "packs", merged.isEmpty() ? "none" : String.join(", ", merged)));
        }

        return 1;
    }

    /**
     * Handles {@code /packmerger apply} (no player argument) — force-sends the pack
     * to all online players, bypassing the cache.
     *
     * @param ctx the Brigadier command context
     * @return 1 if the pack was sent, 0 if no merged pack is available
     */
    private int handleApplyAll(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();

        if (plugin.getCurrentPackUrl() == null) {
            sender.sendMessage(msg.getMessage("apply.no-pack"));
            return 0;
        }

        int count = Bukkit.getOnlinePlayers().size();
        plugin.getDistributor().sendToAll(true);
        sender.sendMessage(msg.getMessage("apply.sent-all",
                "count", String.valueOf(count)));
        return 1;
    }

    /**
     * Handles {@code /packmerger apply <player>} — force-sends the pack to a specific
     * player (or players matching the selector), bypassing the cache.
     *
     * @param ctx the Brigadier command context with a "player" argument
     * @return 1 if the pack was sent, 0 if no pack is available or no player matched
     */
    private int handleApplyPlayer(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageManager msg = plugin.getMessageManager();

        if (plugin.getCurrentPackUrl() == null) {
            sender.sendMessage(msg.getMessage("apply.no-pack"));
            return 0;
        }

        try {
            // Resolve the player selector argument (supports @a, @p, player names, etc.)
            PlayerSelectorArgumentResolver resolver = ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
            List<Player> players = resolver.resolve(ctx.getSource());

            if (players.isEmpty()) {
                sender.sendMessage(msg.getMessage("apply.no-match"));
                return 0;
            }

            for (Player player : players) {
                plugin.getDistributor().sendPack(player, true);
                sender.sendMessage(msg.getMessage("apply.sent-player",
                        "player", player.getName()));
            }
        } catch (Exception e) {
            sender.sendMessage(msg.getMessage("apply.player-error",
                    "error", e.getMessage()));
            return 0;
        }

        return 1;
    }

    /** Shared MiniMessage parser for the inspect subcommands' styled output. */
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Sends each rendered line through MiniMessage to the given sender. */
    private static void sendAll(CommandSender sender, List<String> lines) {
        for (String line : lines) {
            sender.sendMessage(MINI.deserialize(line));
        }
    }

    /** {@code /pm inspect} — top-line summary of the last merge. */
    private int handleInspectSummary(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MergeProvenance prov = plugin.getLastMergeProvenance();
        sendAll(sender, InspectRenderer.summary(prov));
        return 1;
    }

    /** {@code /pm inspect collisions} — list every path contributed by 2+ packs. */
    private int handleInspectCollisions(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MergeProvenance prov = plugin.getLastMergeProvenance();
        sendAll(sender, InspectRenderer.collisions(prov));
        return 1;
    }

    /** {@code /pm inspect <pack>} — what this pack won + contributed but lost on. */
    private int handleInspectPack(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String packName = StringArgumentType.getString(ctx, "pack");
        MergeProvenance prov = plugin.getLastMergeProvenance();
        sendAll(sender, InspectRenderer.packDetail(prov, packName));
        return 1;
    }

    // ---- /pm fetch ------------------------------------------------------

    /** {@code /pm fetch} — refetch every remote pack, ignoring refresh policy. */
    private int handleFetchAll(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        RemotePackManager rpm = plugin.getRemotePackManager();
        List<RemoteSpec> specs = plugin.getConfigManager().getRemotePacks();
        if (specs.isEmpty()) {
            sender.sendMessage(MINI.deserialize("<gray>No remote packs configured.</gray>"));
            return 1;
        }
        Bukkit.getAsyncScheduler().runNow(plugin.getLoader(), asyncTask -> {
            List<FetchResult> results = rpm.fetchAll(specs, RemotePackManager.Trigger.MANUAL);
            for (FetchResult r : results) {
                sender.sendMessage(MINI.deserialize(fetchLine(r)));
            }
            sender.sendMessage(MINI.deserialize("<gray>Triggering merge with refreshed packs…</gray>"));
            plugin.mergeAndUpload(sender);
        });
        return 1;
    }

    /** {@code /pm fetch <alias>} — refetch one named remote pack. */
    private int handleFetchOne(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String alias = StringArgumentType.getString(ctx, "alias");
        RemoteSpec spec = plugin.getConfigManager().getRemotePacks().stream()
                .filter(s -> s.alias().equals(alias))
                .findFirst()
                .orElse(null);
        if (spec == null) {
            sender.sendMessage(MINI.deserialize("<red>Unknown remote pack alias: <white>"
                    + alias + "</white></red>"));
            return 0;
        }
        Bukkit.getAsyncScheduler().runNow(plugin.getLoader(), asyncTask -> {
            FetchResult result = plugin.getRemotePackManager().fetch(spec);
            sender.sendMessage(MINI.deserialize(fetchLine(result)));
            sender.sendMessage(MINI.deserialize("<gray>Triggering merge with refreshed pack…</gray>"));
            plugin.mergeAndUpload(sender);
        });
        return 1;
    }

    private static String fetchLine(FetchResult r) {
        return switch (r.status()) {
            case FETCHED -> "<green>✓</green> <white>" + r.alias() + "</white> <gray>— fetched ("
                    + r.detail() + ")</gray>";
            case NOT_MODIFIED -> "<green>✓</green> <white>" + r.alias() + "</white> <gray>— cached (304)</gray>";
            case ERROR_USING_CACHE -> "<yellow>!</yellow> <white>" + r.alias()
                    + "</white> <gray>— fetch failed; using cache (" + r.detail() + ")</gray>";
            case ERROR_NO_CACHE -> "<red>✗</red> <white>" + r.alias()
                    + "</white> <gray>— fetch failed, no cache (" + r.detail() + ")</gray>";
            case SKIPPED_BY_POLICY -> "<gray>· " + r.alias() + " — skipped (" + r.detail() + ")</gray>";
        };
    }

    // ---- /pm profile ----------------------------------------------------

    /** {@code /pm profile [list]} — list defined profiles and mark the active one. */
    private int handleProfileList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var profiles = plugin.getConfigManager().getProfiles();
        if (profiles.isEmpty()) {
            sender.sendMessage(MINI.deserialize("<gray>No profiles defined. "
                    + "Add a <white>profiles:</white> section to config.yml to use this feature.</gray>"));
            return 1;
        }
        String active = plugin.getConfigManager().getActiveProfile();
        sender.sendMessage(MINI.deserialize("<aqua>━━━ Profiles ━━━</aqua>"));
        profiles.forEach((name, cfg) -> {
            String marker = name.equals(active) ? " <green>(active)</green>" : "";
            sender.sendMessage(MINI.deserialize("  <white>" + name + "</white>"
                    + " <gray>[" + cfg.priority().size() + " packs]</gray>" + marker));
        });
        return 1;
    }

    /** {@code /pm profile switch <name>} — activate a profile and re-merge. */
    private int handleProfileSwitch(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        var profiles = plugin.getConfigManager().getProfiles();
        if (!profiles.containsKey(name)) {
            sender.sendMessage(MINI.deserialize("<red>Unknown profile: <white>" + name + "</white></red>"));
            sender.sendMessage(MINI.deserialize("<gray>Known profiles:</gray> <white>"
                    + String.join(", ", profiles.keySet()) + "</white>"));
            return 0;
        }
        plugin.getConfig().set("active-profile", name);
        plugin.saveConfig();
        plugin.getConfigManager().load();
        sender.sendMessage(MINI.deserialize("<green>Switched to profile <white>" + name + "</white></green>"));
        sender.sendMessage(MINI.deserialize("<gray>Triggering merge with new profile…</gray>"));
        plugin.mergeAndUpload(sender);
        return 1;
    }

    // ---- /pm priority ---------------------------------------------------

    /** {@code /pm priority list} — show current priority order with 1-based indices. */
    private int handlePriorityList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        List<String> priority = plugin.getConfigManager().getPriority();
        if (priority.isEmpty()) {
            sender.sendMessage(MINI.deserialize("<gray>Priority list is empty.</gray>"));
            return 1;
        }
        sender.sendMessage(MINI.deserialize("<aqua>━━━ Priority (highest first) ━━━</aqua>"));
        for (int i = 0; i < priority.size(); i++) {
            sender.sendMessage(MINI.deserialize(
                    "<yellow>" + (i + 1) + ".</yellow> <white>" + priority.get(i) + "</white>"));
        }
        return 1;
    }

    /** Shared handler for up/down/top/bottom ops. */
    private int handlePriorityMove(CommandContext<CommandSourceStack> ctx, String op) {
        CommandSender sender = ctx.getSource().getSender();
        String pack = StringArgumentType.getString(ctx, "pack");
        List<String> current = plugin.getConfigManager().getPriority();

        List<String> updated;
        try {
            updated = switch (op) {
                case "up" -> PriorityMutator.up(current, pack);
                case "down" -> PriorityMutator.down(current, pack);
                case "top" -> PriorityMutator.top(current, pack);
                case "bottom" -> PriorityMutator.bottom(current, pack);
                default -> throw new IllegalStateException("unknown op: " + op);
            };
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MINI.deserialize("<red>" + e.getMessage() + "</red>"));
            return 0;
        }

        persistPriorityAndReMerge(sender, updated, "moved <white>" + pack + "</white> " + op);
        return 1;
    }

    /** {@code /pm priority set <pack> <position>} — absolute 1-based placement. */
    private int handlePrioritySet(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String pack = StringArgumentType.getString(ctx, "pack");
        int position = IntegerArgumentType.getInteger(ctx, "position");
        List<String> current = plugin.getConfigManager().getPriority();

        List<String> updated;
        try {
            updated = PriorityMutator.set(current, pack, position);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(MINI.deserialize("<red>" + e.getMessage() + "</red>"));
            return 0;
        }

        persistPriorityAndReMerge(sender, updated, "set <white>" + pack + "</white> to position " + position);
        return 1;
    }

    /**
     * Writes the updated priority list back to {@code config.yml} via Bukkit's
     * config API (into the active profile's list when a profile is active),
     * invalidates the cached ConfigManager state, and triggers a re-merge. Comments in the on-disk config are lost — Bukkit's YAML writer
     * doesn't preserve them. Documented behaviour; the alternative (manual
     * YAML mutation) is a rabbit hole we're not opening for this feature.
     */
    private void persistPriorityAndReMerge(CommandSender sender, List<String> updated, String what) {
        // An active profile's priority shadows the root list (that's what getPriority()
        // returned and the mutation was computed from), so write the change there.
        String activeProfile = plugin.getConfigManager().getActiveProfile();
        String key = activeProfile != null ? "profiles." + activeProfile + ".priority" : "priority";
        plugin.getConfig().set(key, updated);
        plugin.saveConfig();
        plugin.getConfigManager().load();
        sender.sendMessage(MINI.deserialize("<green>Priority updated:</green> <gray>" + what + "</gray>"));
        sender.sendMessage(MINI.deserialize("<gray>Triggering merge with new priority…</gray>"));
        plugin.mergeAndUpload(sender);
    }

    /** {@code /pm inspect export} — write the full report to output/last-merge-report.txt. */
    private int handleInspectExport(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MergeProvenance prov = plugin.getLastMergeProvenance();
        if (prov == null) {
            sender.sendMessage(MINI.deserialize("<red>No merge has completed yet.</red>"));
            return 0;
        }

        File reportFile = new File(plugin.getOutputFolder(), "last-merge-report.txt");
        try {
            List<String> lines = InspectRenderer.fullReport(prov);
            Files.writeString(reportFile.toPath(), String.join(System.lineSeparator(), lines),
                    StandardCharsets.UTF_8);
            sender.sendMessage(MINI.deserialize("<green>Wrote merge report to <white>"
                    + reportFile.getAbsolutePath() + "</white></green>"));
            return 1;
        } catch (Exception e) {
            sender.sendMessage(MINI.deserialize("<red>Failed to write report: "
                    + e.getMessage() + "</red>"));
            return 0;
        }
    }
}
