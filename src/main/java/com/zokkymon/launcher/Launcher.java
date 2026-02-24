package com.zokkymon.launcher;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class Launcher {

    /**
     * Point d’entrée principal du lancement : prépare l’environnement (runtime Java, dépendances,
     * classpath) puis lance le client Minecraft Fabric via un {@link ProcessBuilder}.
     *
     * @param config  la configuration du launcher (RAM, version Minecraft/Fabric, langue)
     * @param gui     la fenêtre du launcher pour afficher les logs
     * @param gameDir le répertoire du modpack extrait (ex : {@code ~/.zokkymon/zokkymon_v1.0.1})
     * @throws Exception en cas d’erreur critique (Java manquant, Fabric Loader introuvable…)
     */
    public static void launchMinecraft(ConfigManager config, LauncherGUI gui, String gameDir) throws Exception {
        gui.appendLog("[*] Initialisation du runtime Java embarqué...");
        extractEmbeddedJavaRuntime(gui);
        
        String javaExe = findJavaExecutable();
        if (javaExe == null) {
            throw new Exception("Java n'est pas installé ou pas trouvé dans PATH");
        }

        // Vérification de la version Java (min. 21 requis par ce modpack)
        String javaVersion = getJavaVersion(javaExe);
        gui.appendLog("Java trouvé: " + javaExe);
        gui.appendLog("Version Java: " + (javaVersion != null ? javaVersion : "INCONNUE"));
        
        if (javaVersion == null || !isJavaVersionAcceptable(javaVersion)) {
            gui.appendLog("[WARN] Java 21+ requis pour ce modpack. Version détectée: " + (javaVersion != null ? javaVersion : "INCONNUE"));
            
            // Java 21 absent malgré l'installation automatique au démarrage
            throw new Exception(
                "ERREUR : Java 21 est requis mais n'a pas pu être installé.\n" +
                "Relancez le launcher pour retenter l'installation automatique,\n" +
                "ou installez Java 21 manuellement depuis : https://adoptium.net/"
            );
        }

        String ramAllocation = config.getRamAllocation();
        // Convertir "6 Go" en "6g" pour Java
        String javaRam = ramAllocation.replace(" Go", "g").replace(" go", "g").replace("Go", "g").replace("go", "g");
        
        String minecraftVersion = config.getMinecraftVersion();
        String fabricVersion = config.getFabricVersion();

        // Préparation du gameDir (devrait contenir le modpack extrait)
        File gameDirFile = new File(gameDir);
        if (!gameDirFile.exists()) {
            gameDirFile.mkdirs();
        }
        
        // Vérifier/créer le dossier libraries
        File librariesDir = new File(gameDirFile, "libraries");
        if (!librariesDir.exists()) {
            gui.appendLog("[*] Création du dossier libraries...");
            librariesDir.mkdirs();
        }
        
        // Vérifier/télécharger les dépendances en parallèle (gain ~1s si tout est déjà présent)
        gui.appendLog("[*] Vérification des dépendances (parallèle)...");
        final File finalLibrariesDir = librariesDir;
        final File finalGameDirFile  = gameDirFile;
        final String finalMcVersion  = minecraftVersion;
        final String finalFabricVer  = fabricVersion;
        try {
            CompletableFuture<Void> mcJarsFuture = CompletableFuture.runAsync(() -> {
                try { downloadMinecraftJarsIfMissing(finalLibrariesDir, finalMcVersion, gui); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            CompletableFuture<Void> fabricDepsFuture = CompletableFuture.runAsync(() -> {
                try { downloadFabricDependencies(finalLibrariesDir, gui); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            CompletableFuture<Void> fabricLoaderFuture = CompletableFuture.runAsync(() -> {
                try { downloadFabricLoaderIfMissing(finalGameDirFile, finalMcVersion, finalFabricVer, gui); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            CompletableFuture.allOf(mcJarsFuture, fabricDepsFuture, fabricLoaderFuture).get();
        } catch (Exception e) {
            Throwable cause = e.getCause();
            throw new Exception(cause != null ? cause.getMessage() : e.getMessage());
        }
        
        // Chercher le JAR de Fabric Loader à la racine du modpack EN PREMIER
        // (c'est la version correcte pour ce modpack)
        String fabricJar = findFabricLoaderJar(gameDirFile, minecraftVersion, fabricVersion, gui);
        if (fabricJar == null) {
            // Fallback: chercher dans libraries/
            fabricJar = findFabricLoaderJarInLibraries(librariesDir);
        }
        if (fabricJar == null) {
            throw new Exception("Fabric Loader JAR non trouvé. Assurez-vous que le modpack est bien extrait.");
        }

        gui.appendLog("Fabric Loader trouvé: " + fabricJar);
        String modsDir = new File(gameDirFile, "mods").getAbsolutePath();

        gui.appendLog("Java: " + javaExe);
        gui.appendLog("RAM: " + ramAllocation + " (Java: " + javaRam + ")");
        gui.appendLog("Répertoire du jeu: " + gameDirFile.getAbsolutePath());
        gui.appendLog("Dossier des mods: " + modsDir);
        
        // Vérifier que les dépendances de Fabric existent bien
        // Chercher n'importe quel JAR ASM disponible (9.3, 9.5, 9.8, 9.9, etc.)
        File asmDir = new File(librariesDir, "org/ow2/asm");
        File asmJar = null;
        
        if (asmDir.exists() && asmDir.isDirectory()) {
            asmJar = findFirstAsmJar(asmDir, gui);
        }
        
        if (asmJar == null) {
            throw new Exception(
                "Erreur: ASM (dépendance de Fabric Loader) manque!\n\n" +
                "Le modpack doit contenir les libraries complètes dans libraries/org/ow2/asm/\n\n" +
                "Vérifiez que vous avez bien copié le dossier libraries/ complet du modpack CurseForge."
            );
        }
        
        // ── Authentification : MSA si disponible, sinon offline ──────────────
        String userName;
        String userUUID;
        String accessToken;
        String userType;

        if (config.hasMsaProfile()) {
            userName    = config.getMsaUsername();
            userUUID    = config.getMsaUuid();
            // L'UUID stocké peut être sans tirets ; Minecraft attend le format avec tirets
            if (userUUID != null && !userUUID.contains("-") && userUUID.length() == 32) {
                userUUID = userUUID.replaceFirst(
                    "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
            }
            accessToken = config.getMsaAccessToken();
            userType    = "msa";
            gui.appendLog("[MSA] Joueur authentifié : " + userName + " | UUID: " + userUUID);
        } else {
            userName    = System.getProperty("user.name");
            // UUID déterministe basé sur le username (whitelist serveur OK en offline)
            userUUID    = java.util.UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + userName).getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ).toString();
            accessToken = "0";
            userType    = "legacy";
            gui.appendLog("[Offline] Joueur: " + userName + " | UUID: " + userUUID);
        }

        // Construire le classpath complet avec tous les JARs de libraries/
        String classpath = buildClasspath(gameDirFile, fabricJar, gui);
        String[] classpathParts = classpath.split(File.pathSeparator);
        gui.appendLog("[*] Classpath construit: " + classpathParts.length + " JARs");

        // Localiser le JAR client Minecraft — indispensable pour que Fabric applique
        // correctement les remappings Intermediary (sans ça, les Mixins crashent)
        File minecraftClientJar = new File(librariesDir,
            "net/minecraft/client/" + minecraftVersion + "/client-" + minecraftVersion + ".jar");
        if (!minecraftClientJar.exists()) {
            // Chercher tout fichier client-*.jar dans le dossier de version
            File versionDir = new File(librariesDir, "net/minecraft/client/" + minecraftVersion);
            File[] candidates = versionDir.listFiles((d, n) -> n.startsWith("client-") && n.endsWith(".jar"));
            if (candidates != null && candidates.length > 0) {
                minecraftClientJar = candidates[0];
            }
        }
        if (minecraftClientJar.exists()) {
            gui.appendLog("[*] Game JAR: " + minecraftClientJar.getName());
        } else {
            gui.appendLog("[WARN] client JAR non trouvé — les Mixins risquent de crasher");
        }

        // Résoudre l'id de l'assetIndex (ex: "17" pour 1.21.1, pas "1.21.1")
        String assetIndexId = getAssetIndexId(minecraftVersion);
        gui.appendLog("[*] AssetIndex id : " + assetIndexId);

        ProcessBuilder pb = new ProcessBuilder(
            javaExe,
            "-Xmx" + javaRam,
            "-Xms1G",
            "-Dfile.encoding=UTF-8",
            "-Dfabric.development=false",
            "-DFabricMcEmu= net.minecraft.client.main.Main",
            "-Dwaila.allowUnsupportedPlatforms=true",
            "-Djava.library.path=" + new File(gameDirFile, "natives").getAbsolutePath(),
            "-cp", classpath,
            "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "--gameDir", gameDirFile.getAbsolutePath(),
            "--gameJar", minecraftClientJar.getAbsolutePath(),
            "--username", userName,
            "--uuid", userUUID,
            "--accessToken", accessToken,
            "--userType", userType,
            "--versionType", "release",
            "--assetIndex", assetIndexId,
            "--assetsDir", resolveAssetsDir(gameDirFile, assetIndexId, gui),
            "--version", minecraftVersion
        );
        // Récupérer le chemin résolu depuis la commande pour l'utiliser dans ensureLanguageAssets
        List<String> cmd = pb.command();
        String resolvedAssetsPath = cmd.get(cmd.indexOf("--assetsDir") + 1);

        pb.directory(gameDirFile);

        // Nettoyer .fabric/remappedJars avant chaque lancement :
        // Fabric échoue si ce dossier contient des fichiers partiels d'une session précédente.
        File remappedJarsDir = new File(gameDirFile, ".fabric" + File.separator + "remappedJars");
        if (remappedJarsDir.exists()) {
            gui.appendLog("[*] Nettoyage du cache Fabric (remappedJars)...");
            deleteDir(remappedJarsDir);
        }

        // Assurer que les assets de langue sont présents dans le dossier assets résolu
        ensureLanguageAssets(new File(resolvedAssetsPath), assetIndexId, gui);

        // Désactiver les layouts FancyMenu qui écrasent l'écran de langue
        disableFancyMenuLanguageLayouts(gameDirFile, gui);

        // Appliquer la langue dans options.txt (Minecraft lit ce fichier, pas -Duser.language)
        ensureOptionsLanguage(gameDirFile);
        gui.appendLog("[*] Langue appliquée : fr_fr dans options.txt");

        // Rediriger STDERR vers STDOUT pour capturer les logs
        pb.redirectErrorStream(true);

        gui.appendLog("Lancement du jeu...");
        Process process = pb.start();
        
        // Lire et afficher la sortie du processus
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                gui.appendLog("[MC] " + line);
            }
        }
        
        int exitCode = process.waitFor();
        gui.appendLog("[MC] Processus terminé avec le code: " + exitCode);
    }

    // URL de téléchargement officielle Eclipse Temurin 21.0.4 (Windows x64 JRE)
    private static final String JRE_DOWNLOAD_URL =
        "https://github.com/adoptium/temurin21-binaries/releases/download/" +
        "jdk-21.0.4%2B7/OpenJDK21U-jre_x64_windows_hotspot_21.0.4_7.zip";
    // Nom du dossier racine tel qu'il apparaît dans le ZIP Temurin
    private static final String JRE_ZIP_ROOT = "jdk-21.0.4+7-jre";

    /**
     * S'assure que Jre_21 est présent dans ~/.zokkymon/.
     * <ul>
     *   <li>Si déjà présent → rien à faire.</li>
     *   <li>Sinon → télécharge le ZIP Temurin 21.0.4 depuis GitHub Releases,
     *       l'extrait, renomme le dossier racine en {@code Jre_21}.</li>
     * </ul>
     */
    private static void extractEmbeddedJavaRuntime(LauncherGUI gui) {
        String userHome = System.getProperty("user.home");
        File zokkymonDir = new File(userHome, ".zokkymon");
        zokkymonDir.mkdirs();

        File jre21Dir = new File(zokkymonDir, "Jre_21");
        if (jre21Dir.exists()) {
            gui.appendLog("[OK] Jre_21 déjà présent");
            return;
        }

        gui.appendLog("[*] Java 21 (Temurin) non trouvé — téléchargement en cours...");
        gui.appendLog("[*] Source : Eclipse Adoptium (officiel)");
        gui.setProgress(0);

        File zipFile = new File(zokkymonDir, "jre21_tmp.zip");
        try {
            // Téléchargement
            downloadFileWithProgress(JRE_DOWNLOAD_URL, zipFile.getAbsolutePath(), gui);
            gui.appendLog("[OK] Téléchargement terminé");
            gui.setProgress(50);

            // Extraction dans un dossier temporaire
            File extractTmp = new File(zokkymonDir, "jre21_extract_tmp");
            extractZip(zipFile.getAbsolutePath(), extractTmp.getAbsolutePath(), gui);

            // Le ZIP Temurin contient un dossier racine (ex: jdk-21.0.4+7-jre)
            // On le renomme en Jre_21 pour correspondre à findJavaExecutable()
            File extracted = new File(extractTmp, JRE_ZIP_ROOT);
            if (!extracted.exists()) {
                // Fallback : prendre le premier sous-dossier trouvé
                File[] children = extractTmp.listFiles(File::isDirectory);
                if (children != null && children.length > 0) extracted = children[0];
            }
            if (extracted != null && extracted.exists()) {
                extracted.renameTo(jre21Dir);
                gui.appendLog("[OK] Jre_21 installé dans " + jre21Dir.getAbsolutePath());
            } else {
                // Extraction plate (pas de sous-dossier) : on déplace directement
                extractTmp.renameTo(jre21Dir);
                gui.appendLog("[OK] Jre_21 installé dans " + jre21Dir.getAbsolutePath());
            }

            // Nettoyage
            if (extractTmp.exists()) deleteDir(extractTmp);
            gui.setProgress(100);

        } catch (Exception e) {
            gui.appendLog("[ERR] Impossible d'installer Java 21 : " + e.getMessage());
            gui.appendLog("[!] Le jeu ne pourra pas démarrer sans Java 21.");
        } finally {
            if (zipFile.exists()) zipFile.delete();
        }
    }

    /**
     * Résout le dossier assets à passer à Minecraft.
     *
     * <p>Priorité :
     * <ol>
     *   <li>assets/ du modpack si l'index {@code <version>.json} existe (assets complets)</li>
     *   <li>assets/ du launcher Minecraft officiel ({@code %APPDATA%/.minecraft/assets}) si l'index y est présent</li>
     *   <li>assets/ du modpack en dernier recours (Minecraft le remplira lui-même)</li>
     * </ol>
     */
    private static String resolveAssetsDir(File gameDir, String assetIndexId, LauncherGUI gui) {
        String sep = File.separator;

        // ── Chercher le dossier assets complet dans les emplacements standard ──
        // CurseForge et le launcher officiel Mojang partagent ~/.minecraft/assets
        // qui est déjà entièrement téléchargé — pas besoin de re-télécharger.
        String appData  = System.getenv("APPDATA");
        String localApp = System.getenv("LOCALAPPDATA");
        String home     = System.getProperty("user.home");

        java.util.List<File> candidates = new java.util.ArrayList<>();
        if (appData  != null) candidates.add(new File(appData,  ".minecraft"  + sep + "assets"));
        if (localApp != null) candidates.add(new File(localApp, "CurseForge" + sep + "minecraft" + sep + "Install" + sep + "assets"));
        if (home     != null) candidates.add(new File(home,     ".minecraft"  + sep + "assets"));
        // Dossier local du modpack en dernier recours
        candidates.add(new File(gameDir, "assets"));

        for (File assetsDir : candidates) {
            File index = new File(assetsDir, "indexes" + sep + assetIndexId + ".json");
            if (index.exists() && index.length() > 100_000) { // index complet ~400 KB
                // Vérifier qu'il y a bien des objets téléchargés
                File objects = new File(assetsDir, "objects");
                if (objects.isDirectory() && objects.list() != null && objects.list().length > 0) {
                    gui.appendLog("[*] Assets : " + assetsDir.getAbsolutePath()
                        + " (index " + assetIndexId + ".json, " + (index.length() / 1024) + " KB)");
                    return assetsDir.getAbsolutePath();
                }
            }
        }

        // Aucun dossier complet trouvé → utiliser gameDir/assets et laisser ensureLanguageAssets télécharger
        File fallback = new File(gameDir, "assets");
        File fallbackIndex = new File(fallback, "indexes" + sep + assetIndexId + ".json");
        gui.appendLog("[WARN] Aucun dossier assets complet trouvé — utilisation de " + fallback.getAbsolutePath());
        if (!fallbackIndex.exists() || fallbackIndex.length() < 100) {
            gui.appendLog("[WARN] Index assets absent (" + assetIndexId + ".json) — téléchargement en cours...");
        }
        return fallback.getAbsolutePath();
    }

    /**
     * Retourne l'id de l'assetIndex pour une version Minecraft donnée.
     * Cet id est différent de la version MC (ex: 1.21.1 → "17").
     */
    private static String getAssetIndexId(String mcVersion) {
        // Table de correspondance versions MC → id assetIndex
        switch (mcVersion) {
            case "1.21.4": return "17";
            case "1.21.3": return "17";
            case "1.21.2": return "17";
            case "1.21.1": return "17";
            case "1.21":   return "17";
            case "1.20.6": return "16";
            case "1.20.4": return "16";
            case "1.20.2": return "16";
            case "1.20.1": return "16";
            case "1.20":   return "16";
            default:       return mcVersion; // fallback : utiliser la version telle quelle
        }
    }

    /**
     * Télécharge l'index des assets Minecraft et les fichiers de langue
     * ({@code minecraft/lang/*.json}) s'ils sont absents du modpack.
     *
     * <p>Seuls les fichiers de langue sont téléchargés (~5 Mo pour toutes les langues),
     * pas les sons ni les textures.</p>
     */
    private static void ensureLanguageAssets(File assetsDir, String assetIndexId, LauncherGUI gui) {
        File indexesDir = new File(assetsDir, "indexes");
        File indexFile  = new File(indexesDir, assetIndexId + ".json");
        File objectsDir = new File(assetsDir, "objects");

        // Si le dossier assets est déjà complet (index étendu + objects présents), rien à faire
        if (indexFile.exists() && indexFile.length() > 100_000
                && objectsDir.isDirectory() && objectsDir.list() != null && objectsDir.list().length > 10) {
            gui.appendLog("[OK] Assets de langue déjà présents dans " + assetsDir.getAbsolutePath());
            return;
        }

        // ── 1. Télécharger l'index si absent ──────────────────────────────────
        if (!indexFile.exists() || indexFile.length() < 100) {
            gui.appendLog("[*] Téléchargement de l'index assets " + assetIndexId + "...");
            indexesDir.mkdirs();
            try {
                String indexUrl = fetchAssetIndexUrl(assetIndexId, gui);
                if (indexUrl == null) {
                    gui.appendLog("[WARN] URL de l'index assets introuvable — langues ignorées");
                    return;
                }
                downloadDependency(indexUrl, indexFile, 10000, 30000);
                gui.appendLog("[OK] Index assets téléchargé (" + (indexFile.length() / 1024) + " KB)");
            } catch (Exception e) {
                gui.appendLog("[WARN] Impossible de télécharger l'index assets : " + e.getMessage());
                return;
            }
        }

        // ── 2. Parser l'index et télécharger les objets de langue manquants ───
        try {
            String indexContent = new String(Files.readAllBytes(indexFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

            // Extraire toutes les entrées minecraft/lang/
            // Format dans le JSON : "minecraft/lang/fr_fr.json":{"hash":"abc123...","size":12345}
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "\"minecraft/lang/[^\"]+\"\\s*:\\s*\\{[^}]*\"hash\"\\s*:\\s*\"([a-f0-9]{40})\"");
            java.util.regex.Matcher m = pat.matcher(indexContent);

            int total = 0, downloaded = 0, alreadyPresent = 0;
            while (m.find()) {
                total++;
                String hash    = m.group(1);
                String subDir  = hash.substring(0, 2);
                File   objDir  = new File(objectsDir, subDir);
                File   objFile = new File(objDir, hash);

                if (objFile.exists() && objFile.length() > 0) { alreadyPresent++; continue; }

                objDir.mkdirs();
                String url = "https://resources.download.minecraft.net/" + subDir + "/" + hash;
                try {
                    downloadDependency(url, objFile, 10000, 30000);
                    downloaded++;
                } catch (Exception e) {
                    gui.appendLog("[WARN] Échec téléchargement objet langue " + hash.substring(0,8) + "... : " + e.getMessage());
                }
            }

            if (total == 0) {
                gui.appendLog("[WARN] Aucune entrée minecraft/lang/ trouvée dans " + indexFile.getName() + " — vérifier le fichier");
            } else if (downloaded > 0) {
                gui.appendLog("[OK] Langue : " + downloaded + " fichiers téléchargés, " + alreadyPresent + " déjà présents / " + total + " total");
            } else {
                gui.appendLog("[OK] Langue : " + total + " fichiers déjà présents dans assets/objects/");
            }
        } catch (Exception e) {
            gui.appendLog("[WARN] Erreur lors du chargement des assets de langue : " + e.getMessage());
        }
    }

    /**
     * Récupère l'URL de l'index des assets depuis le version manifest Mojang.
     * L'assetIndexId est l'id retourné par getAssetIndexId() (ex: "17" pour 1.21.x).
     */
    private static String fetchAssetIndexUrl(String assetIndexId, LauncherGUI gui) {
        // Pas de hash hardcodé — on passe par le manifest pour avoir toujours une URL valide
        // Le manifest v2 est compact et fiable
        final String fallbackManifest = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
        try {
            // Étape 1 : télécharger le manifest pour trouver la version MC correspondant à l'assetIndex
            // On cherche la première version MC qui utilise cet assetIndex id
            URL manifestUrl = URI.create(fallbackManifest).toURL();
            URLConnection conn = manifestUrl.openConnection();
            conn.setConnectTimeout(10000); conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "ZokkymonLauncher/1.0");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String l; while ((l = br.readLine()) != null) sb.append(l);
            }
            String manifest = sb.toString();

            // Le manifest v2 liste toutes les versions. On prend la première "release" qui a
            // un champ "assetIndex" correspondant à notre id — mais le manifest v2 ne contient
            // pas directement l'assetIndex. On va donc trouver l'URL de la version MC 1.21.1
            // en cherchant la version avec id "1.21.1" (ou toute version connue pour cet assetId).
            // Map assetIndexId → version MC de référence pour ce lookup
            String refMcVersion;
            switch (assetIndexId) {
                case "17": refMcVersion = "1.21.1"; break;
                case "16": refMcVersion = "1.20.4"; break;
                default:   refMcVersion = assetIndexId;
            }

            String versionKey = "\"id\":\"" + refMcVersion + "\"";
            int idx = manifest.indexOf(versionKey);
            if (idx == -1) {
                gui.appendLog("[WARN] Version " + refMcVersion + " introuvable dans le manifest");
                return null;
            }
            // "url" se trouve dans les ~250 chars qui suivent l'"id"
            String chunk = manifest.substring(idx, Math.min(idx + 250, manifest.length()));
            int urlIdx = chunk.indexOf("\"url\":\"");
            if (urlIdx == -1) return null;
            urlIdx += 7;
            String versionJsonUrl = chunk.substring(urlIdx, chunk.indexOf("\"", urlIdx));

            // Étape 2 : télécharger le JSON de version pour trouver l'URL de l'assetIndex
            URL vUrl = URI.create(versionJsonUrl).toURL();
            URLConnection vConn = vUrl.openConnection();
            vConn.setConnectTimeout(10000); vConn.setReadTimeout(15000);
            vConn.setRequestProperty("User-Agent", "ZokkymonLauncher/1.0");
            StringBuilder vSb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(vConn.getInputStream()))) {
                String l; while ((l = br.readLine()) != null) vSb.append(l);
            }
            String vJson = vSb.toString();

            // "assetIndex":{"id":"17","sha1":"...","size":...,"url":"https://..."}
            int aiIdx = vJson.indexOf("\"assetIndex\"");
            if (aiIdx == -1) return null;
            String aiChunk = vJson.substring(aiIdx, Math.min(aiIdx + 400, vJson.length()));
            int aiUrlIdx = aiChunk.indexOf("\"url\":\"");
            if (aiUrlIdx == -1) return null;
            aiUrlIdx += 7;
            String assetUrl = aiChunk.substring(aiUrlIdx, aiChunk.indexOf("\"", aiUrlIdx));
            gui.appendLog("[*] URL index assets trouvée");
            return assetUrl;
        } catch (Exception e) {
            gui.appendLog("[WARN] fetchAssetIndexUrl : " + e.getMessage());
            return null;
        }
    }

    /**
     * Écrit (ou met à jour) {@code lang:fr_fr} dans {@code options.txt} du modpack.
     * Minecraft lit ce fichier au démarrage pour appliquer la langue.
     */
    private static void ensureOptionsLanguage(File gameDir) {
        File optionsFile = new File(gameDir, "options.txt");
        try {
            List<String> lines = new ArrayList<>();
            boolean langFound = false;
            if (optionsFile.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(optionsFile))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("lang:")) {
                            lines.add("lang:fr_fr");
                            langFound = true;
                        } else {
                            lines.add(line);
                        }
                    }
                }
            }
            if (!langFound) {
                lines.add("lang:fr_fr");
            }
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(optionsFile))) {
                for (String line : lines) {
                    bw.write(line);
                    bw.newLine();
                }
            }
        } catch (Exception ignored) {
            // Non critique
        }
    }

    /**
     * Désactive les layouts FancyMenu qui écrasent l'écran de sélection de langue
     * en les renommant en .disabled (réversible, sans rien supprimer définitivement).
     * FancyMenu ne charge pas les fichiers avec une extension autre que .txt.
     */
    private static void disableFancyMenuLanguageLayouts(File gameDir, LauncherGUI gui) {
        File layoutsDir = new File(gameDir, "config" + File.separator + "fancymenu" + File.separator + "layouts");
        if (!layoutsDir.isDirectory()) return;

        // Mots-clés identifiant les écrans problématiques
        String[] targets = {"language", "language_select", "options_screen", "options"};

        File[] files = layoutsDir.listFiles();
        if (files == null) return;

        for (File f : files) {
            String name = f.getName().toLowerCase();
            // Ne traiter que les fichiers .txt actifs
            if (!name.endsWith(".txt")) continue;

            // Lire le contenu pour détecter si ce layout cible l'écran de langue
            boolean isLanguageLayout = false;
            for (String kw : targets) {
                if (name.contains(kw)) { isLanguageLayout = true; break; }
            }
            if (!isLanguageLayout) {
                // Chercher dans le contenu du fichier
                try {
                    String content = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
                    for (String kw : targets) {
                        if (content.contains(kw)) { isLanguageLayout = true; break; }
                    }
                } catch (Exception ignored) {}
            }

            if (isLanguageLayout) {
                File disabled = new File(f.getParent(), f.getName() + ".disabled");
                if (f.renameTo(disabled)) {
                    gui.appendLog("[*] FancyMenu : layout désactivé → " + f.getName());
                } else {
                    gui.appendLog("[WARN] FancyMenu : impossible de désactiver " + f.getName());
                }
            }
        }
    }

    /** Suppression récursive d'un dossier. */
    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        dir.delete();
    }

    /**
     * Télécharge un fichier avec barre de progression
     */
    private static void downloadFileWithProgress(String urlString, String outputPath, LauncherGUI gui) throws Exception {
        URL url = URI.create(urlString).toURL();
        URLConnection conn = url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        long contentLength = conn.getContentLengthLong();

        try (java.io.InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(outputPath)) {
            byte[] buffer = new byte[8192];
            int read;
            long downloadedBytes = 0;
            long lastLogTime = System.currentTimeMillis();
            while ((read = in.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                downloadedBytes += read;
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 1000) {
                    lastLogTime = now;
                    if (contentLength > 0) {
                        int percent = (int) ((downloadedBytes * 100) / contentLength);
                        long sizeMB = downloadedBytes / 1024 / 1024;
                        long totalMB = contentLength / 1024 / 1024;
                        gui.appendLog("[*] Téléchargement: " + percent + "% (" + sizeMB + "MB / " + totalMB + "MB)");
                    }
                }
            }
        }
    }

    /**
     * Extrait un fichier ZIP (simplifié)
     */
    private static void extractZip(String zipPath, String destPath, LauncherGUI gui) throws Exception {
        File destDir = new File(destPath);
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        
        gui.appendLog("[*] Extraction: " + zipPath + " -> " + destPath);
        
        String osName = System.getProperty("os.name").toLowerCase();
        ProcessBuilder pb;
        
        if (osName.contains("win")) {
            // Windows: utiliser PowerShell
            pb = new ProcessBuilder(
                "powershell",
                "-Command",
                "Expand-Archive -Path '" + zipPath + "' -DestinationPath '" + destPath + "' -Force"
            );
        } else {
            // Linux/Mac: utiliser unzip
            pb = new ProcessBuilder("unzip", "-o", zipPath, "-d", destPath);
        }
        
        pb.redirectErrorStream(true);
        Process p = pb.start();
        
        // Lire la sortie
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.trim().isEmpty()) {
                gui.appendLog("[ZIP] " + line);
            }
        }
        
        int exitCode = p.waitFor();
        
        if (exitCode != 0) {
            throw new Exception("Erreur extraction ZIP (code: " + exitCode + ")");
        }
    }

    /**
     * Vérifie et télécharge les JARs Minecraft manquants (client JAR)
     * Nécessaire car le modpack ne contient que les mods, pas le client Minecraft lui-même
     */
    private static void downloadMinecraftJarsIfMissing(File librariesDir, String minecraftVersion, LauncherGUI gui) throws Exception {
        File minecraftClientDir = new File(librariesDir, "net/minecraft/client/" + minecraftVersion);
        File[] jarFiles = minecraftClientDir.listFiles((d, name) -> name.startsWith("client-") && name.endsWith(".jar"));
        
        if (minecraftClientDir.exists() && jarFiles != null && jarFiles.length > 0) {
            boolean hasValidJar = false;
            for (File jar : jarFiles) {
                if (jar.length() > 1000000) { // > 1 MB = valide
                    hasValidJar = true;
                    gui.appendLog("[OK] Client Minecraft trouvé: " + jar.getName() + " (" + (jar.length() / 1024 / 1024) + " MB)");
                    break;
                }
            }
            if (hasValidJar) {
                return; // JARs OK, pas besoin de télécharger/copier
            }
        }
        
        // JAR manquant ou vide, essayer de copier depuis les ressources d'abord
        gui.appendLog("[*] Client Minecraft " + minecraftVersion + " manquant ou invalide...");
        
        // Créer le répertoire s'il n'existe pas
        if (!minecraftClientDir.exists()) {
            minecraftClientDir.mkdirs();
        }
        
        // Essayer de charger le JAR depuis les ressources du launcher
        String resourcePath = "/minecraft/" + minecraftVersion + ".jar";
        InputStream resourceStream = Launcher.class.getResourceAsStream(resourcePath);
        
        if (resourceStream != null) {
            gui.appendLog("[*] Copie du JAR depuis les ressources du launcher...");
            
            String jarFileName = "client-" + minecraftVersion + ".jar";
            File clientJarFile = new File(minecraftClientDir, jarFileName);
            
            // Copier le fichier depuis les ressources
            FileOutputStream fos = new FileOutputStream(clientJarFile);
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytes = 0;
            long lastLogTime = System.currentTimeMillis();
            
            while ((bytesRead = resourceStream.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
                
                // Log progress tous les 1 seconde
                long now = System.currentTimeMillis();
                if (now - lastLogTime >= 1000) {
                    gui.appendLog("[*] Copie en cours: " + (totalBytes / 1024 / 1024) + " MB");
                    lastLogTime = now;
                }
            }
            
            fos.close();
            resourceStream.close();
            
            gui.appendLog("[OK] JAR copié depuis les ressources (" + (totalBytes / 1024 / 1024) + " MB)");
            return;
        }
        
        // Si pas de ressource, essayer de télécharger depuis Mojang
        gui.appendLog("[*] Ressource locale non disponible, tentative de téléchargement...");
        
        String minecraftJarUrl = getMinecraftClientJarUrl(minecraftVersion, gui);
        
        if (minecraftJarUrl == null || minecraftJarUrl.isEmpty()) {
            throw new Exception(
                "Impossible de trouver le client Minecraft " + minecraftVersion + "\n\n" +
                "Solution recommandée:\n" +
                "Placer le fichier 1.21.1.jar dans src/main/resources/minecraft/ puis recompiler le launcher"
            );
        }
        
        // Télécharger le JAR
        String jarFileName = "client-" + minecraftVersion + ".jar";
        File clientJarFile = new File(minecraftClientDir, jarFileName);
        
        gui.appendLog("[*] URL: " + minecraftJarUrl);
        gui.appendLog("[*] Destination: " + clientJarFile.getAbsolutePath());
        gui.appendLog("[*] Téléchargement (cela peut prendre 1-2 minutes)...");
        
        try {
            downloadFileWithProgress(minecraftJarUrl, clientJarFile.getAbsolutePath(), gui);
            gui.appendLog("[OK] Client Minecraft téléchargé avec succès!");
        } catch (Exception e) {
            clientJarFile.delete(); // Nettoyer le JAR corrompu
            throw new Exception("Erreur téléchargement client Minecraft: " + e.getMessage());
        }
    }
    
    /**
     * Récupère l'URL du JAR client Minecraft depuis le version manifest de Mojang
     */
    private static String getMinecraftClientJarUrl(String version, LauncherGUI gui) throws Exception {
        String versionManifestUrl = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
        
        try {
            String line;  // Déclarer la variable avant de l'utiliser
            
            gui.appendLog("[*] Récupération du manifest Mojang pour version " + version + "...");
            
            String versionData = null;
            
            // Essayer d'abord de charger le JSON depuis les ressources locales
            String resourcePath = "/minecraft/" + version + ".json";
            try {
                InputStream resourceStream = Launcher.class.getResourceAsStream(resourcePath);
                if (resourceStream != null) {
                    gui.appendLog("[*] Fichier JSON trouvé dans les ressources locales");
                    BufferedReader resourceReader = new BufferedReader(new InputStreamReader(resourceStream));
                    StringBuilder resourceJson = new StringBuilder();
                    while ((line = resourceReader.readLine()) != null) {
                        resourceJson.append(line);
                    }
                    resourceReader.close();
                    versionData = resourceJson.toString();
                }
            } catch (Exception e) {
                // Ressource locale non disponible, on continue vers le téléchargement
            }
            
            // Si pas trouvé localement, essayer de récupérer depuis Mojang
            if (versionData == null) {
                gui.appendLog("[*] Téléchargement du manifest Mojang...");
                
                // Télécharger le manifest JSON
                URL url = URI.create(versionManifestUrl).toURL();
                URLConnection conn = url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder jsonResponse = new StringBuilder();
                
                while ((line = reader.readLine()) != null) {
                    jsonResponse.append(line);
                }
                reader.close();
                
                String json = jsonResponse.toString();
                // Parser JSON pour trouver l'URL de la version
                String versionKey = "\"id\":\"" + version + "\"";
                int versionIndex = json.indexOf(versionKey);
                String versionJsonUrl = null;
                
                if (versionIndex == -1) {
                    gui.appendLog("[WARN] Version " + version + " non trouvée dans le manifest récent");
                    // Utiliser URL hardcodée pour 1.21.1
                    if ("1.21.1".equals(version)) {
                        versionJsonUrl = "https://piston-meta.mojang.com/v1/packages/550bf292a202ba3f82181e04672e6e4d598d45ea/1.21.1.json";
                    } else {
                        gui.appendLog("[ERROR] Version " + version + " non supportée");
                        return null;
                    }
                } else {
                    // Chercher l'URL dans la même entrée de version
                    int objectStart = json.lastIndexOf("{", versionIndex);
                    int objectEnd = json.indexOf("}", versionIndex);
                    
                    if (objectStart != -1 && objectEnd != -1) {
                        String versionObject = json.substring(objectStart, objectEnd + 1);
                        int urlIndex = versionObject.indexOf("\"url\":\"");
                        if (urlIndex != -1) {
                            int urlStart = urlIndex + 8;
                            int urlEndIndex = versionObject.indexOf("\"", urlStart);
                            if (urlEndIndex != -1) {
                                versionJsonUrl = versionObject.substring(urlStart, urlEndIndex);
                            }
                        }
                    }
                }
                
                if (versionJsonUrl == null) {
                    gui.appendLog("[ERROR] Impossible de trouver l'URL JSON pour la version " + version);
                    return null;
                }
                
                gui.appendLog("[*] URL de version trouvée, récupération du JSON...");
                
                // Récupérer le JSON de la version
                URL versionUrl = URI.create(versionJsonUrl).toURL();
                URLConnection versionConn = versionUrl.openConnection();
                versionConn.setConnectTimeout(5000);
                versionConn.setReadTimeout(5000);
                
                BufferedReader versionReader = new BufferedReader(new InputStreamReader(versionConn.getInputStream()));
                StringBuilder versionJson = new StringBuilder();
                
                while ((line = versionReader.readLine()) != null) {
                    versionJson.append(line);
                }
                versionReader.close();
                
                versionData = versionJson.toString();
            }
            
            // Parser le JSON pour trouver l'URL du client
            // Format: "downloads":{"client":{"sha1":"...","size":...,"url":"https://..."}}
            int clientIndex = versionData.indexOf("\"client\"");
            if (clientIndex != -1) {
                int clientUrlIndex = versionData.indexOf("\"url\":\"", clientIndex);
                if (clientUrlIndex != -1) {
                    int clientUrlStart = clientUrlIndex + 8;
                    int clientUrlEnd = versionData.indexOf("\"", clientUrlStart);
                    if (clientUrlEnd != -1) {
                        String clientUrl = versionData.substring(clientUrlStart, clientUrlEnd);
                        gui.appendLog("[OK] URL du client trouvée: " + clientUrl);
                        return clientUrl;
                    }
                } else {
                    // Fallback: reconstruire l'URL depuis le sha1
                    int sha1Index = versionData.indexOf("\"sha1\":\"", clientIndex);
                    if (sha1Index != -1) {
                        int sha1Start = sha1Index + 9;
                        int sha1End = versionData.indexOf("\"", sha1Start);
                        if (sha1End != -1) {
                            String clientHash = versionData.substring(sha1Start, sha1End);
                            String clientUrl = "https://piston-data.mojang.com/v1/objects/" +
                                             clientHash + "/client.jar";
                            gui.appendLog("[OK] URL du client trouvée via sha1");
                            return clientUrl;
                        }
                    }
                }
            } else {
                gui.appendLog("[WARN] Section 'client' non trouvée dans le JSON de version");
            }
        } catch (Exception e) {
            gui.appendLog("[WARN] Erreur récupération manifest: " + e.getMessage());
            e.printStackTrace();
        }
        
        return null;
    }

    /**
     * Télécharge et installe Fabric Loader si manquant
     */
    private static void downloadFabricDependencies(File librariesDir, LauncherGUI gui) throws Exception {
        // Chercher d'abord si les dépendances ASM existent DÉJÀ dans libraries/ (peu importe la version)
        gui.appendLog("[*] Vérification des dépendances ASM existantes...");
        
        File asmBaseDir = new File(librariesDir, "org/ow2/asm");
        boolean hasAsmDependencies = false;
        
        if (asmBaseDir.exists()) {
            // Chercher tous les fichiers asm*.jar
            File[] allAsmDirs = asmBaseDir.listFiles(File::isDirectory);
            if (allAsmDirs != null && allAsmDirs.length > 0) {
                for (File componentDir : allAsmDirs) {
                    File[] versions = componentDir.listFiles(File::isDirectory);
                    if (versions != null && versions.length > 0) {
                        // Au moins une version ASM existe
                        hasAsmDependencies = true;
                        gui.appendLog("[OK] Dépendances ASM trouvées dans le modpack (" + componentDir.getName() + ")");
                    }
                }
            }
        }
        
        // Si les ASM existent, ne pas essayer de les télécharger (probablement une version différente de 9.5)
        if (hasAsmDependencies) {
            gui.appendLog("[*] Les dépendances ASM sont disponibles dans le modpack");
            return;
        }
        
        // Si ASM n'existe pas, essayer de télécharger les dépendances minimales
        gui.appendLog("[*] Dépendances ASM non trouvées, tentative de téléchargement...");
        
        // Dépendances minimum de Fabric Loader 0.17.3 depuis Maven Central
        String[][] dependencies = {
            // ASM (obligatoire)
            {"org/ow2/asm/asm/9.5", "asm-9.5.jar"},
            {"org/ow2/asm/asm-commons/9.5", "asm-commons-9.5.jar"},
            {"org/ow2/asm/asm-tree/9.5", "asm-tree-9.5.jar"},
            {"org/ow2/asm/asm-analysis/9.5", "asm-analysis-9.5.jar"},
            {"org/ow2/asm/asm-util/9.5", "asm-util-9.5.jar"},
            // JSON (obligatoire)
            {"com/google/code/gson/gson/2.8.9", "gson-2.8.9.jar"},
            // SLF4J (obligatoire)
            {"org/slf4j/slf4j-api/1.7.36", "slf4j-api-1.7.36.jar"},
            {"org/slf4j/slf4j-simple/1.7.36", "slf4j-simple-1.7.36.jar"},
        };
        
        // Essayer plusieurs sources Maven en fallback
        String[] mavenUrls = {
            "https://repo1.maven.org/maven2/",      // Maven Central (le plus stable)
            "https://maven.fabricmc.net/",          // Fabric Maven
            "https://maven.fabric.net/",            // Alternative Fabric
        };
        
        int count = 0;
        
        for (String[] dep : dependencies) {
            String path = dep[0];
            String jarName = dep[1];
            
            File depDir = new File(librariesDir, path);
            File jarFile = new File(depDir, jarName);
            
            if (jarFile.exists()) {
                gui.appendLog("[OK] " + jarName + " déjà présent");
                count++;
                continue;
            }
            
            gui.appendLog("[*] Téléchargement: " + jarName);
            boolean success = false;
            
            for (String mavenUrl : mavenUrls) {
                String downloadUrl = mavenUrl + path + "/" + jarName;
                try {
                    depDir.mkdirs();
                    downloadDependency(downloadUrl, jarFile, 30000, 30000); // 30s timeout
                    gui.appendLog("[OK] " + jarName + " téléchargé");
                    success = true;
                    count++;
                    break;
                } catch (Exception e) {
                    gui.appendLog("[RETRY] " + mavenUrl + " timeout, essai suivant...");
                }
            }
            
            if (!success) {
                gui.appendLog("[WARN] Impossible de télécharger " + jarName + " (vérifie ta connexion internet)");
            }
        }
        
        if (count == 0 && !hasAsmDependencies) {
            gui.appendLog("[WARN] Aucune dépendance téléchargée");
        } else {
            gui.appendLog("[OK] " + count + "/" + dependencies.length + " dépendances téléchargées");
        }
    }
    
    /**
     * Télécharge Fabric Loader à la racine du modpack si manquant
     */
    private static void downloadFabricLoaderIfMissing(File gameDir, String minecraftVersion, String fabricVersion, LauncherGUI gui) throws Exception {
        // Chercher si le Fabric Loader portant existe déjà
        File[] fabricJars = gameDir.listFiles((d, name) -> 
            name.startsWith("fabric-loader") && name.endsWith(".jar")
        );
        
        if (fabricJars != null && fabricJars.length > 0) {
            for (File jar : fabricJars) {
                if (jar.getName().contains(fabricVersion)) {
                    gui.appendLog("[OK] Fabric Loader " + fabricVersion + " trouvé: " + jar.getName());
                    return;
                }
            }
        }
        
        // Fabric Loader manquant, le télécharger
        gui.appendLog("[*] Fabric Loader " + fabricVersion + " manquant, téléchargement...");
        
        // Nom final du fichier à la racine du modpack
        String finalJarName = "fabric-loader-" + fabricVersion + "-" + minecraftVersion + ".jar";
        File destFile = new File(gameDir, finalJarName);
        
        // Nom du JAR Maven (sans version Minecraft)
        String mavenJarName = "fabric-loader-" + fabricVersion + ".jar";
        File tempFile = new File(gameDir, mavenJarName);
        
        // URL Maven pour Fabric Loader
        String fabricMavenUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-loader/" + fabricVersion + "/" + mavenJarName;
        
        try {
            gui.appendLog("[*] Téléchargement depuis: " + fabricMavenUrl);
            downloadDependency(fabricMavenUrl, tempFile, 30000, 30000);
            
            // Renommer le fichier au format attendu
            if (tempFile.exists() && !tempFile.equals(destFile)) {
                if (tempFile.renameTo(destFile)) {
                    gui.appendLog("[OK] Fabric Loader téléchargé: " + finalJarName);
                } else {
                    // Si le renommage échoue, juste utiliser le nom temporaire
                    gui.appendLog("[WARN] Fabric Loader téléchargé avec nom: " + mavenJarName);
                }
            } else {
                gui.appendLog("[OK] Fabric Loader téléchargé: " + finalJarName);
            }
        } catch (Exception e) {
            gui.appendLog("[ERROR] Impossible de télécharger Fabric Loader: " + e.getMessage());
            throw new Exception("Fabric Loader " + fabricVersion + " manquant et impossible à télécharger.\n" +
                              "Vérifiez votre connexion internet ou téléchargez manuellement depuis:\n" +
                              fabricMavenUrl);
        }
    }
    
    /**
     * Télécharge un fichier distant vers {@code destFile} avec des timeouts configurables.
     *
     * @param urlStr          URL source
     * @param destFile        fichier de destination
     * @param connectTimeout  timeout de connexion en ms
     * @param readTimeout     timeout de lecture en ms
     */
    private static void downloadDependency(String urlStr, File destFile, int connectTimeout, int readTimeout) throws Exception {
        URL url = URI.create(urlStr).toURL();
        URLConnection conn = url.openConnection();
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(destFile)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    /**
     * Construit le classpath complet nécessaire au lancement de Minecraft Fabric.
     *
     * <p>Ordre de chargement garanti :
     * <ol>
     *   <li><b>Fabric Loader</b> (racine du modpack) — en tête de classpath</li>
     *   <li><b>Sponge-Mixin</b> — version la plus récente uniquement (ex : 0.16.5, pas 0.15.2)</li>
     *   <li><b>Guava</b> — version la plus récente uniquement (ex : 32.x, pas 20.0)</li>
     *   <li><b>ASM</b> — une seule version par composant (asm, asm-tree, asm-commons…)</li>
     *   <li>Tout le reste de {@code libraries/}, à l’exclusion des JARs NeoForge/FancyModLoader</li>
     * </ol>
     *
     * @param gameDir   répertoire racine du modpack
     * @param fabricJar chemin absolu du JAR Fabric Loader sélectionné
     * @param gui       la fenêtre du launcher pour les logs
     * @return le classpath séparé par {@code File.pathSeparator}
     */
    private static String buildClasspath(File gameDir, String fabricJar, LauncherGUI gui) {
        File librariesDir = new File(gameDir, "libraries");
        File cacheFile    = new File(gameDir, ".classpath.cache");

        // Tentative de lecture du cache (invalidé si lastModified de libraries/ ou fabricJar change)
        String cacheKey = fabricJar + "|" + librariesDir.lastModified();
        try {
            if (cacheFile.exists()) {
                String[] lines = new String(Files.readAllBytes(cacheFile.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).split("\n", 2);
                if (lines.length == 2 && lines[0].trim().equals(cacheKey)) {
                    gui.appendLog("[*] Classpath chargé depuis le cache.");
                    return lines[1].trim();
                }
            }
        } catch (Exception ignored) {}

        // CopyOnWriteArrayList pour la sécurité du parallel stream dans findAllJars
        List<String> classpathEntries = new java.util.concurrent.CopyOnWriteArrayList<>();

        if (fabricJar != null) {
            classpathEntries.add(fabricJar);
            gui.appendLog("[*] Fabric Loader ajouté : " + new File(fabricJar).getName());
        }

        // Sponge-Mixin : version la plus récente uniquement
        File spongeMixinBaseDir = new File(librariesDir, "net/fabricmc");
        String latestSpongeMixin = null;
        String latestSpongeMixinVersion = "0.0.0";
        if (spongeMixinBaseDir.exists()) {
            List<File> spongeCandidates = new ArrayList<>();
            findSpongeMixinJars(spongeMixinBaseDir, spongeCandidates);
            for (File sm : spongeCandidates) {
                String v = sm.getName().replace("sponge-mixin-", "").split("\\+")[0];
                if (compareVersions(v, latestSpongeMixinVersion) > 0) {
                    latestSpongeMixinVersion = v;
                    latestSpongeMixin = sm.getAbsolutePath();
                }
            }
        }
        if (latestSpongeMixin != null) {
            classpathEntries.add(latestSpongeMixin);
            gui.appendLog("[*] Sponge-Mixin ajouté (v" + latestSpongeMixinVersion + ") : " + new File(latestSpongeMixin).getName());
        } else {
            gui.appendLog("[WARN] Sponge-Mixin introuvable — les Mixins risquent de crasher");
        }

        // Guava : version la plus récente uniquement
        String latestGuava = findLatestJarByPrefix(librariesDir, "guava", gui);
        if (latestGuava != null) {
            classpathEntries.add(latestGuava);
            gui.appendLog("[*] Guava ajouté (priorité) : " + new File(latestGuava).getName());
        }

        // ASM : une version par composant
        classpathEntries.addAll(findUniqueAsmJars(librariesDir, gui));

        // Tous les autres JARs (parallel stream)
        if (librariesDir.exists()) {
            findAllJars(librariesDir, classpathEntries, gui);
        } else {
            gui.appendLog("[WARN] Dossier libraries/ non trouvé : " + librariesDir.getAbsolutePath());
        }

        gui.appendLog("[*] Classpath construit : " + classpathEntries.size() + " JARs");
        String classpath = String.join(File.pathSeparator, classpathEntries);

        // Sauvegarde du cache
        try {
            Files.write(cacheFile.toPath(),
                (cacheKey + "\n" + classpath).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}

        return classpath;
    }
    
    /**
     * Cherche le JAR de Fabric Loader dans libraries/
     */
    private static String findFabricLoaderJarInLibraries(File librariesDir) {
        if (!librariesDir.exists()) return null;
        
        // Chercher dans libraries/net/fabricmc/fabric-loader/*/
        File fabricLoaderDir = new File(librariesDir, "net/fabricmc/fabric-loader");
        if (!fabricLoaderDir.exists()) return null;
        
        File[] versions = fabricLoaderDir.listFiles(File::isDirectory);
        if (versions == null || versions.length == 0) return null;
        
        // Prendre la première version trouvée
        File versionDir = versions[0];
        String versionName = versionDir.getName();
        
        File jarFile = new File(versionDir, "fabric-loader-" + versionName + ".jar");
        if (jarFile.exists()) {
            return jarFile.getAbsolutePath();
        }
        
        return null;
    }
    
    /**
     * Trouve tous les JARs ASM uniques en évitant les doublons de composants
     * Pour chaque composant (asm, tree, commons, analysis, util), ajoute une seule version
     * (préfère la plus récente si plusieurs versions existent)
     */
    private static List<String> findUniqueAsmJars(File librariesDir, LauncherGUI gui) {
        List<String> asmJars = new ArrayList<>();
        
        File asmBaseDir = new File(librariesDir, "org/ow2/asm");
        if (!asmBaseDir.exists() || !asmBaseDir.isDirectory()) {
            return asmJars;
        }
        
        // Scanne tous les répertoires ASM
        File[] asmDirs = asmBaseDir.listFiles(File::isDirectory);
        if (asmDirs == null) {
            return asmJars;
        }
        
        for (File componentDir : asmDirs) {
            // componentDir = "asm", "asm-tree", "asm-commons", etc.
            String componentName = componentDir.getName();
            
            // Scanne les versions de ce composant
            File[] versions = componentDir.listFiles(File::isDirectory);
            if (versions == null) {
                continue;
            }
            
            // Trouve la version la PLUS RÉCENTE pour ce composant
            String latestVersion = "0.0.0";
            File latestVersionDir = null;
            
            for (File versionDir : versions) {
                String version = versionDir.getName();
                // Comparaison simple
                if (compareVersions(version, latestVersion) > 0) {
                    latestVersion = version;
                    latestVersionDir = versionDir;
                }
            }
            
            // Ajouter le JAR de la version la plus récente
            if (latestVersionDir != null) {
                File[] jars = latestVersionDir.listFiles((d, name) -> name.startsWith(componentName) && name.endsWith(".jar"));
                if (jars != null && jars.length > 0) {
                    asmJars.add(jars[0].getAbsolutePath());
                    gui.appendLog("[*] ASM ajouté: " + jars[0].getName() + " (v" + latestVersion + ")");
                }
            }
        }
        
        return asmJars;
    }
    
    /**
     * Comparaison de versions (retourne 1 si v1 > v2, -1 si v1 < v2, 0 si égal)
     * Ex: "9.9" > "9.3" retourne 1
     */
    private static int compareVersions(String v1, String v2) {
        try {
            String[] parts1 = v1.split("\\.");
            String[] parts2 = v2.split("\\.");
            
            for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
                int num1 = (i < parts1.length) ? Integer.parseInt(parts1[i]) : 0;
                int num2 = (i < parts2.length) ? Integer.parseInt(parts2[i]) : 0;
                
                if (num1 > num2) return 1;
                if (num1 < num2) return -1;
            }
        } catch (Exception e) {
            // En cas d'erreur de parsing, retourner 0
            return 0;
        }
        return 0;
    }

    /**
     * Parcourt récursivement {@code libraries/} en mode parallèle (NIO + parallel stream) pour collecter
     * tous les JARs, sauf : fabric-loader, asm*, sponge-mixin, guava et JARs NeoForge/FancyModLoader.
     */
    private static void findAllJars(File dir, List<String> jars, LauncherGUI gui) {
        try {
            Files.walk(dir.toPath())
                .parallel()
                .filter(p -> !Files.isDirectory(p))
                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                .filter(p -> {
                    String name = p.getFileName().toString();
                    String absPath = p.toString().replace('\\', '/');
                    return !name.startsWith("fabric-loader")
                        && !(name.startsWith("asm") && name.endsWith(".jar"))
                        && !name.startsWith("sponge-mixin")
                        && !name.startsWith("guava")
                        && !name.contains("neoforge")
                        && !absPath.contains("/neoforged/")
                        && !absPath.contains("/fancymodloader/");
                })
                .forEach(p -> jars.add(p.toAbsolutePath().toString()));
        } catch (java.io.IOException e) {
            gui.appendLog("[WARN] Erreur lecture du dossier libraries: " + e.getMessage());
        }
    }

    /**
     * Cherche le JAR de Fabric Loader dans le répertoire du jeu.
     * Cherche dans les sous-dossiers (lib/, libs/, etc.)
     */
    private static String findFabricLoaderJar(File gameDir, String minecraftVersion, String fabricVersion, LauncherGUI gui) {
        // Chercher directement dans gameDir avec PRIORITÉ à la bonne version
        File[] files = gameDir.listFiles(File::isFile);
        if (files != null) {
            // Chercher d'abord EXACTEMENT la bonne version
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith("fabric-loader") && name.endsWith(".jar")) {
                    // Vérifier si c'est la bonne version (ex: fabric-loader-0.17.3-1.21.1.jar)
                    if (name.contains(fabricVersion)) {
                        gui.appendLog("[OK] Fabric Loader trouvé (bonne version): " + name);
                        return f.getAbsolutePath();
                    }
                }
            }
            
            // Si pas trouvé avec la bonne version, accepter n'importe quel Fabric Loader
            for (File f : files) {
                if (f.getName().startsWith("fabric-loader") && f.getName().endsWith(".jar")) {
                    gui.appendLog("[WARN] Fabric Loader trouvé MAIS PAS la version " + fabricVersion + ": " + f.getName());
                    return f.getAbsolutePath();
                }
            }
        }
        
        // Chercher dans les sous-dossiers (lib/, libs/, fabric/, etc.)
        File[] subdirs = gameDir.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File subdir : subdirs) {
                File[] jars = subdir.listFiles((d, name) -> name.startsWith("fabric-loader") && name.endsWith(".jar"));
                if (jars != null && jars.length > 0) {
                    for (File jar : jars) {
                        if (jar.getName().contains(fabricVersion)) {
                            gui.appendLog("[OK] Fabric Loader trouvé dans sous-dossier: " + jar.getName());
                            return jar.getAbsolutePath();
                        }
                    }
                    // Sinon prendre le premier
                    gui.appendLog("[WARN] Utilisation de " + jars[0].getName());
                    return jars[0].getAbsolutePath();
                }
            }
        }
        
        // Si rien trouvé, retourner null
        gui.appendLog("[ERROR] Aucun Fabric Loader JAR trouvé!");
        return null;
    }

    /**
     * Cherche l’exécutable Java 21+ dans cet ordre de priorité :
     * <ol>
     *   <li>Jre_21 et java-runtime-delta extraits dans {@code ~/.zokkymon/}</li>
     *   <li>Chemins statiques connus sous {@code ~/.zokkymon/}</li>
     *   <li>Sous-dossiers dynamiques de {@code ~/.zokkymon/} (jdk-21*, runtime/)</li>
     *   <li>PATH système</li>
     *   <li>java.home (JVM courante injectée par Launch4j)</li>
     * </ol>
     *
     * @return chemin absolu vers un {@code java.exe} valide (≥ 21), ou {@code null} si non trouvé
     */
    private static String findJavaExecutable() {
        String osName = System.getProperty("os.name").toLowerCase();
        String javaExeName = osName.contains("win") ? "java.exe" : "java";
        String home = System.getProperty("user.home");
        String z = home + File.separator + ".zokkymon" + File.separator;
        String b = File.separator + "bin" + File.separator + javaExeName;

        // Tous les chemins fixes, par priorité décroissante
        List<String> candidates = new ArrayList<>(java.util.Arrays.asList(
            z + "Jre_21" + b,
            z + "runtime" + File.separator + "java21" + b,
            z + "java" + b,
            z + "jre_21" + b,
            z + "openjdk-21" + b
        ));

        // Sous-dossiers dynamiques de ~/.zokkymon/ (jdk-21*, runtime/...)
        File zokkymonDir = new File(home, ".zokkymon");
        if (zokkymonDir.isDirectory()) {
            File[] dirs = zokkymonDir.listFiles(File::isDirectory);
            if (dirs != null) {
                for (File dir : dirs) {
                    String n = dir.getName().toLowerCase();
                    if (n.contains("21") && (n.startsWith("jdk-") || n.startsWith("openjdk-") || n.startsWith("jre-"))) {
                        candidates.add(dir.getAbsolutePath() + b);
                    }
                }
                File runtime = new File(zokkymonDir, "runtime");
                if (runtime.isDirectory()) {
                    File found = findJavaInRuntimeDir(runtime, javaExeName);
                    if (found != null) candidates.add(found.getAbsolutePath());
                }
            }
        }

        // Vérifier chaque candidat statique
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                String version = getJavaVersion(path);
                if (version != null && isJavaVersionAcceptable(version)) return path;
            }
        }

        // PATH système
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                String p = dir + File.separator + javaExeName;
                if (new File(p).exists()) {
                    String version = getJavaVersion(p);
                    if (version != null && isJavaVersionAcceptable(version)) return p;
                }
            }
        }

        // java.home (JVM injectée par Launch4j)
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            String p = javaHome + File.separator + "bin" + File.separator + javaExeName;
            if (new File(p).exists()) return p;
        }

        return null;
    }
    
    /**
     * Récupère la version de Java d'un exécutable java
     * Retourne par exemple "21.0.1" ou "17.0.5"
     */
    private static String getJavaVersion(String javaPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(javaPath, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                // Format: "openjdk version "21.0.1" 2023-10-17"
                if (line.contains("version")) {
                    // Extraire le numéro de version
                    String[] parts = line.split("\"");
                    if (parts.length > 1) {
                        return parts[1];
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            // En cas d'erreur, ignorer
        }
        return null;
    }
    
    /**
     * Cherche récursivement java.exe/java dans le répertoire runtime/ (format CurseForge)
     * Cherche dans des chemins comme: runtime/java-runtime-delta/java21/bin/java.exe
     */
    private static File findJavaInRuntimeDir(File runtimeDir, String javaExeName) {
        if (!runtimeDir.exists() || !runtimeDir.isDirectory()) {
            return null;
        }
        
        // Chercher d'abord dans les chemins standard
        File[] subDirs = runtimeDir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File subDir : subDirs) {
                String subDirName = subDir.getName().toLowerCase();
                // Chercher dans java-runtime-delta/*, java21*, openjdk-21*, etc.
                if (subDirName.contains("java") && (subDirName.contains("21") || subDirName.contains("runtime"))) {
                    // Chercher récursivement bin/java.exe
                    File javaFile = findJavaExecutableRecursive(subDir, javaExeName);
                    if (javaFile != null) {
                        String version = getJavaVersion(javaFile.getAbsolutePath());
                        if (version != null && isJavaVersionAcceptable(version)) {
                            return javaFile;
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Cherche récursivement un exécutable java dans un répertoire
     */
    private static File findJavaExecutableRecursive(File dir, String javaExeName) {
        if (!dir.exists() || !dir.isDirectory()) {
            return null;
        }
        
        // Vérifier d'abord dans ce répertoire et ses sous-dossiers proches
        File binDir = new File(dir, "bin");
        if (binDir.exists()) {
            File javaFile = new File(binDir, javaExeName);
            if (javaFile.exists()) {
                return javaFile;
            }
        }
        
        // Chercher récursivement (max 3 niveaux de profondeur)
        return findJavaExecutableRecursiveHelper(dir, javaExeName, 0);
    }
    
    /** Implémentation récursive de la recherche d’un exécutable java, limitée à 3 niveaux de profondeur. */
    private static File findJavaExecutableRecursiveHelper(File dir, String javaExeName, int depth) {
        if (depth > 3 || !dir.exists() || !dir.isDirectory()) {
            return null;
        }
        
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().equals(javaExeName)) {
                    return file;
                }
                if (file.isDirectory()) {
                    File found = findJavaExecutableRecursiveHelper(file, javaExeName, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * Vérifie si la version Java est acceptable (≥ 21), requis par ce modpack.
     */
    private static boolean isJavaVersionAcceptable(String version) {
        if (version == null) {
            return false;
        }
        try {
            // Format: "21.0.1" ou "17.0.5"
            int dotIndex = version.indexOf('.');
            int major = Integer.parseInt(dotIndex > 0 ? version.substring(0, dotIndex) : version);
            return major >= 21;  // Les mods de ce modpack requièrent Java 21+
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Cherche récursivement le premier JAR ASM dans un répertoire
     */
    private static File findFirstAsmJar(File dir, LauncherGUI gui) {
        if (dir == null || !dir.exists()) {
            return null;
        }
        
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        
        for (File file : files) {
            if (file.isFile() && (file.getName().startsWith("asm-") || file.getName().startsWith("asm.") 
                                 || file.getName().equals("asm.jar")) && file.getName().endsWith(".jar")) {
                return file;
            }
        }
        
        // Chercher récursivement dans les sous-dossiers
        for (File file : files) {
            if (file.isDirectory()) {
                File found = findFirstAsmJar(file, gui);
                if (found != null) {
                    return found;
                }
            }
        }
        
        return null;
    }

    /**
     * Cherche récursivement tous les JARs sponge-mixin disponibles dans {@code dir}.
     * Retourne tous les candidats ; l'appelant choisit ensuite la version la plus récente.
     */
    private static void findSpongeMixinJars(File dir, List<File> result) {
        try {
            Files.walk(dir.toPath())
                .filter(p -> !Files.isDirectory(p))
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith("sponge-mixin") && name.endsWith(".jar");
                })
                .forEach(p -> result.add(p.toFile()));
        } catch (java.io.IOException e) { /* ignorer */ }
    }

    /**
     * Trouve le JAR avec le préfixe donné ayant la version la plus récente
     * en parcourant récursivement les libraries/.
     * Extrait la version depuis le nom: prefix-X.Y.Z[-classifier].jar
     * Ex: guava-32.1.2-jre.jar -> "32.1.2", guava-20.0.jar -> "20.0"
     */
    private static String findLatestJarByPrefix(File librariesDir, String prefix, LauncherGUI gui) {
        List<File> candidates = new ArrayList<>();
        findJarsByPrefix(librariesDir, prefix, candidates);
        String latestPath = null;
        String latestVersion = "0.0.0";
        for (File f : candidates) {
            // Extrait version depuis "prefix-VERSION[-classifier].jar"
            String withoutPrefix = f.getName().substring(prefix.length() + 1); // retirer "prefix-"
            String versionPart = withoutPrefix.replace(".jar", "").split("-")[0]; // "32.1.2" ou "20.0"
            if (compareVersions(versionPart, latestVersion) > 0) {
                latestVersion = versionPart;
                latestPath = f.getAbsolutePath();
            }
        }
        return latestPath;
    }

    /**
     * Cherche récursivement tous les JARs dont le nom commence par {@code prefix} dans {@code dir}.
     * Utilisé notamment pour trouver toutes les versions de guava disponibles.
     */
    private static void findJarsByPrefix(File dir, String prefix, List<File> result) {
        try {
            Files.walk(dir.toPath())
                .filter(p -> !Files.isDirectory(p))
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(prefix) && name.endsWith(".jar");
                })
                .forEach(p -> result.add(p.toFile()));
        } catch (java.io.IOException e) { /* ignorer */ }
    }

}