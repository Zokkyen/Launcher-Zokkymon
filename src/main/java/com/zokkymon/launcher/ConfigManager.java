package com.zokkymon.launcher;

import org.json.JSONObject;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.*;

public class ConfigManager {

    /** Chemin fixe du fichier config — toujours dans ~/.zokkymon/config/, indépendant de l'exe */
    private static final String CONFIG_PATH = System.getProperty("user.home")
            + System.getProperty("file.separator") + ".zokkymon"
            + System.getProperty("file.separator") + "config"
            + System.getProperty("file.separator") + "launcher_config.json";

    private JSONObject config;

    @SuppressWarnings("this-escape")
    public ConfigManager() {
        loadConfig();
    }

    private void loadConfig() {
        try {
            Path configPath = Paths.get(CONFIG_PATH);
            // Migration : ancien emplacement ~/.zokkymon/launcher_config.json → ~/.zokkymon/config/
            String oldPath = System.getProperty("user.home") + System.getProperty("file.separator")
                    + ".zokkymon" + System.getProperty("file.separator") + "launcher_config.json";
            Path oldConfigPath = Paths.get(oldPath);
            if (!Files.exists(configPath) && Files.exists(oldConfigPath)) {
                Files.createDirectories(configPath.getParent());
                Files.move(oldConfigPath, configPath);
                System.out.println("[INFO] launcher_config.json migré vers " + configPath);
            }

            if (Files.exists(configPath)) {
                String content = new String(Files.readAllBytes(configPath));
                config = new JSONObject(content);
                // Fusionner les clés manquantes depuis la config embarquée dans le JAR
                mergeEmbeddedConfig();
            } else {
                // Pas de fichier externe : charger la config embarquée dans le JAR
                loadEmbeddedConfig();
            }
        } catch (IOException e) {
            System.err.println("Erreur lors de la lecture de la config : " + e.getMessage());
            loadEmbeddedConfig();
        }
    }

    /**
     * Complète la config locale avec les clés présentes dans la config embarquée
     * mais absentes du fichier local (ex: modpackToken, launcherInfoUrl...).
     * Les valeurs existantes dans le fichier local ne sont jamais écrasées.
     */
    private void mergeEmbeddedConfig() {
        try (InputStream is = getClass().getResourceAsStream("/launcher_config.json")) {
            if (is == null) return;
            JSONObject embedded = new JSONObject(new String(is.readAllBytes()));
            boolean changed = false;
            for (String key : embedded.keySet()) {
                if (!config.has(key) || config.optString(key, "").trim().isEmpty()) {
                    // Ne pas écraser installPath avec celui de la machine de build
                    if ("installPath".equals(key)) continue;
                    config.put(key, embedded.get(key));
                    changed = true;
                }
            }
            if (changed) {
                System.out.println("[INFO] Config locale complétée depuis la config embarquée.");
                saveConfig();
            }
        } catch (IOException e) {
            System.err.println("Erreur lors de la fusion de la config embarquée : " + e.getMessage());
        }
    }

    private void loadEmbeddedConfig() {
        try (InputStream is = getClass().getResourceAsStream("/launcher_config.json")) {
            if (is != null) {
                String content = new String(is.readAllBytes());
                config = new JSONObject(content);
                // On normalise le installPath avec le home réel de cette machine
                String defaultInstall = System.getProperty("user.home")
                        + System.getProperty("file.separator") + ".zokkymon";
                config.put("installPath", defaultInstall);
                System.out.println("[INFO] Config embarquée chargée depuis le JAR.");
                saveConfig(); // écrire le fichier externe pour les prochains lancements
            } else {
                System.out.println("[WARN] Aucune config embarquée trouvée, génération des valeurs par défaut.");
                createDefaultConfig();
            }
        } catch (IOException e) {
            System.err.println("Erreur lecture config embarquée : " + e.getMessage());
            createDefaultConfig();
        }
    }

    private void createDefaultConfig() {
        config = new JSONObject();
        config.put("serverUrl", "http://localhost:8080");
        config.put("minecraftVersion", "1.21.1");
        config.put("fabricVersion", "0.18.1");
        config.put("modpackName", "Zokkymon");
        config.put("launcherVersion", "0.1.0");
        config.put("language", "fr");
        config.put("ram", "6 Go");
        // URL publique fournissant les métadonnées de mise à jour du launcher (version, url, sha256)
        config.put("launcherInfoUrl", "");
        // URL du endpoint modpack info.json
        config.put("modpackInfoUrl", "https://zokkyen-cobblemon.ddns.net/modpack/info.json");
        // Token pour authentifier les téléchargements du modpack
        config.put("modpackToken", "");
        // Nom de l'exécutable du launcher attendu (utilisé pour remplacer le binaire)
        config.put("launcherExeName", "ZokkymonLauncher.exe");
        // Default install path: use user home + .zokkymon folder
        String installPath = System.getProperty("user.home") + System.getProperty("file.separator") + ".zokkymon";
        config.put("installPath", installPath);
        config.put("enableVersionCheck", true);
        saveConfig();
    }

    public void saveConfig() {
        try {
            Path configPath = Paths.get(CONFIG_PATH);
            Files.createDirectories(configPath.getParent()); // crée ~/.zokkymon/ si absent
            Files.write(configPath, config.toString(2).getBytes());
        } catch (IOException e) {
            System.err.println("Erreur lors de la sauvegarde de la config : " + e.getMessage());
        }
    }

    public String getServerUrl() {
        return config.getString("serverUrl");
    }

    public String getMinecraftVersion() {
        return config.getString("minecraftVersion");
    }

    public String getFabricVersion() {
        return config.getString("fabricVersion");
    }

    public String getModpackName() {
        return config.getString("modpackName");
    }

    public String getRamAllocation() {
        // Normalise l'ancienne syntaxe "6Go" → "6 Go" pour compatibilité
        String ram = config.getString("ram");
        if (ram != null && ram.matches("\\d+Go")) {
            ram = ram.replace("Go", " Go");
        }
        return ram;
    }

    public String getInstallPath() {
        return config.optString("installPath", System.getProperty("user.home") + System.getProperty("file.separator") + ".zokkymon");
    }

    public void setServerUrl(String url) {
        config.put("serverUrl", url);
        saveConfig();
    }

    public void setRamAllocation(String ram) {
        config.put("ram", ram);
        saveConfig();
    }

    public void setInstallPath(String path) {
        config.put("installPath", path);
        saveConfig();
    }

    public JSONObject getConfig() {
        return config;
    }

    public String getLauncherVersion() {
        // Lire depuis launcher_config.json seulement (local_version.json est pour le modpack)
        return config.optString("launcherVersion", "0.1.0");
    }

    public void setLauncherVersion(String version) {
        config.put("launcherVersion", version);
        saveConfig();
    }

    public String getLanguage() {
        String lang = config.optString("language", "fr");
        // Garantir que la langue a une valeur valide
        if (lang == null || lang.trim().isEmpty()) {
            return "fr";
        }
        return lang;
    }

    public void setLanguage(String lang) {
        config.put("language", lang);
        saveConfig();
    }

    public String getLauncherInfoUrl() {
        // If explicitly set in config, use it; otherwise fall back to serverUrl + /launcher/info.json
        String u = config.optString("launcherInfoUrl", "");
        if (u != null && !u.trim().isEmpty()) return u;
        String server = getServerUrl();
        return server + (server.endsWith("/") ? "" : "/") + "launcher/info.json";
    }

    public String getLauncherExeName() {
        return config.optString("launcherExeName", "ZokkymonLauncher.exe");
    }

    public String getModpackInfoUrl() {
        return config.optString("modpackInfoUrl", "https://zokkyen-cobblemon.ddns.net/modpack/info.json");
    }

    public String getModpackToken() {
        return config.optString("modpackToken", "");
    }

    public void setModpackToken(String token) {
        config.put("modpackToken", token);
        saveConfig();
    }

    public boolean isDarkMode() {
        return config.optBoolean("darkMode", false);
    }

    public void setDarkMode(boolean dark) {
        config.put("darkMode", dark);
        saveConfig();
    }

    public String getActiveTheme() {
        return config.optString("activeTheme", "default");
    }

    public void setActiveTheme(String themeId) {
        config.put("activeTheme", themeId);
        saveConfig();
    }

    public String getModpackVersion() {
        return config.optString("modpackVersion", "");
    }

    public void setModpackVersion(String version) {
        config.put("modpackVersion", version);
        saveConfig();
    }

    // ── MSA (Microsoft Authentication) ───────────────────────────────────────

    /** Retourne true si un profil Microsoft a été enregistré. */
    public boolean hasMsaProfile() {
        return !getMsaUsername().isEmpty() && !getMsaRefreshToken().isEmpty();
    }

    public String getMsaUsername()    { return config.optString("msaUsername",    ""); }
    public String getMsaUuid()        { return config.optString("msaUuid",        ""); }

    public String getMsaAccessToken() {
        String raw = config.optString("msaAccessToken", "");
        if (raw.isBlank()) return "";
        // Migration transparente : si la valeur est chiffrée, on déchiffre ; sinon (ancien format
        // en clair) on retourne null pour forcer une reconnexion propre.
        if (SecureStorage.looksEncrypted(raw)) {
            String decrypted = SecureStorage.decrypt(raw);
            return decrypted != null ? decrypted : "";
        }
        return ""; // valeur en clair (ancien format) → forcer re-login
    }

    public String getMsaRefreshToken() {
        String raw = config.optString("msaRefreshToken", "");
        if (raw.isBlank()) return "";
        if (SecureStorage.looksEncrypted(raw)) {
            String decrypted = SecureStorage.decrypt(raw);
            return decrypted != null ? decrypted : "";
        }
        return ""; // valeur en clair (ancien format) → forcer re-login
    }

    public long   getMsaExpiresAt()   { return config.optLong("msaExpiresAt",     0L); }

    /** Retourne le Client ID Azure lu depuis la config (champ {@code msaClientId}). */
    public String getClientId() {
        return config.optString("msaClientId", "").strip();
    }

    /** Persiste le profil complet obtenu après une authentification réussie.
     *  Les tokens sont chiffrés via {@link SecureStorage} avant d'être écrits sur disque. */
    public void saveMsaProfile(MicrosoftAuth.McProfile p) {
        config.put("msaUsername", p.username);
        config.put("msaUuid",     p.uuid);
        config.put("msaExpiresAt", p.expiresAtMs);
        try {
            config.put("msaAccessToken",  SecureStorage.encrypt(p.accessToken));
            config.put("msaRefreshToken", SecureStorage.encrypt(p.refreshToken));
        } catch (Exception e) {
            // Si le chiffrement échoue (cas très rare), on stocke quand même pour ne pas
            // bloquer la session — mais on prévient dans la console.
            System.err.println("[WARN] Impossible de chiffrer les tokens MSA : " + e.getMessage());
            config.put("msaAccessToken",  p.accessToken);
            config.put("msaRefreshToken", p.refreshToken);
        }
        saveConfig();
    }

    /** Supprime toutes les données MSA (déconnexion). */
    public void clearMsaProfile() {
        config.remove("msaUsername");
        config.remove("msaUuid");
        config.remove("msaAccessToken");
        config.remove("msaRefreshToken");
        config.remove("msaExpiresAt");
        saveConfig();
    }
}
