package de.nucleus.command;

import de.nucleus.NucleusPlugin;
import de.nucleus.core.ModuleManager;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;

import static de.nucleus.command.CommandKit.sender;
import static de.nucleus.command.CommandKit.sub;

/** /nucleus [reload|modules]: gehört zum Kern und ist immer registriert. */
public final class CoreCommands {

    private static final String ADMIN = "nucleus.admin";

    private final NucleusPlugin plugin;
    private final CommandKit kit;

    public CoreCommands(NucleusPlugin plugin) {
        this.plugin = plugin;
        // Der Kern ist kein abschaltbares Modul; CommandKit#root wird hier nicht benutzt.
        this.kit = new CommandKit(plugin, "core");
    }

    public void register(Commands registrar) {
        var root = Commands.literal("nucleus")
                .executes(kit.run(ctx -> plugin.messages().send(sender(ctx), "core.info",
                        "version", plugin.getPluginMeta().getVersion())))
                .then(sub("reload", ADMIN).executes(kit.run(ctx -> {
                    boolean restartNeeded = plugin.reloadAll();
                    plugin.messages().send(sender(ctx), "core.reloaded");
                    if (restartNeeded) {
                        plugin.messages().send(sender(ctx), "core.restart-needed");
                    }
                })))
                .then(sub("modules", ADMIN).executes(kit.run(ctx -> {
                    ModuleManager mm = plugin.modules();
                    plugin.messages().send(sender(ctx), "core.modules-header");
                    for (String id : mm.known()) {
                        String key;
                        if (mm.isActive(id)) {
                            key = mm.hasCommands(id) ? "core.module-active" : "core.module-active-no-commands";
                        } else {
                            key = mm.hasCommands(id) ? "core.module-disabled-commands" : "core.module-disabled";
                        }
                        Component line = plugin.messages().get(key, "module", id);
                        sender(ctx).sendMessage(line);
                    }
                })));
        // /nucleus import essentials [ordner]: Ordner relativ zum Serververzeichnis, Standard plugins/Essentials
        // offline: UUIDs über den Namen umrechnen (Daten vom Online-Server, Nucleus läuft offline)
        root.then(sub("import", ADMIN).then(CommandKit.sub("essentials", null)
                .executes(kit.run(ctx -> runImport(ctx, false, null)))
                .then(CommandKit.sub("offline", null)
                        .executes(kit.run(ctx -> runImport(ctx, true, null)))
                        .then(CommandKit.greedy("folder").executes(kit.run(ctx -> runImport(ctx, true, CommandKit.arg(ctx, "folder"))))))
                .then(CommandKit.greedy("folder").executes(kit.run(ctx -> runImport(ctx, false, CommandKit.arg(ctx, "folder")))))));
        CommandKit.register(registrar, root, "Nucleus: Info, Neuladen und Modulstatus", "nuc");
    }

    /** Ordner relativ zum Serververzeichnis, ohne Angabe plugins/Essentials. */
    private void runImport(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx,
                           boolean offline, String path) {
        java.io.File folder = plugin.essentialsImporter().defaultFolder();
        if (path != null) {
            folder = new java.io.File(path);
            if (!folder.isAbsolute()) {
                folder = new java.io.File(plugin.getServer().getWorldContainer(), folder.getPath());
            }
        }
        plugin.essentialsImporter().run(sender(ctx), folder, offline);
    }
}
