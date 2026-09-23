package com.neuromuser.randomrespawn;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config config = new Config();

    public static Config get() {
        return config;
    }

    public static void load(Path path) {
        try {
            if (Files.exists(path)) {
                String json = Files.readString(path);
                Config loaded = GSON.fromJson(json, Config.class);
                if (loaded == null) {
                    loaded = new Config();
                }
                if (loaded.playerSettings == null) {
                    loaded.playerSettings = new HashMap<>();
                }
                if (loaded.pendingRespawns == null) {
                    loaded.pendingRespawns = new HashSet<>();
                }
                config = loaded;
            } else {
                config = new Config();
                save(path);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Failed to load config, using defaults: " + e.getMessage());
            config = new Config();
        }
    }

    public static void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(config));
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Failed to save config: " + e.getMessage());
        }
    }
}
