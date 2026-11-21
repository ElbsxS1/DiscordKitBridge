package br.asthen.kitbridge.core;

import br.asthen.kitbridge.util.Atomic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Who already received the Kit Inicial once (by UUID), saved on disk so it survives restarts. This is what stops the
 * lobby villager (or leaving and joining again) from being used to collect the kit over and over: the kit given on
 * join / entering survival is "first time only"; dying always gives it again.
 *
 * <p>File format: one UUID per line. Unreadable lines are ignored (and logged). If saving fails, the player is still
 * remembered in memory until the next restart and the problem is logged; nobody ever gets the kit twice because of it.
 */
public final class StarterKitReceived {

    private final Path file;
    private final Consumer<String> warn;
    private final Set<UUID> received = ConcurrentHashMap.newKeySet();

    public StarterKitReceived(Path file, Consumer<String> warn) {
        this.file = file;
        this.warn = warn;
    }

    /** Reads the file (a missing file is simply an empty list). @return how many players are remembered */
    public synchronized int load() {
        received.clear();
        if (!Files.isRegularFile(file)) return 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.isEmpty()) continue;
                try {
                    received.add(UUID.fromString(t));
                } catch (IllegalArgumentException bad) {
                    warn.accept("starter-received.txt: linha ignorada (UUID inválido): " + t);
                }
            }
        } catch (IOException e) {
            warn.accept("Não foi possível ler " + file.getFileName() + ": " + e.getMessage()
                    + ". Jogadores que já receberam o Kit Inicial podem recebê-lo de novo na primeira entrada.");
        }
        return received.size();
    }

    public boolean has(UUID id) {
        return received.contains(id);
    }

    /** Remembers the player and saves. @return true if they were not remembered before */
    public synchronized boolean add(UUID id) {
        if (!received.add(id)) return false;
        StringBuilder sb = new StringBuilder();
        for (UUID u : received) sb.append(u).append('\n');
        try {
            Atomic.write(file, sb.toString());
        } catch (IOException e) {
            warn.accept("Não foi possível salvar " + file.getFileName() + ": " + e.getMessage()
                    + " (o jogador fica lembrado só até reiniciar).");
        }
        return true;
    }

    public int size() {
        return received.size();
    }
}
