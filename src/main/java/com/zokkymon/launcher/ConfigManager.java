package com.zokkymon.launcher;

import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.*;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

public class ConfigManager {

    /** Chemin fixe du fichier config — toujours dans ~/.zokkymon/config/, indépendant de l'exe */
    private static final String CONFIG_PATH = System.getProperty("user.home")
            + System.getProperty("file.separator") + ".zokkymon"
            + System.getProperty("file.separator") + "config"
            + System.getProperty("file.separator") + "launcher_config.json";

    /**
     * Clés dont la valeur de référence est toujours celle du JAR.
     * Elles ne sont jamais écrites dans le fichier utilisateur.
     */
    private static final Set<String> JAR_KEYS = Set.of(
        "launcherVersion", "launcherInfoUrl", "modpackInfoUrl", "serverUrl",
        "modpackName", "fabricVersion", "minecraftVersion", "enableVersionCheck",
        "msaClientId", "launcherExeName"
    );

    /** Clés obsolètes retirées du fichier utilisateur au premier chargement. */
    private static final Set<String> DEPRECATED_KEYS = Set.of("modpackVersion");

    /** Config système — chargée depuis le JAR, jamais écrite sur disque. */
    private JSONObject jarConfig;

    /** Config utilisateur — préférences et données propres à la machine. */
    private JSONObject userConfig;

    @SuppressWarnings("this-escape")
    public ConfigManager() {
        loadJarConfig();
        loadUserConfig();
    }

    // ── Chargement ────────────────────────────────────────────────────────────

    private void loadJarConfig() {
        try (InputStream is = getClass().getResourceAsStream("/launcher_config.json")) {
            if (is != null) {
                jarConfig = new JSONObject(new String(is.readAllBytes()));
            } else {
                jarConfig = new JSONObject();
                System.err.println("[WARN] Config embarquée introuvable dans le JAR");
            }
        } catch (IOException e) {
            jarConfig = new JSONObject();
            System.err.println("[ERR] Lecture config JAR : " + e.getMessage());
        }
    }

    private void loadUserConfig() {
        Path configPath = Paths.get(CONFIG_PATH);
        // Migration : ancien emplacement ~/.zokkymon/launcher_config.json → ~/.zokkymon/config/
        String oldPath = System.getProperty("user.home") + System.getProperty("file.separator")
                + ".zokkymon" + System.getProperty("file.separator") + "launcher_config.json";
        Path oldConfigPath = Paths.get(oldPath);
        if (!Files.exists(configPath) && Files.exists(oldConfigPath)) {
            try {
                Files.createDirectories(configPath.getParent());
                Files.move(oldConfigPath, configPath);
                System.out.println("[INFO] launcher_config.json migré vers " + configPath);
            } catch (IOException e) {
                System.err.println("[WARN] Migration impossible : " + e.getMessage());
            }
        }

        if (Files.exists(configPath)) {
            try {
                JSONObject raw = new JSONObject(new String(Files.readAllBytes(configPath)));
                userConfig = new JSONObject();
                boolean cleaned = false;
                // Copie uniquement les clés utilisateur — retire les clés JAR et obsolètes si elles traînent
                for (String key : raw.keySet()) {
                    if (JAR_KEYS.contains(key) || DEPRECATED_KEYS.contains(key)) {
                        cleaned = true;
                    } else {
                        userConfig.put(key, raw.get(key));
                    }
                }
                if (cleaned) {
                    System.out.println("[INFO] Clés système retirées du fichier utilisateur.");
                    saveConfig();
                }
            } catch (IOException e) {
                System.err.println("[ERR] Lecture config utilisateur : " + e.getMessage());
                userConfig = defaultUserConfig();
                saveConfig();
            }
        } else {
            userConfig = defaultUserConfig();
            saveConfig();
        }
    }

    private JSONObject defaultUserConfig() {
        JSONObject u = new JSONObject();
        u.put("installPath", System.getProperty("user.home")
                + System.getProperty("file.separator") + ".zokkymon");
        u.put("ram", "6 Go");
        u.put("language", "fr");
        u.put("darkMode", true);
        u.put("launcherChannel", inferDefaultChannel());
        u.put("launchProfile", "performance");
        u.put("customJvmArgs", "");
        return u;
    }

    public void saveConfig() {
        try {
            Path configPath = Paths.get(CONFIG_PATH);
            Files.createDirectories(configPath.getParent());
            Files.write(configPath, userConfig.toString(2).getBytes());
        } catch (IOException e) {
            System.err.println("[ERR] Sauvegarde config : " + e.getMessage());
        }
    }

    // ── Getters JAR / runtime ───────────────────────────────────────────────────

    public String getLauncherVersion()  {
        String embedded = jarConfig.optString("launcherVersion", "0.1.0").trim();
        String local = userConfig.optString("launcherVersionLocal", "").trim();

        if (embedded.isBlank()) embedded = "0.1.0";
        if (local.isBlank()) return embedded;

        return compareVersions(local, embedded) > 0 ? local : embedded;
    }
    public String getServerUrl()        { return jarConfig.optString("serverUrl", ""); }
    public String getMinecraftVersion() { return jarConfig.optString("minecraftVersion", "1.21.1"); }
    public String getFabricVersion()    { return jarConfig.optString("fabricVersion", "0.18.1"); }
    public String getModpackName()      { return jarConfig.optString("modpackName", "Zokkymon"); }
    public String getLauncherExeName()  { return jarConfig.optString("launcherExeName", "ZokkymonLauncher.exe"); }
    public String getModpackInfoUrl()   { return jarConfig.optString("modpackInfoUrl", ""); }
    public boolean isVersionCheckEnabled() { return jarConfig.optBoolean("enableVersionCheck", true); }

    public String getClientId() {
        return jarConfig.optString("msaClientId", "").strip();
    }

    public String getLauncherInfoUrl() {
        String u = jarConfig.optString("launcherInfoUrl", "");
        if (!u.isBlank()) {
            return mapLauncherInfoUrlForChannel(u, getLauncherChannel());
        }
        String server = getServerUrl();
        return server + (server.endsWith("/") ? "" : "/") + "launcher/info.json";
    }

    public String getLauncherChannel() {
        String c = userConfig.optString("launcherChannel", "").toLowerCase(Locale.ROOT).trim();
        if ("beta".equals(c)) return "beta";
        if ("stable".equals(c) || "main".equals(c)) return "stable";
        return inferDefaultChannel();
    }

    public void setLauncherChannel(String channel) {
        String normalized = "beta".equalsIgnoreCase(channel) ? "beta" : "stable";
        userConfig.put("launcherChannel", normalized);
        saveConfig();
    }

    private String inferDefaultChannel() {
        String url = jarConfig.optString("launcherInfoUrl", "").toLowerCase(Locale.ROOT);
        if (url.contains("/beta/")) return "beta";
        return "stable";
    }

    private String mapLauncherInfoUrlForChannel(String originalUrl, String channel) {
        String targetBranch = "beta".equals(channel) ? "beta" : "main";
        try {
            URI uri = URI.create(originalUrl);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || path == null) return originalUrl;
            if (!"raw.githubusercontent.com".equalsIgnoreCase(host)) return originalUrl;

            String[] parts = path.split("/");
            // path attendu : /owner/repo/branch/...
            if (parts.length < 5) return originalUrl;
            parts[3] = targetBranch;

            StringBuilder rebuiltPath = new StringBuilder();
            for (String p : parts) {
                if (p == null || p.isEmpty()) continue;
                rebuiltPath.append('/').append(p);
            }

            URI mapped = new URI(
                uri.getScheme(),
                uri.getUserInfo(),
                uri.getHost(),
                uri.getPort(),
                rebuiltPath.toString(),
                uri.getQuery(),
                uri.getFragment()
            );
            return mapped.toString();
        } catch (Exception ignored) {
            return originalUrl;
        }
    }

    public void setLauncherVersion(String version) {
        if (version == null) return;
        String normalized = version.trim();
        if (normalized.isBlank()) return;
        userConfig.put("launcherVersionLocal", normalized);
        saveConfig();
    }

    private int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? parseVersionPart(pa[i]) : 0;
            int vb = i < pb.length ? parseVersionPart(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private int parseVersionPart(String part) {
        if (part == null) return 0;
        String numeric = part.replaceAll("[^0-9]", "").trim();
        if (numeric.isBlank()) return 0;
        try { return Integer.parseInt(numeric); } catch (Exception e) { return 0; }
    }

    // ── modpackToken : source JAR, copie chiffrée dans userConfig ─────────────

    public String getModpackToken() {
        // 1. Copie chiffrée déjà dans userConfig ?
        String raw = userConfig.optString("modpackToken", "");
        if (!raw.isBlank() && SecureStorage.looksEncrypted(raw)) {
            String decrypted = SecureStorage.decrypt(raw);
            return decrypted != null ? decrypted : "";
        }
        // 2. Lire depuis JAR et chiffrer immédiatement dans userConfig
        String plain = jarConfig.optString("modpackToken", "");
        if (!plain.isBlank()) {
            try {
                String enc = SecureStorage.encrypt(plain);
                if (enc != null) { userConfig.put("modpackToken", enc); saveConfig(); }
            } catch (Exception ignored) {}
            return plain;
        }
        return "";
    }

    public void setModpackToken(String token) {
        try {
            String enc = SecureStorage.encrypt(token);
            userConfig.put("modpackToken", enc != null ? enc : token);
        } catch (Exception e) {
            userConfig.put("modpackToken", token);
        }
        saveConfig();
    }

    // ── Getters/Setters utilisateur ───────────────────────────────────────────

    public String getInstallPath() {
        return userConfig.optString("installPath",
                System.getProperty("user.home") + System.getProperty("file.separator") + ".zokkymon");
    }

    public void setInstallPath(String path) {
        userConfig.put("installPath", path);
        saveConfig();
    }

    public String getRamAllocation() {
        String ram = userConfig.optString("ram", "6 Go");
        if (ram != null && ram.matches("\\d+Go")) ram = ram.replace("Go", " Go");
        return ram;
    }

    public void setRamAllocation(String ram) {
        userConfig.put("ram", ram);
        saveConfig();
    }

    public String getLanguage() {
        String lang = userConfig.optString("language", "fr");
        return (lang == null || lang.isBlank()) ? "fr" : lang;
    }

    public void setLanguage(String lang) {
        userConfig.put("language", lang);
        saveConfig();
    }

    public boolean isDarkMode()          { return userConfig.optBoolean("darkMode", true); }
    public void setDarkMode(boolean dark) { userConfig.put("darkMode", dark); saveConfig(); }

    /**
     * Détecte la version installée en cherchant le dossier {@code zokkymon_v*} le plus récent
     * dans le répertoire d'installation. Aucune donnée stockée en config.
     */
    public String getModpackVersion() {
        File installDir = new File(getInstallPath());
        File[] dirs = installDir.listFiles(
                f -> f.isDirectory() && f.getName().startsWith("zokkymon_v"));
        if (dirs == null || dirs.length == 0) return "";
        java.util.Arrays.sort(dirs);
        return dirs[dirs.length - 1].getName().replace("zokkymon_v", "");
    }

    /** No-op : la version est désormais dérivée du nom de dossier sur disque. */
    public void setModpackVersion(String version) {}

    // ── MSA (Microsoft Authentication) ───────────────────────────────────────

    public boolean hasMsaProfile() {
        return !getMsaUsername().isEmpty() && !getMsaRefreshToken().isEmpty();
    }

    public String getMsaUsername() { return userConfig.optString("msaUsername", ""); }
    public String getMsaUuid()     { return userConfig.optString("msaUuid",     ""); }
    public long   getMsaExpiresAt(){ return userConfig.optLong("msaExpiresAt",  0L); }

    public String getMsaAccessToken() {
        String raw = userConfig.optString("msaAccessToken", "");
        if (raw.isBlank()) return "";
        if (SecureStorage.looksEncrypted(raw)) {
            String decrypted = SecureStorage.decrypt(raw);
            return decrypted != null ? decrypted : "";
        }
        return ""; // ancienne valeur en clair → forcer re-login
    }

    public String getMsaRefreshToken() {
        String raw = userConfig.optString("msaRefreshToken", "");
        if (raw.isBlank()) return "";
        if (SecureStorage.looksEncrypted(raw)) {
            String decrypted = SecureStorage.decrypt(raw);
            return decrypted != null ? decrypted : "";
        }
        return ""; // ancienne valeur en clair → forcer re-login
    }

    /** Persiste le profil complet obtenu après une authentification réussie.
     *  Les tokens sont chiffrés via {@link SecureStorage} avant d'être écrits sur disque. */
    public void saveMsaProfile(MicrosoftAuth.McProfile p) {
        userConfig.put("msaUsername",  p.username);
        userConfig.put("msaUuid",      p.uuid);
        userConfig.put("msaExpiresAt", p.expiresAtMs);
        try {
            userConfig.put("msaAccessToken",  SecureStorage.encrypt(p.accessToken));
            userConfig.put("msaRefreshToken", SecureStorage.encrypt(p.refreshToken));
        } catch (Exception e) {
            System.err.println("[WARN] Impossible de chiffrer les tokens MSA : " + e.getMessage());
            userConfig.put("msaAccessToken",  p.accessToken);
            userConfig.put("msaRefreshToken", p.refreshToken);
        }
        saveConfig();
    }

    /** Supprime toutes les données MSA (déconnexion). */
    public void clearMsaProfile() {
        userConfig.remove("msaUsername");
        userConfig.remove("msaUuid");
        userConfig.remove("msaAccessToken");
        userConfig.remove("msaRefreshToken");
        userConfig.remove("msaExpiresAt");
        saveConfig();
    }

    // ── Résolution de la fenêtre Minecraft ───────────────────────────────────
    public int getWindowWidth()  { return userConfig.optInt("windowWidth",  1280); }
    public int getWindowHeight() { return userConfig.optInt("windowHeight",  720); }
    public boolean isFullscreen() { return userConfig.optBoolean("fullscreen", false); }

    public void setWindowSize(int width, int height) {
        userConfig.put("windowWidth",  width);
        userConfig.put("windowHeight", height);
        saveConfig();
    }

    public void setFullscreen(boolean fullscreen) {
        userConfig.put("fullscreen", fullscreen);
        saveConfig();
    }

    // ── Profils de lancement / JVM args ─────────────────────────────────────
    public String getLaunchProfile() {
        String p = userConfig.optString("launchProfile", "performance").toLowerCase(Locale.ROOT).trim();
        return switch (p) {
            case "quality", "qualite" -> "quality";
            case "low-end", "lowend" -> "low-end";
            case "custom" -> "custom";
            default -> "performance";
        };
    }

    public void setLaunchProfile(String profile) {
        String normalized = switch (profile == null ? "" : profile.toLowerCase(Locale.ROOT).trim()) {
            case "quality", "qualite" -> "quality";
            case "low-end", "lowend" -> "low-end";
            case "custom" -> "custom";
            default -> "performance";
        };
        userConfig.put("launchProfile", normalized);
        saveConfig();
    }

    public String getCustomJvmArgs() {
        return userConfig.optString("customJvmArgs", "").trim();
    }

    public void setCustomJvmArgs(String args) {
        userConfig.put("customJvmArgs", args == null ? "" : args.trim());
        saveConfig();
    }
}
