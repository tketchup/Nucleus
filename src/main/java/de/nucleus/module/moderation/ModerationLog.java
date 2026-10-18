package de.nucleus.module.moderation;

import de.nucleus.NucleusPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Protokoll aller Moderationsaktionen in plugins/Nucleus/moderation.log, eine Zeile pro Aktion:
 * {@code 2026-10-17 23:40:12 | Console | BAN | ketc_hup | duration=1d | reason=Spam}.
 * Bewusst sprachunabhängig (englische Schlüssel), damit das Protokoll einheitlich bleibt.
 * Geschrieben wird asynchron über den IO-Thread.
 */
final class ModerationLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NucleusPlugin plugin;
    private final Path file;

    ModerationLog(NucleusPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("moderation.log");
    }

    /**
     * @param details zusätzliche Angaben als Paare: "duration", "1d", "reason", "Spam"
     */
    void write(CommandSender actor, String action, String target, String... details) {
        StringBuilder line = new StringBuilder()
                .append(LocalDateTime.now().format(TIME)).append(" | ")
                .append(actor instanceof ConsoleCommandSender ? "Console" : actor.getName()).append(" | ")
                .append(action).append(" | ")
                .append(target);
        for (int i = 0; i + 1 < details.length; i += 2) {
            // Zeilenumbrüche entfernen, damit eine Aktion immer genau eine Zeile bleibt
            line.append(" | ").append(details[i]).append('=').append(details[i + 1].replace('\n', ' '));
        }
        plugin.io().append(file, line.toString());
    }
}
