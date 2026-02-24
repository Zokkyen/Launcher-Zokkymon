package com.zokkymon.launcher;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Updater {
    private LauncherGUI gui;
    private ConfigManager config;

    public Updater(LauncherGUI gui, ConfigManager config) {
        this.gui = gui;
        this.config = config;
    }

    /**
     * Calcule le SHA-256 d'un fichier et retourne sa représentation hexadécimale minuscule.
     * Utilisé pour la vérification d'intégrité des téléchargements.
     */
    private String computeHash(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = is.read(buffer)) != -1) md.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private void downloadFile(String urlStr, File dest) throws IOException {
        downloadFile(urlStr, dest, null);
    }

    private void downloadFile(String urlStr, File dest, String fileName) throws IOException {
        URL url = URI.create(urlStr).toURL();
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        
        // Récupérer la taille du fichier
        long contentLength = connection.getContentLengthLong();
        
        try (InputStream in = connection.getInputStream();
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytesRead = 0;
            int lastLoggedProgress = -1;  // Pour afficher le log seulement tous les 10%
            
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                
                // Mettre à jour la barre de progression
                if (contentLength > 0) {
                    int progress = (int) ((totalBytesRead * 100) / contentLength);
                    gui.setProgress(progress);
                    
                    // Afficher le log seulement tous les 10%
                    if (fileName != null && progress >= lastLoggedProgress + 10) {
                        gui.appendLog("[*] " + fileName + ": " + progress + "%");
                        lastLoggedProgress = progress;
                    }
                }
            }
        }
    }

    private String readUrl(String urlStr) throws IOException {
        URL url = URI.create(urlStr).toURL();
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        
        // Vérifier le code HTTP si c'est une connexion HTTP/HTTPS
        if (connection instanceof HttpURLConnection) {
            HttpURLConnection httpConnection = (HttpURLConnection) connection;
            int responseCode = httpConnection.getResponseCode();
            
            if (responseCode != 200) {
                throw new IOException("Erreur HTTP " + responseCode + ": " + httpConnection.getResponseMessage());
            }
        }
        
        try (InputStream is = connection.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private String readUrlWithToken(String baseUrl, String token) throws IOException {
        String separator = baseUrl.contains("?") ? "&" : "?";
        String urlStr = baseUrl + separator + "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        return readUrl(urlStr);
    }

    // --------- Modpack update helpers ---------
    
    /**
     * Retourne l'état du modpack : "absent", "outdated", ou "uptodate"
     */
    public String getModpackStatus() {
        try {
            String token = config.getModpackToken();
            String infoUrl = config.getModpackInfoUrl();

            JSONObject serverInfo = new JSONObject(readUrlWithToken(infoUrl, token));
            String serverVer = serverInfo.optString("version", "");

            // Version installée (stockée dans launcher_config après chaque install/MAJ)
            String installedVer = config.getModpackVersion();

            // Pas encore installé
            if (installedVer == null || installedVer.isBlank()) {
                gui.appendLog("[!] Aucune version installée connue");
                return "absent";
            }

            // Vérifier que le dossier du modpack installé existe encore physiquement
            File installDir = new File(config.getInstallPath());
            File installedDir = new File(installDir, "zokkymon_v" + installedVer);
            if (!installedDir.exists()) {
                gui.appendLog("[!] Dossier modpack manquant: " + installedDir.getName());
                config.setModpackVersion("");   // réinitialiser
                return "absent";
            }

            gui.appendLog("[*] Installé: v" + installedVer + " | Serveur: v" + serverVer);

            if (!serverVer.equals(installedVer)) {
                gui.appendLog("[!] Mise à jour disponible: " + installedVer + " → " + serverVer);
                return "outdated";
            }

            gui.appendLog("[OK] Modpack à jour (v" + installedVer + ")");
            return "uptodate";
        } catch (Exception e) {
            gui.appendLog("[!] Impossible de vérifier le statut du modpack en ligne: " + e.getMessage());

            // Si on ne peut pas contacter le serveur, vérifier si le modpack existe localement
            File installDir = new File(config.getInstallPath());
            File[] modpackDirs = installDir.listFiles((d) -> d.isDirectory() && d.getName().startsWith("zokkymon_v"));

            if (modpackDirs == null || modpackDirs.length == 0) {
                gui.appendLog("[!] Aucun modpack trouvé localement");
                return "absent";
            }

            // Détecter la version depuis le nom du dossier et la sauvegarder si absente
            java.util.Arrays.sort(modpackDirs);
            String detectedVer = modpackDirs[modpackDirs.length - 1].getName().replace("zokkymon_v", "");
            if (!detectedVer.equals(config.getModpackVersion())) {
                config.setModpackVersion(detectedVer);
                gui.appendLog("[*] Version installée détectée et enregistrée: " + detectedVer);
            }
            gui.appendLog("[OK] Modpack v" + detectedVer + " présent localement");
            return "uptodate";
        }
    }
    
    public boolean isModpackUpdateAvailable() {
        String status = getModpackStatus();
        return !status.equals("uptodate");
    }

    public boolean downloadAndExtractModpack() {
        try {
            String token = config.getModpackToken();
            String infoUrl = config.getModpackInfoUrl();
            File installDir = new File(config.getInstallPath());
            installDir.mkdirs();

            gui.appendLog("[*] Telechargement des metadonnees du modpack...");
            gui.appendLog("   URL: " + infoUrl);
            try {
                JSONObject serverInfo = new JSONObject(readUrlWithToken(infoUrl, token));
                
                String version = serverInfo.optString("version", "");
                String downloadUrl = serverInfo.optString("url", "");
                String expectedHash = serverInfo.optString("sha256", "");

                gui.appendLog("Version: " + version);
                gui.appendLog("URL: " + downloadUrl);

                File modulesDir = new File(installDir, "zokkymon_v" + version);
                if (modulesDir.exists()) {
                    gui.appendLog("[OK] Modpack v" + version + " déjà présent");
                    return true;
                }

                File tempZip = new File(installDir, "modpack_temp.zip");
                
                gui.appendLog("[*] Téléchargement du modpack...");
                // Ajouter le token au lien de téléchargement
                String secureDownloadUrl = downloadUrl.contains("?") ?
                    downloadUrl + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8) :
                    downloadUrl + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
                downloadFile(secureDownloadUrl, tempZip, "modpack_v" + version + ".zip");

                if (expectedHash != null && !expectedHash.isEmpty()) {
                    gui.appendLog("Vérification SHA-256...");
                    String actualHash = computeHash(tempZip);
                    if (!actualHash.equalsIgnoreCase(expectedHash)) {
                        gui.appendLog("[ERROR] Hash invalide pour le modpack téléchargé.");
                        gui.appendLog("   Attendu   : " + expectedHash);
                        gui.appendLog("   Calculé   : " + actualHash);
                        tempZip.delete();
                        return false;
                    }
                }

                extractZip(tempZip, modulesDir);
                tempZip.delete();

                // Enregistrer la version installée dans launcher_config.json
                config.setModpackVersion(version);
                gui.appendLog("[*] Version installée enregistrée: " + version);

                gui.appendLog("[OK] Modpack v" + version + " téléchargé et extrait avec succès!");
                return true;
            } catch (Exception e) {
                gui.appendLog("[ERROR] Erreur lors du téléchargement des métadonnées:");
                gui.appendLog("   Type: " + e.getClass().getSimpleName());
                gui.appendLog("   Message: " + e.getMessage());
                if (e.getCause() != null) {
                    gui.appendLog("   Cause: " + e.getCause().getMessage());
                }
                // Afficher la stacktrace dans le journal
                for (StackTraceElement el : e.getStackTrace()) {
                    gui.appendLog("   at " + el);
                }
                return false;
            }
        } catch (Exception e) {
            gui.appendLog("[ERR] Erreur critique lors du téléchargement du modpack: " + e.getMessage());
            return false;
        }
    }

    /**
     * Extrait {@code zipFile} dans {@code extractDir} en une seule ouverture du fichier.
     * Utilise {@link ZipFile} (accès aléatoire) pour connaître le nombre d'entrées
     * immédiatement via {@code size()}, sans double passe.
     */
    private void extractZip(File zipFile, File extractDir) throws IOException {
        extractDir.mkdirs();
        try (ZipFile zf = new ZipFile(zipFile)) {
            int totalEntries    = zf.size();
            int currentEntry    = 0;
            int lastLoggedPct   = -1;
            gui.appendLog("[*] Extraction du modpack (" + totalEntries + " fichiers)...");

            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File     dest  = new File(extractDir, entry.getName());

                // Protection contre les path traversal (Zip Slip)
                if (!dest.toPath().normalize().startsWith(extractDir.toPath().normalize())) {
                    gui.appendLog("[WARN] Entrée ignorée (path traversal) : " + entry.getName());
                    currentEntry++;
                    continue;
                }

                if (entry.isDirectory()) {
                    dest.mkdirs();
                } else {
                    dest.getParentFile().mkdirs();
                    try (InputStream is = zf.getInputStream(entry)) {
                        Files.copy(is, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }

                currentEntry++;
                if (totalEntries > 0) {
                    int pct = (currentEntry * 100) / totalEntries;
                    gui.setProgress(pct);
                    if (pct >= lastLoggedPct + 10) {
                        gui.appendLog("[*] Extraction : " + pct + "%");
                        lastLoggedPct = pct;
                    }
                }
            }
        }
    }

    // --------- Launcher update helpers ---------

    public JSONObject getLauncherInfo() throws Exception {
        String infoUrl = config.getLauncherInfoUrl();
        String content = readUrl(infoUrl);
        return new JSONObject(content);
    }

    public boolean isLauncherUpdateAvailable() {
        try {
            JSONObject info = getLauncherInfo();
            String serverVer = info.optString("version", "");
            String localVer = config.getLauncherVersion();
            return (serverVer != null && !serverVer.isEmpty() && !serverVer.equals(localVer));
        } catch (Exception e) {
            gui.appendLog("Impossible de vérifier la mise à jour du launcher: " + e.getMessage());
            return false;
        }
    }

    // Silent check method for auto-startup (returns 2-part result: update available + server version)
    /**
     * Compare deux versions sémantiques de la forme "X.Y.Z".
     * Retourne > 0 si a > b, 0 si égales, < 0 si a < b.
     */
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
        try { return Integer.parseInt(part.trim()); } catch (Exception e) { return 0; }
    }

    public String[] checkLauncherUpdateSilent() {
        try {
            JSONObject info = getLauncherInfo();
            String serverVer = info.optString("version", "");
            String localVer = config.getLauncherVersion();
            // Proposer une mise à jour uniquement si la version serveur est strictement supérieure
            boolean updateAvail = (serverVer != null && !serverVer.isEmpty()
                    && compareVersions(serverVer, localVer) > 0);
            return new String[]{updateAvail ? "update" : "ok", serverVer};
        } catch (Exception e) {
            return new String[]{"error", ""};
        }
    }

    public boolean downloadAndInstallLauncher(JSONObject info) {
        try {
            String url = info.getString("url");
            String expectedHash = info.optString("sha256", "");
            File base = new File(config.getInstallPath());
            base.mkdirs();

            File downloaded = new File(base, "new_launcher.exe");
            gui.appendLog("Téléchargement du nouvel exécutable du launcher...");
            downloadFile(url, downloaded);

            if (expectedHash != null && !expectedHash.isEmpty()) {
                gui.appendLog("Vérification SHA-256...");
                String actualLauncherHash = computeHash(downloaded);
                if (!actualLauncherHash.equalsIgnoreCase(expectedHash)) {
                    gui.appendLog("[ERR] Hash invalide pour le launcher téléchargé.");
                    gui.appendLog("   Attendu : " + expectedHash);
                    gui.appendLog("   Calculé : " + actualLauncherHash);
                }
            }

            // Lancer un processus discret pour remplacer et relancer le launcher
            String exeName = config.getLauncherExeName();
            
            // Trouver le chemin du launcher EXE en cours d'exécution
            String launcherDir = System.getProperty("user.dir");
            File launcherFile = new File(launcherDir, exeName);
            
            if (!launcherFile.exists()) {
                launcherFile = new File(new File(launcherDir).getParent(), exeName);
            }
            if (!launcherFile.exists()) {
                launcherDir = new File(System.getProperty("user.dir")).getAbsolutePath();
            } else {
                launcherDir = launcherFile.getParent();
            }
            
            String newLauncherPath = new File(base, "new_launcher.exe").getAbsolutePath();
            String targetLauncherPath = new File(launcherDir, exeName).getAbsolutePath();

            // Créer un script PowerShell qui remplace et relance silencieusement
            File ps = new File(base, "update_launcher.ps1");
            StringBuilder psContent = new StringBuilder();
            psContent.append("Start-Sleep -Milliseconds 2000\n");
            psContent.append("$newExe = \"").append(newLauncherPath).append("\"\n");
            psContent.append("$target = \"").append(targetLauncherPath).append("\"\n");
            psContent.append("try {\n");
            psContent.append("  if (Test-Path $target) { Remove-Item $target -Force }\n");
            psContent.append("  Move-Item $newExe $target -Force\n");
            psContent.append("  Start-Process -FilePath $target -NoNewWindow\n");
            psContent.append("  Start-Sleep -Seconds 1\n");
            psContent.append("  Remove-Item \"").append(ps.getAbsolutePath()).append("\" -Force\n");
            psContent.append("} catch { }\n");
            
            Files.write(ps.toPath(), psContent.toString().getBytes());

            // Mettre à jour la version dans launcher_config.json avant de quitter
            // pour qu'au prochain démarrage (nouveau launcher) la comparaison indique "ok"
            String newVersion = info.optString("version", "");
            if (!newVersion.isEmpty()) {
                config.setLauncherVersion(newVersion);
                gui.appendLog("[*] Version mise à jour dans la config : " + newVersion);
            }

            // Lancer le script PowerShell discrètement
            gui.appendLog("Mise à jour en cours, fermeture du launcher...\n");
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass",
                "-File", ps.getAbsolutePath()
            );
            pb.start();

            Thread.sleep(500);
            return true;
        } catch (Exception e) {
            gui.appendLog("Erreur lors de la mise à jour du launcher: " + e.getMessage());
            return false;
        }
    }

    /**
     * Installe Fabric Loader dans le répertoire du modpack s'il n'existe pas déjà.
     * Télécharge depuis le Maven officiel de Fabric si nécessaire.
     */
    public boolean installFabricLoader(String gameDir) {
        try {
            File gameDirFile = new File(gameDir);
            String fabricVersion = config.getFabricVersion();
            
            // Chercher si Fabric Loader existe déjà
            File[] files = gameDirFile.listFiles(File::isFile);
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith("fabric-loader") && f.getName().endsWith(".jar")) {
                        gui.appendLog("[OK] Fabric Loader trouvé: " + f.getName());
                        return true;
                    }
                }
            }
            
            // Chercher dans les sous-dossiers
            File[] subdirs = gameDirFile.listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File subdir : subdirs) {
                    File[] jars = subdir.listFiles((d, name) -> name.startsWith("fabric-loader") && name.endsWith(".jar"));
                    if (jars != null && jars.length > 0) {
                        gui.appendLog("[OK] Fabric Loader trouvé: " + jars[0].getName());
                        return true;
                    }
                }
            }
            
            // Fabric Loader non trouvé, le télécharger
            gui.appendLog("[*] Installation de Fabric Loader v" + fabricVersion + "...");
            gui.setProgress(0);
            
            String downloadUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-loader/" + fabricVersion + 
                                 "/fabric-loader-" + fabricVersion + ".jar";
            File fabricJar = new File(gameDirFile, "fabric-loader-" + fabricVersion + ".jar");
            
            gui.appendLog("[*] Téléchargement depuis Maven...");
            downloadFile(downloadUrl, fabricJar, "fabric-loader-" + fabricVersion + ".jar");
            
            gui.appendLog("[OK] Fabric Loader installé avec succès!");
            gui.setProgress(100);
            return true;

        } catch (Exception e) {
            gui.appendLog("[ERR] Impossible d'installer Fabric Loader: " + e.getMessage());
            return false;
        }
    }
}
