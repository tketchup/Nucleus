package de.nucleus.data;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ein einzelner IO-Thread für alle Dateizugriffe. Weil es genau ein Thread ist, laufen Schreib- und
 * Lesevorgänge in Auftragsreihenfolge: ein Laden nach einem Speichern sieht immer den neuen Stand.
 */
public final class AsyncIO {

    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Nucleus-IO");
        t.setDaemon(true);
        return t;
    });

    public AsyncIO(Logger logger) {
        this.logger = logger;
    }

    /** Schreibt atomar (temporäre Datei + Umbenennen), damit ein Absturz keine halbe Datei hinterlässt. */
    public CompletableFuture<Void> write(Path file, String content) {
        return CompletableFuture.runAsync(() -> {
            try {
                writeNow(file, content);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Konnte " + file + " nicht speichern", e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    /** Wie {@link #write(Path, String)}, aber der Text wird erst im IO-Thread erzeugt (für große Dateien). */
    public CompletableFuture<Void> write(Path file, Supplier<String> content) {
        return CompletableFuture.runAsync(() -> {
            try {
                writeNow(file, content.get());
            } catch (IOException | RuntimeException e) {
                logger.log(Level.SEVERE, "Konnte " + file + " nicht speichern", e);
                throw new CompletionException(e);
            }
        }, executor);
    }

    /** Hängt eine Zeile an (z. B. Protokolle). Reihenfolge bleibt dank des einen Threads erhalten. */
    public CompletableFuture<Void> append(Path file, String line) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Konnte nicht an " + file + " anhängen", e);
            }
        }, executor);
    }

    public <T> CompletableFuture<T> supply(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /** Wartet, bis alle offenen Aufträge erledigt sind (beim Stoppen). */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.warning("IO-Thread wurde nach 15 s nicht fertig, Daten könnten fehlen.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void writeNow(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
