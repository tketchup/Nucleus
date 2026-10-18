package de.nucleus.module.lock;

import com.mojang.brigadier.context.CommandContext;
import de.nucleus.NucleusPlugin;
import de.nucleus.command.CommandFail;
import de.nucleus.command.CommandKit;
import de.nucleus.gui.Dialogs;
import de.nucleus.util.Passwords;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static de.nucleus.command.CommandKit.self;
import static de.nucleus.command.CommandKit.sub;
import static de.nucleus.command.CommandKit.word;

/**
 * /lock, /lock password [off], /lock trust|untrust &lt;spieler&gt;, /lock info, /unlock.
 * Passwörter werden nur über einen Dialog eingegeben, nie als Befehlsargument: so landen sie nicht im
 * Server-Log, wo jeder ausgeführte Befehl mitgeschrieben wird.
 */
final class LockCommands {

    private final NucleusPlugin plugin;
    private final LockModule module;
    private final CommandKit kit;

    LockCommands(NucleusPlugin plugin, LockModule module, CommandKit kit) {
        this.plugin = plugin;
        this.module = module;
        this.kit = kit;
    }

    void register(Commands r) {
        CommandKit.register(r, kit.root("lock", "nucleus.lock")
                .executes(kit.run(this::lock))
                .then(sub("password", "nucleus.lock.password")
                        .executes(kit.run(this::askNewPassword))
                        .then(sub("off", null).executes(kit.run(this::removePassword))))
                .then(sub("trust", null).then(word("player").suggests(CommandKit.players())
                        .executes(kit.run(ctx -> trust(ctx, true)))))
                .then(sub("untrust", null).then(word("player").suggests(CommandKit.players())
                        .executes(kit.run(ctx -> trust(ctx, false)))))
                .then(sub("info", null).executes(kit.run(this::info))),
                "Sperrt den Behälter, auf den du schaust", "protect");

        CommandKit.register(r, kit.root("unlock", "nucleus.lock")
                .executes(kit.run(this::unlock)),
                "Hebt die Sperre auf", "unprotect");
    }

    /** Behälter im Blickfeld (Reichweite aus der Config). */
    private Block target(Player player) {
        Block block = player.getTargetBlockExact(module.settings().maxDistance());
        if (block == null) {
            throw new CommandFail("lock.no-target");
        }
        return block;
    }

    /** Gesperrter Behälter, den der Spieler verwalten darf (Besitzer oder nucleus.lock.admin). */
    private LockService.Lock ownedLock(Player player, Block block) {
        LockService.Lock lock = module.locks().get(block);
        if (lock == null) {
            throw new CommandFail("lock.not-locked");
        }
        if (!lock.owner().equals(player.getUniqueId()) && !player.hasPermission("nucleus.lock.admin")) {
            throw new CommandFail("lock.not-owner", "player", name(lock.owner()));
        }
        return lock;
    }

    private void lock(CommandContext<CommandSourceStack> ctx) {
        Player player = self(ctx);
        Block block = target(player);
        if (!module.isLockable(block)) {
            throw new CommandFail("lock.not-lockable");
        }
        LockService.Lock existing = module.locks().get(block);
        if (existing != null) {
            throw new CommandFail("lock.already-locked", "player", name(existing.owner()));
        }
        module.locks().set(block, new LockService.Lock(player.getUniqueId(), new HashSet<>(), null));
        plugin.messages().send(player, "lock.locked-self");
    }

    private void unlock(CommandContext<CommandSourceStack> ctx) {
        Player player = self(ctx);
        Block block = target(player);
        ownedLock(player, block);
        module.locks().remove(block);
        plugin.messages().send(player, "lock.unlocked");
    }

    private void askNewPassword(CommandContext<CommandSourceStack> ctx) {
        Player player = self(ctx);
        if (!module.settings().passwordsEnabled()) {
            throw new CommandFail("lock.passwords-disabled");
        }
        Block block = target(player);
        ownedLock(player, block);
        Dialogs.askText(plugin, player, plugin.messages().get("lock.password-set-title"),
                plugin.messages().get("lock.password-label"), "", 64, input -> {
                    int min = module.settings().passwordMinLength();
                    if (input.length() < min) {
                        plugin.messages().send(player, "lock.password-too-short", "min", min);
                        return;
                    }
                    module.offThread(() -> Passwords.hash(input), hash -> {
                        LockService.Lock now = module.locks().get(block);
                        if (now == null) {
                            plugin.messages().send(player, "lock.not-locked");
                            return;
                        }
                        module.locks().set(block, new LockService.Lock(now.owner(), now.trusted(), hash));
                        plugin.messages().send(player, "lock.password-set");
                    });
                });
    }

    private void removePassword(CommandContext<CommandSourceStack> ctx) {
        Player player = self(ctx);
        Block block = target(player);
        LockService.Lock lock = ownedLock(player, block);
        module.locks().set(block, new LockService.Lock(lock.owner(), lock.trusted(), null));
        plugin.messages().send(player, "lock.password-removed");
    }

    private void trust(CommandContext<CommandSourceStack> ctx, boolean add) {
        Player player = self(ctx);
        Block block = target(player);
        LockService.Lock lock = ownedLock(player, block);
        OfflinePlayer other = CommandKit.known(ctx, "player");
        Set<UUID> trusted = new HashSet<>(lock.trusted());
        String otherName = other.getName() != null ? other.getName() : other.getUniqueId().toString();
        if (add ? !trusted.add(other.getUniqueId()) : !trusted.remove(other.getUniqueId())) {
            throw new CommandFail(add ? "lock.already-trusted" : "lock.not-trusted", "player", otherName);
        }
        module.locks().set(block, new LockService.Lock(lock.owner(), trusted, lock.passwordHash()));
        plugin.messages().send(player, add ? "lock.trusted" : "lock.untrusted", "player", otherName);
    }

    private void info(CommandContext<CommandSourceStack> ctx) {
        Player player = self(ctx);
        Block block = target(player);
        LockService.Lock lock = module.locks().get(block);
        if (lock == null) {
            throw new CommandFail("lock.not-locked");
        }
        plugin.messages().send(player, "lock.info-owner", "player", name(lock.owner()));
        // Details nur für Besitzer und Admins
        if (lock.owner().equals(player.getUniqueId()) || player.hasPermission("nucleus.lock.admin")) {
            List<String> names = new ArrayList<>();
            lock.trusted().forEach(id -> names.add(name(id)));
            plugin.messages().send(player, "lock.info-trusted",
                    "players", names.isEmpty() ? plugin.messages().raw("lock.info-nobody") : String.join(", ", names));
            plugin.messages().send(player, lock.hasPassword() ? "lock.info-password-yes" : "lock.info-password-no");
        }
    }

    private static String name(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : id.toString();
    }
}
