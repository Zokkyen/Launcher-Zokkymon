package com.zokkymon.launcher;

import com.formdev.flatlaf.FlatDarkLaf;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.json.JSONObject;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Interface principale du launcher — Design moderne "Glassmorphism"
 */
public class LauncherGUI extends JFrame {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    // ── Couleurs et Thèmes ───────────────────────────────────────────────────
    static Color BG, CARD_BG, SIDEBAR1, CONSOLE_BG, ACCENT, WARNING, DANGER, TEXT, TEXT_DIM;

    static void applyTheme(boolean dark) {
        if (dark) {
            BG         = new Color( 10,   0,  21);
            CARD_BG    = new Color( 20,   4,  36);
            SIDEBAR1   = new Color( 16,   2,  30);
            CONSOLE_BG = new Color(  5,   0,  12);
            ACCENT     = new Color(255,   0, 170);
            WARNING    = new Color(245, 158,  11);
            DANGER     = new Color(239,  68,  68);
            TEXT       = new Color(240, 232, 255);
            TEXT_DIM   = new Color(130, 110, 160);
        } else {
            BG         = new Color(242, 242, 248);
            CARD_BG    = new Color(255, 255, 255);
            SIDEBAR1   = new Color(255, 255, 255);
            CONSOLE_BG = new Color(250, 250, 255);
            ACCENT     = new Color(110,  40, 230);
            WARNING    = new Color(180,  90,   0);
            DANGER     = new Color(190,  25,  25);
            TEXT       = new Color( 30,  30,  35);
            TEXT_DIM   = new Color(100, 100, 120);
        }
    }

    // ── Composants exposés ───────────────────────────────────────────────────
    private JTextArea    logArea;
    private JLabel       statusLabel;
    private JProgressBar progressBar;
    private JButton      playButton;

    // ── Info-cards ───────────────────────────────────────────────────────────
    private JLabel infoModpackVal;
    private JLabel infoJavaVal;
    private JLabel infoRamVal;
    private JLabel infoModsVal;
    private JLabel infoDiskVal;

    // ── Capsule version launcher / Console scroll ─────────────────────────────
    private JScrollPane logScrollPane;
    private JLabel      launcherStatusLabel;
    private JButton applyLauncherButton;

    // ── Voyant serveur ───────────────────────────────────────────────────────
    private JLabel serverDot;
    private JLabel serverStatusLbl;
    private JButton serverRefreshBtn;
    private static final String SERVER_HOST = "zokkyen-cobblemon.ddns.net";
    private static final int    SERVER_PORT = 25565;

    // ── Auth Microsoft ───────────────────────────────────────────────────────
    private JLabel  authStatusLbl;
    private JButton authActionBtn;

    // ── Cache des états réseau ────────────────────────────────────────────────
    private volatile Boolean cachedServerOnline   = null;
    private volatile String  cachedLauncherStatus = null;
    private volatile String  cachedLauncherNewVer = null;
    private volatile String  cachedModpackStatus  = null;
    private volatile String  cachedInfoModpack    = null;
    private volatile String  cachedInfoJava       = null;
    private volatile String  cachedInfoRam        = null;
    private volatile String  cachedInfoMods       = null;
    private volatile String  cachedInfoDisk       = null;

    // ── Polices statiques ─────────────────────────────────────────────────────
    private static final Font FONT_MONO  = new Font("Consolas", Font.PLAIN,  12);
    private static final DateTimeFormatter LOG_LINE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter LOG_FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final int LOG_RETENTION_DAYS = 30;
    private static final int MAX_SESSION_LOG_FILES = 120;
    private static final long FULL_LOG_ROTATE_BYTES = 25L * 1024L * 1024L;

    // ── Logs persistants ──────────────────────────────────────────────────────
    private final Object logFileLock = new Object();
    private File logsDirectory;
    private File sessionLogFile;
    private File fullLogFile;
    private BlockingQueue<String> pendingLogLines;

    // ── Cache images ──────────────────────────────────────────────────────────
    private transient BufferedImage bannerImg;
    private transient BufferedImage logoImg;

    // ── Services ─────────────────────────────────────────────────────────────
    private transient ConfigManager config;
    private transient Updater        updater;

    // ═════════════════════════════════════════════════════════════════════════
    //  Constructeur
    // ═════════════════════════════════════════════════════════════════════════
    @SuppressWarnings("this-escape")
    public LauncherGUI() {
        try {
            UIManager.put("Button.arc", 14);
            UIManager.put("Component.arc", 14);
            UIManager.put("ProgressBar.arc", 14);
            FlatDarkLaf.setup();
        } catch (Exception ignored) {}

        config  = new ConfigManager();
        MicrosoftAuth.setClientId(config.getClientId());
        applyTheme(config.isDarkMode());
        updater = new Updater(this, config);
        initLogStorage();

        loadBannerForCurrentTheme();
        logoImg = loadImage("/zokkymon.png", "/zokkymon.ico");

        setTitle("Launcher Zokkymon");
        setSize(1000, 640);
        setResizable(false);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        loadWindowIcon();

        initLogs();

        JPanel root = buildRootPanel();
        setContentPane(root);
        setVisible(true);

        startBackgroundChecks();
        new Thread(this::checkAndUpdate).start();
    }

    private void initLogs() {
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(FONT_MONO);
        logArea.setBackground(CONSOLE_BG);
        logArea.setForeground(TEXT);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        javax.swing.text.DefaultCaret caret = (javax.swing.text.DefaultCaret) logArea.getCaret();
        caret.setUpdatePolicy(javax.swing.text.DefaultCaret.ALWAYS_UPDATE);

        logScrollPane = new JScrollPane(logArea);
        logScrollPane.setBorder(BorderFactory.createLineBorder(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
        logScrollPane.getViewport().setBackground(CONSOLE_BG);
    }

    private void initLogStorage() {
        try {
            logsDirectory = new File(System.getProperty("user.home"), ".zokkymon/logs");
            if (!logsDirectory.exists() && !logsDirectory.mkdirs()) {
                throw new IOException("Impossible de créer le dossier logs : " + logsDirectory);
            }

            fullLogFile = new File(logsDirectory, "launcher-full.txt");
            rotateFullLogIfNeeded();
            sessionLogFile = new File(logsDirectory,
                "launcher-session-" + LOG_FILE_TS.format(LocalDateTime.now()) + ".txt");
            cleanupOldLogs();
            cleanupExcessSessionLogs();
            startAsyncLogWriter();

            appendTextToFile(fullLogFile,
                "\n=== Nouvelle session " + LOG_LINE_TS.format(LocalDateTime.now()) + " ===\n");
            appendTextToFile(sessionLogFile,
                "=== Session lancée " + LOG_LINE_TS.format(LocalDateTime.now()) + " ===\n");
        } catch (Exception e) {
            logsDirectory = null;
            fullLogFile = null;
            sessionLogFile = null;
        }
    }

    private void rotateFullLogIfNeeded() {
        try {
            if (!fullLogFile.exists() || fullLogFile.length() < FULL_LOG_ROTATE_BYTES) return;
            File archived = new File(logsDirectory,
                "launcher-full-" + LOG_FILE_TS.format(LocalDateTime.now()) + ".txt");
            if (!fullLogFile.renameTo(archived)) {
                Files.copy(fullLogFile.toPath(), archived.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(fullLogFile.toPath());
            }
        } catch (Exception ignored) {}
    }

    private int cleanupOldLogs() {
        int deleted = 0;
        try {
            File[] allLogs = logsDirectory.listFiles((dir, name) -> name.endsWith(".txt"));
            if (allLogs == null) return 0;
            long cutoff = System.currentTimeMillis() - (LOG_RETENTION_DAYS * 24L * 60L * 60L * 1000L);
            for (File f : allLogs) {
                if (!f.exists()) continue;
                if (f.getName().equals("launcher-full.txt")) continue;
                if (sessionLogFile != null && f.equals(sessionLogFile)) continue;
                if (f.lastModified() < cutoff) {
                    try {
                        if (Files.deleteIfExists(f.toPath())) deleted++;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return deleted;
    }

    private int cleanupExcessSessionLogs() {
        int deleted = 0;
        try {
            File[] sessionLogs = logsDirectory.listFiles((dir, name) ->
                name.startsWith("launcher-session-") && name.endsWith(".txt"));
            if (sessionLogs == null || sessionLogs.length <= MAX_SESSION_LOG_FILES) return 0;

            Arrays.sort(sessionLogs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File file : sessionLogs) {
                if (sessionLogs.length - deleted <= MAX_SESSION_LOG_FILES) break;
                if (sessionLogFile != null && file.equals(sessionLogFile)) continue;
                try {
                    if (Files.deleteIfExists(file.toPath())) deleted++;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return deleted;
    }

    private void cleanupLogsNow(Component parent) {
        if (logsDirectory == null || !logsDirectory.exists()) {
            JOptionPane.showMessageDialog(parent,
                "Le dossier de logs est indisponible.",
                "Logs",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        int choice = JOptionPane.showConfirmDialog(parent,
            "Supprimer les logs anciens (rétention > " + LOG_RETENTION_DAYS + " jours) et réduire les sessions en surplus ?",
            "Nettoyage des logs",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        int oldDeleted = cleanupOldLogs();
        int sessionDeleted = cleanupExcessSessionLogs();
        int total = oldDeleted + sessionDeleted;

        JOptionPane.showMessageDialog(parent,
            "Nettoyage terminé : " + total + " fichier(s) supprimé(s).",
            "Logs",
            JOptionPane.INFORMATION_MESSAGE);
        appendLog("[Logs] Nettoyage manuel exécuté : " + total + " fichier(s) supprimé(s).");
    }

    private void startAsyncLogWriter() {
        pendingLogLines = new LinkedBlockingQueue<>();
        Thread writer = new Thread(() -> {
            while (true) {
                try {
                    String first = pendingLogLines.take();
                    List<String> batch = new ArrayList<>();
                    batch.add(first);
                    pendingLogLines.drainTo(batch, 128);

                    StringBuilder payload = new StringBuilder(first.length() * Math.max(1, batch.size()));
                    for (String line : batch) {
                        payload.append(line);
                    }

                    synchronized (logFileLock) {
                        appendTextToFile(fullLogFile, payload.toString());
                        appendTextToFile(sessionLogFile, payload.toString());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {}
            }
        }, "zokkymon-log-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private void appendTextToFile(File file, String text) throws IOException {
        Files.writeString(
            file.toPath(),
            text,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    private void writeLogLineToFiles(String message) {
        if (fullLogFile == null || sessionLogFile == null) return;
        String line = "[" + LOG_LINE_TS.format(LocalDateTime.now()) + "] " + message + System.lineSeparator();
        if (pendingLogLines != null) {
            try {
                pendingLogLines.offer(line);
                return;
            } catch (Exception ignored) {}
        }
        synchronized (logFileLock) {
            try {
                appendTextToFile(fullLogFile, line);
                appendTextToFile(sessionLogFile, line);
            } catch (IOException ignored) {}
        }
    }

    private void exportLogs(boolean selectionOnly, Component parent) {
        if (logsDirectory == null) {
            JOptionPane.showMessageDialog(parent,
                "Le stockage de logs n'est pas disponible.",
                "Logs",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        String content = selectionOnly ? logArea.getSelectedText() : logArea.getText();
        if (content == null || content.isBlank()) {
            JOptionPane.showMessageDialog(parent,
                selectionOnly
                    ? "Aucun texte sélectionné dans les logs."
                    : "Aucun contenu de log à exporter.",
                "Logs",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String scope = selectionOnly ? "targeted" : "complete";
        File out = new File(logsDirectory,
            "launcher-" + scope + "-" + LOG_FILE_TS.format(LocalDateTime.now()) + ".txt");
        try {
            Files.writeString(out.toPath(), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            JOptionPane.showMessageDialog(parent,
                "Export réussi : " + out.getName(),
                "Logs",
                JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent,
                "Échec d'export : " + e.getMessage(),
                "Logs",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openLogsFolder(Component parent) {
        if (logsDirectory == null || !logsDirectory.exists()) {
            JOptionPane.showMessageDialog(parent,
                "Le dossier de logs est indisponible.",
                "Logs",
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!Desktop.isDesktopSupported()) {
            JOptionPane.showMessageDialog(parent,
                "Ouverture du dossier non supportée sur cet environnement.",
                "Logs",
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Desktop.getDesktop().open(logsDirectory);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent,
                "Impossible d'ouvrir le dossier : " + e.getMessage(),
                "Logs",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private void generateCrashReport(Component parent) {
        if (logsDirectory == null || !logsDirectory.exists()) {
            JOptionPane.showMessageDialog(parent,
                "Le dossier de logs est indisponible.",
                "Rapport crash",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        File report = new File(logsDirectory,
            "crash-report-" + LOG_FILE_TS.format(LocalDateTime.now()) + ".txt");

        String uiLogs = logArea != null ? logArea.getText() : "";
        if (uiLogs.length() > 25_000) {
            uiLogs = uiLogs.substring(uiLogs.length() - 25_000);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Zokkymon Crash Report ===\n");
        sb.append("Généré le : ").append(LOG_LINE_TS.format(LocalDateTime.now())).append("\n\n");

        sb.append("[Launcher]\n");
        sb.append("Version : ").append(config.getLauncherVersion()).append("\n");
        sb.append("Canal : ").append(config.getLauncherChannel()).append("\n");
        sb.append("Minecraft : ").append(config.getMinecraftVersion()).append("\n");
        sb.append("Fabric : ").append(config.getFabricVersion()).append("\n");
        sb.append("RAM config : ").append(config.getRamAllocation()).append("\n");
        sb.append("Install path : ").append(config.getInstallPath()).append("\n\n");

        sb.append("[Système]\n");
        sb.append("OS : ").append(System.getProperty("os.name")).append(" ")
            .append(System.getProperty("os.version")).append("\n");
        sb.append("Arch : ").append(System.getProperty("os.arch")).append("\n");
        sb.append("Java runtime : ").append(System.getProperty("java.version")).append("\n");
        sb.append("Java home : ").append(System.getProperty("java.home")).append("\n\n");

        sb.append("[Logs récents UI]\n");
        sb.append(uiLogs.isBlank() ? "(vide)" : uiLogs).append("\n");

        try {
            Files.writeString(report.toPath(), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            JOptionPane.showMessageDialog(parent,
                "Rapport généré : " + report.getName(),
                "Rapport crash",
                JOptionPane.INFORMATION_MESSAGE);
            appendLog("[CrashReport] Rapport généré: " + report.getAbsolutePath());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent,
                "Impossible de générer le rapport : " + e.getMessage(),
                "Rapport crash",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private String checkEndpointConnectivity(String url, int timeoutMs) {
        try {
            URLConnection conn = URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            if (conn instanceof HttpURLConnection http) {
                http.setRequestMethod("HEAD");
                int code = http.getResponseCode();
                if (code >= 200 && code < 400) return null;
                // fallback GET si HEAD non supporté
                http.disconnect();
                URLConnection conn2 = URI.create(url).toURL().openConnection();
                conn2.setConnectTimeout(timeoutMs);
                conn2.setReadTimeout(timeoutMs);
                if (conn2 instanceof HttpURLConnection get) {
                    get.setRequestMethod("GET");
                    int c2 = get.getResponseCode();
                    if (c2 >= 200 && c2 < 400) return null;
                    return "HTTP " + c2;
                }
            }
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private int parseConfiguredRamGb() {
        try {
            String raw = config.getRamAllocation();
            String numeric = raw.replaceAll("[^0-9]", "");
            if (numeric.isBlank()) return 6;
            return Integer.parseInt(numeric);
        } catch (Exception ignored) {
            return 6;
        }
    }

    private boolean runPreLaunchHealthCheck() {
        List<String> infos = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // 1) Disque
        try {
            File install = new File(config.getInstallPath());
            long freeGb = Math.round(install.getUsableSpace() / 1_073_741_824.0);
            infos.add("Disque libre: " + freeGb + " Go");
            if (freeGb < 3) errors.add("Espace disque insuffisant (< 3 Go) pour lancer le jeu.");
            else if (freeGb < 8) warnings.add("Espace disque faible (" + freeGb + " Go). Risque d'échec update/cache.");
        } catch (Exception e) {
            warnings.add("Impossible de lire l'espace disque: " + e.getMessage());
        }

        // 2) RAM
        try {
            int configuredRam = parseConfiguredRamGb();
            com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long freePhysicalGb = Math.round(os.getFreeMemorySize() / 1_073_741_824.0);
            infos.add("RAM libre: " + freePhysicalGb + " Go / RAM configurée: " + configuredRam + " Go");
            if (freePhysicalGb < Math.max(2, configuredRam - 1)) {
                warnings.add("RAM libre faible par rapport à la RAM allouée (" + configuredRam + " Go).");
            }
        } catch (Exception e) {
            warnings.add("Impossible de vérifier la RAM disponible: " + e.getMessage());
        }

        // 3) Java
        try {
            String javaExe = Launcher.findCompatibleJavaExecutable();
            if (javaExe == null || javaExe.isBlank()) {
                errors.add("Java 21+ compatible introuvable.");
            } else {
                String v = Launcher.readJavaVersion(javaExe);
                infos.add("Java détecté: " + javaExe + (v != null ? " (" + v + ")" : ""));
            }
        } catch (Exception e) {
            errors.add("Échec vérification Java: " + e.getMessage());
        }

        // 4) Connectivité endpoints
        try {
            String launcherInfoUrl = config.getLauncherInfoUrl();
            String modpackInfoUrl = config.getModpackInfoUrl();
            String modpackToken = config.getModpackToken();
            if (modpackToken != null && !modpackToken.isBlank()) {
                modpackInfoUrl = modpackInfoUrl + (modpackInfoUrl.contains("?") ? "&" : "?")
                    + "token=" + java.net.URLEncoder.encode(modpackToken, StandardCharsets.UTF_8);
            }

            String launcherErr = checkEndpointConnectivity(launcherInfoUrl, 5000);
            if (launcherErr == null) infos.add("Endpoint launcher: OK");
            else warnings.add("Endpoint launcher inaccessible: " + launcherErr);

            String modpackErr = checkEndpointConnectivity(modpackInfoUrl, 5000);
            if (modpackErr == null) infos.add("Endpoint modpack: OK");
            else warnings.add("Endpoint modpack inaccessible: " + modpackErr);
        } catch (Exception e) {
            warnings.add("Échec vérification connectivité: " + e.getMessage());
        }

        appendLog("[HealthCheck] Résultat pré-lancement:");
        for (String i : infos) appendLog("  [OK] " + i);
        for (String w : warnings) appendLog("  [WARN] " + w);
        for (String er : errors) appendLog("  [ERR] " + er);

        if (!errors.isEmpty()) {
            final String message = "Le lancement est bloqué par " + errors.size() + " erreur(s).\n\n"
                + String.join("\n", errors);
            try {
                SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(
                    this,
                    message,
                    "Vérification de santé — échec",
                    JOptionPane.ERROR_MESSAGE
                ));
            } catch (Exception ignored) {}
            return false;
        }

        if (!warnings.isEmpty()) {
            final int[] choice = new int[]{JOptionPane.NO_OPTION};
            String warningText = String.join("\n", warnings);
            try {
                SwingUtilities.invokeAndWait(() -> {
                    choice[0] = JOptionPane.showConfirmDialog(
                        this,
                        "Des avertissements ont été détectés :\n\n" + warningText + "\n\nContinuer quand même ?",
                        "Vérification de santé — avertissements",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                    );
                });
            } catch (Exception ignored) {
                return false;
            }
            return choice[0] == JOptionPane.YES_OPTION;
        }

        return true;
    }

    private void startBackgroundChecks() {
        if (cachedInfoModpack != null) {
            SwingUtilities.invokeLater(() -> {
                infoModpackVal.setText(cachedInfoModpack);
                infoJavaVal   .setText(cachedInfoJava);
                infoRamVal    .setText(cachedInfoRam);
                infoModsVal   .setText(cachedInfoMods);
                infoDiskVal   .setText(cachedInfoDisk != null ? cachedInfoDisk : "...");
            });
        } else {
            new Thread(this::initInfoCards).start();
        }

        if (cachedModpackStatus != null) {
            final String s = cachedModpackStatus;
            SwingUtilities.invokeLater(() -> {
                switch (s) {
                    case "absent" -> {
                        setProgress(0);
                        setStatus("Installation requise");
                        playButton.setText("INSTALLATION");
                        playButton.setBackground(new Color(234, 88, 12));
                        playButton.setEnabled(true);
                    }
                    case "outdated" -> {
                        setProgress(0);
                        setStatus("Mise à jour disponible");
                        playButton.setText("METTRE À JOUR");
                        playButton.setBackground(WARNING);
                        playButton.setEnabled(true);
                    }
                    default -> {
                        setProgress(100);
                        setStatus("Prêt à jouer");
                        playButton.setText("JOUER");
                        playButton.setBackground(ACCENT);
                        playButton.setEnabled(true);
                    }
                }
            });
        }

        if (cachedServerOnline != null) {
            final boolean s = cachedServerOnline;
            SwingUtilities.invokeLater(() -> applyServerState(s));
        }

        if (cachedLauncherStatus != null) {
            SwingUtilities.invokeLater(() -> {
                versionCapsuleContainer.removeAll();
                if ("update".equals(cachedLauncherStatus)) {
                    versionCapsuleContainer.add(buildUpdateAvailableCapsule(config.getLauncherVersion(), cachedLauncherNewVer), BorderLayout.CENTER);
                } else {
                    versionCapsuleContainer.add(buildUpToDateCapsule("v" + config.getLauncherVersion(), true), BorderLayout.CENTER);
                }
                versionCapsuleContainer.revalidate();
                versionCapsuleContainer.repaint();
            });
        } else {
            new Thread(() -> {
                try { Thread.sleep(400); } catch (Exception ignored) {}
                try {
                    String[] res = updater.checkLauncherUpdateSilent();
                    cachedLauncherStatus = res[0];
                    if ("update".equals(res[0])) cachedLauncherNewVer = res[1];
                    SwingUtilities.invokeLater(() -> {
                        versionCapsuleContainer.removeAll();
                        if ("update".equals(res[0])) {
                            versionCapsuleContainer.add(buildUpdateAvailableCapsule(config.getLauncherVersion(), res[1]), BorderLayout.CENTER);
                        } else if ("ok".equals(res[0])) {
                            versionCapsuleContainer.add(buildUpToDateCapsule("v" + config.getLauncherVersion(), true), BorderLayout.CENTER);
                        } else {
                            launcherStatusLabel.setForeground(TEXT_DIM);
                        }
                        versionCapsuleContainer.revalidate();
                        versionCapsuleContainer.repaint();
                    });
                } catch (Exception ignored) {}
            }).start();
        }
    }

    private void rebuildUI(boolean dark) {
        String savedLog = logArea != null ? logArea.getText() : "";
        applyTheme(dark);
        loadBannerForCurrentTheme();
        if (logArea != null) {
            logArea.setBackground(CONSOLE_BG);
            logArea.setForeground(TEXT);
            logScrollPane.getViewport().setBackground(CONSOLE_BG);
        }
        
        JPanel root = buildRootPanel();
        setContentPane(root);
        revalidate();
        repaint();

        if (!savedLog.isEmpty()) {
            logArea.setText(savedLog);
            SwingUtilities.invokeLater(() -> {
                javax.swing.JScrollBar bar = logScrollPane.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
        }
        startBackgroundChecks();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Construction de l'UI
    // ═════════════════════════════════════════════════════════════════════════

    private JPanel buildRootPanel() {
        JPanel root = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // Fond uni derrière la sidebar + fallback si pas d'image
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(BG);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        root.setOpaque(true);
        root.add(buildSidebar(),  BorderLayout.WEST);
        root.add(buildMainArea(), BorderLayout.CENTER);
        return root;
    }

    private void loadWindowIcon() {
        try {
            if (logoImg != null) { setIconImage(logoImg); return; }
            InputStream is = getClass().getResourceAsStream("/zokkymon.png");
            if (is == null) is = getClass().getResourceAsStream("/zokkymon.ico");
            if (is != null) {
                BufferedImage img = ImageIO.read(is);
                if (img != null) setIconImage(img);
            }
        } catch (Exception ignored) {}
    }

    private BufferedImage loadImage(String... paths) {
        for (String path : paths) {
            try (InputStream is = getClass().getResourceAsStream(path)) {
                if (is != null) {
                    BufferedImage img = ImageIO.read(is);
                    if (img != null) return img;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void loadBannerForCurrentTheme() {
        bannerImg = loadImage("/banner.png", "/zokkymon.png");
    }

    // ── Sidebar ──────────────────────────────────────────────────────────────
    private JPanel buildSidebar() {
        JPanel s = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(SIDEBAR1.getRed(), SIDEBAR1.getGreen(), SIDEBAR1.getBlue(), 210));
                g2.fillRect(0, 0, getWidth(), getHeight());
                
                Color sep = new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 40);
                g2.setColor(sep);
                g2.fillRect(getWidth()-1, 0, 1, getHeight());
                g2.dispose();
            }
        };
        s.setLayout(new BoxLayout(s, BoxLayout.Y_AXIS));
        s.setOpaque(false);
        s.setPreferredSize(new Dimension(250, 0));
        s.setBorder(new EmptyBorder(15, 15, 15, 15));

        s.add(buildLogoBlock());
        s.add(vSep(20));
        s.add(buildServerStatusPanel());
        s.add(vSep(8));
        s.add(buildAuthCard());
        s.add(vSep(20));

        JLabel secInfo = new JLabel("INFORMATIONS");
        secInfo.setFont(new Font("Segoe UI", Font.BOLD, 9));
        secInfo.setForeground(TEXT_DIM);
        secInfo.setAlignmentX(LEFT_ALIGNMENT);
        s.add(secInfo);
        s.add(vSep(8));

        infoModpackVal = infoVal("...");
        infoJavaVal    = infoVal("...");
        infoRamVal     = infoVal("...");
        infoModsVal    = infoVal("...");
        infoDiskVal    = infoVal("...");

        s.add(infoCard("Version du modpack", infoModpackVal));
        s.add(vSep(6));
        s.add(infoCard("Mods installés",  infoModsVal));
        s.add(vSep(6));
        s.add(infoCard("RAM allouée",     infoRamVal));
        s.add(vSep(6));
        s.add(infoCard("Disque libre",    infoDiskVal));

        s.add(Box.createVerticalGlue());
        s.add(buildVersionCapsule());
        return s;
    }

    private JPanel buildLogoBlock() {
        JPanel block = new JPanel(new BorderLayout(12, 0));
        block.setOpaque(false);
        block.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        block.setAlignmentX(LEFT_ALIGNMENT);

        JLabel logo = new JLabel() {
            @Override protected void paintComponent(Graphics g) {
                if (logoImg == null) { super.paintComponent(g); return; }
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int arc = 20; 
                g2.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
                g2.drawImage(logoImg, 0, 0, getWidth(), getHeight(), null);
                g2.dispose();
            }
        };
        logo.setPreferredSize(new Dimension(54, 54));
        logo.setOpaque(false);

        JPanel txt = new JPanel(new GridLayout(2, 1, 0, 2));
        txt.setOpaque(false);
        JLabel name = new JLabel(config.getModpackName());
        name.setFont(new Font("Segoe UI", Font.BOLD, 15));
        name.setForeground(TEXT);
        
        JLabel sub = new JLabel("Minecraft " + config.getMinecraftVersion());
        sub.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        sub.setForeground(TEXT_DIM);
        
        txt.add(name);
        txt.add(sub);

        block.add(logo, BorderLayout.WEST);
        block.add(txt,  BorderLayout.CENTER);
        return block;
    }

    private void paintGlassCard(Graphics g, int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(CARD_BG.getRed(), CARD_BG.getGreen(), CARD_BG.getBlue(), 200));
        g2.fillRoundRect(0, 0, width-1, height-1, 16, 16);
        g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 50));
        g2.drawRoundRect(0, 0, width-1, height-1, 16, 16);
        g2.dispose();
    }

    // ── Voyant serveur ───────────────────────────────────────────────────────
    private JPanel buildServerStatusPanel() {
        JPanel card = new JPanel(new BorderLayout(10, 0)) {
            @Override protected void paintComponent(Graphics g) {
                paintGlassCard(g, getWidth(), getHeight());
                super.paintComponent(g);
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(8, 12, 8, 12));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        card.setAlignmentX(LEFT_ALIGNMENT);

        serverDot = new JLabel("\u25cf");
        serverDot.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 22));
        serverDot.setForeground(TEXT_DIM);
        serverDot.setHorizontalAlignment(SwingConstants.CENTER);
        serverDot.setPreferredSize(new Dimension(28, 28));

        JPanel txt = new JPanel(new GridLayout(2, 1, 0, 1));
        txt.setOpaque(false);
        JLabel topLbl = new JLabel("SERVEUR");
        topLbl.setFont(new Font("Segoe UI", Font.BOLD, 9));
        topLbl.setForeground(TEXT_DIM);
        serverStatusLbl = new JLabel("Vérification...");
        serverStatusLbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        serverStatusLbl.setForeground(TEXT);
        txt.add(topLbl);
        txt.add(serverStatusLbl);

        serverRefreshBtn = new JButton() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(getModel().isPressed() ? ACCENT : new Color(50, 50, 60, 180));
                g2.fillOval(0, 0, getWidth()-1, getHeight()-1);
                
                String icon = "\u27F3";
                java.awt.Font font = new Font("Segoe UI Symbol", Font.PLAIN, 18);
                g2.setFont(font);
                g2.setColor(getModel().isPressed() ? Color.WHITE : new Color(220, 220, 220));
                java.awt.font.TextLayout tl = new java.awt.font.TextLayout(icon, font, g2.getFontRenderContext());
                java.awt.geom.Rectangle2D vb = tl.getBounds();
                int x = (int) Math.round((getWidth()  - vb.getWidth())  / 2.0 - vb.getX());
                int y = (int) Math.round((getHeight() - vb.getHeight()) / 2.0 - vb.getY());
                g2.drawString(icon, x, y);
                g2.dispose();
            }
        };
        serverRefreshBtn.setPreferredSize(new Dimension(32, 32));
        serverRefreshBtn.setToolTipText("Vérifier maintenant");
        serverRefreshBtn.setFocusPainted(false);
        serverRefreshBtn.setContentAreaFilled(false);
        serverRefreshBtn.setBorderPainted(false);
        serverRefreshBtn.setOpaque(false);
        serverRefreshBtn.addActionListener(e -> {
            serverStatusLbl.setText("Vérification...");
            serverStatusLbl.setForeground(TEXT_DIM);
            serverDot.setForeground(TEXT_DIM);
            cachedServerOnline = null;
            serverRefreshBtn.setEnabled(false);
            new Thread(() -> {
                checkServerOnce();
                SwingUtilities.invokeLater(() -> serverRefreshBtn.setEnabled(true));
            }).start();
        });

        card.add(serverDot,        BorderLayout.WEST);
        card.add(txt,              BorderLayout.CENTER);
        JPanel refreshWrap = new JPanel(new GridBagLayout());
        refreshWrap.setOpaque(false);
        refreshWrap.add(serverRefreshBtn);
        card.add(refreshWrap, BorderLayout.EAST);
        return card;
    }

    private void checkServerOnce() {
        boolean online = false;
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(SERVER_HOST, SERVER_PORT), 2000);
            online = true;
        } catch (Exception ignored) {}
        cachedServerOnline = online;
        final boolean isOnline = online;
        SwingUtilities.invokeLater(() -> applyServerState(isOnline));
    }

    private void applyServerState(boolean isOnline) {
        if (serverDot == null || serverStatusLbl == null) return;
        Color onlineColor = new Color(52, 211, 153); 
        if (isOnline) {
            serverDot.setForeground(onlineColor);
            serverStatusLbl.setText("Serveur en ligne");
            serverStatusLbl.setForeground(onlineColor);
        } else {
            serverDot.setForeground(new Color(239, 68, 68));
            serverStatusLbl.setText("Serveur hors ligne");
            serverStatusLbl.setForeground(new Color(239, 68, 68));
        }
        serverDot.setToolTipText(isOnline ? "Connecté à " + SERVER_HOST : "Impossible de joindre " + SERVER_HOST);
    }

    // ── Carte Auth Microsoft ─────────────────────────────────────
    private JPanel buildAuthCard() {
        JPanel card = new JPanel(new BorderLayout(10, 0)) {
            @Override protected void paintComponent(Graphics g) {
                paintGlassCard(g, getWidth(), getHeight());
                super.paintComponent(g);
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(8, 12, 8, 12));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        card.setAlignmentX(LEFT_ALIGNMENT);

        JLabel icon = new JLabel("\u25A3");
        icon.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 22));
        icon.setForeground(TEXT_DIM);
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        icon.setPreferredSize(new Dimension(28, 28));

        JPanel txt = new JPanel(new GridLayout(2, 1, 0, 1));
        txt.setOpaque(false);
        JLabel topLbl = new JLabel("COMPTE MICROSOFT");
        topLbl.setFont(new Font("Segoe UI", Font.BOLD, 9));
        topLbl.setForeground(TEXT_DIM);
        authStatusLbl = new JLabel(config.hasMsaProfile() ? config.getMsaUsername() : "Non connecté");
        authStatusLbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        authStatusLbl.setForeground(config.hasMsaProfile() ? new Color(52, 211, 153) : TEXT);
        txt.add(topLbl);
        txt.add(authStatusLbl);

        authActionBtn = new JButton(config.hasMsaProfile() ? "\u2715" : "\u2192") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(getModel().isPressed() ? ACCENT : new Color(50, 50, 60, 180));
                g2.fillOval(0, 0, getWidth()-1, getHeight()-1);
                
                java.awt.Font font = new Font("Segoe UI Symbol", Font.PLAIN, 16);
                g2.setFont(font);
                g2.setColor(getModel().isPressed() ? Color.WHITE : new Color(220, 220, 220));
                String icon = getText();
                java.awt.font.TextLayout tl = new java.awt.font.TextLayout(icon, font, g2.getFontRenderContext());
                java.awt.geom.Rectangle2D vb = tl.getBounds();
                int x = (int) Math.round((getWidth()  - vb.getWidth())  / 2.0 - vb.getX());
                int y = (int) Math.round((getHeight() - vb.getHeight()) / 2.0 - vb.getY());
                g2.drawString(icon, x, y);
                g2.dispose();
            }
        };
        authActionBtn.setPreferredSize(new Dimension(32, 32));
        authActionBtn.setToolTipText(config.hasMsaProfile() ? "Déconnecter" : "Se connecter avec Microsoft");
        authActionBtn.setFocusPainted(false);
        authActionBtn.setContentAreaFilled(false);
        authActionBtn.setBorderPainted(false);
        authActionBtn.setOpaque(false);
        authActionBtn.addActionListener(e -> {
            if (config.hasMsaProfile()) {
                int confirm = JOptionPane.showConfirmDialog(this,
                    "Déconnecter " + config.getMsaUsername() + " ?",
                    "Déconnexion Microsoft", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    config.clearMsaProfile();
                    updateAuthCard(null);
                }
            } else {
                showMicrosoftLoginDialog(null);
            }
        });

        card.add(icon, BorderLayout.WEST);
        card.add(txt,  BorderLayout.CENTER);
        JPanel btnWrap = new JPanel(new GridBagLayout());
        btnWrap.setOpaque(false);
        btnWrap.add(authActionBtn);
        card.add(btnWrap, BorderLayout.EAST);
        return card;
    }

    void updateAuthCard(MicrosoftAuth.McProfile profile) {
        SwingUtilities.invokeLater(() -> {
            if (authStatusLbl == null || authActionBtn == null) return;
            if (profile != null) {
                authStatusLbl.setText(profile.username);
                authStatusLbl.setForeground(new Color(52, 211, 153));
                authActionBtn.setText("\u2715");
                authActionBtn.setToolTipText("Déconnecter " + profile.username);
            } else {
                authStatusLbl.setText("Non connecté");
                authStatusLbl.setForeground(TEXT);
                authActionBtn.setText("\u2192");
                authActionBtn.setToolTipText("Se connecter avec Microsoft");
            }
            authStatusLbl.repaint();
            authActionBtn.repaint();
        });
    }

    private void showMicrosoftLoginDialog(Runnable onSuccess) {
        String clientId = config != null ? config.getClientId() : "";
        if (clientId == null || clientId.isBlank()) {
            JOptionPane.showMessageDialog(this,
                "Connexion Microsoft indisponible : msaClientId non configuré dans launcher_config.json.",
                "Connexion Microsoft",
                JOptionPane.WARNING_MESSAGE);
            appendLog("[MSA] Client ID absent: msaClientId non configuré.");
            return;
        }

        authActionBtn.setEnabled(false);
        appendLog("[MSA] Démarrage du Device Code Flow...");
        new Thread(() -> {
            MicrosoftAuth.DeviceCodeResult dcr;
            try {
                dcr = MicrosoftAuth.requestDeviceCode();
            } catch (Exception ex) {
                appendLog("[MSA] Erreur : " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this,
                        "Échec de la connexion Microsoft :\n" + ex.getMessage(),
                        "Connexion Microsoft",
                        JOptionPane.ERROR_MESSAGE);
                    authActionBtn.setEnabled(true);
                });
                return;
            }

            SwingUtilities.invokeLater(() -> openMicrosoftDeviceDialog(dcr, onSuccess));
        }, "msa-device-code-start").start();
    }

    private void openMicrosoftDeviceDialog(MicrosoftAuth.DeviceCodeResult dcr, Runnable onSuccess) {
        JDialog dialog = new JDialog(this, "Connexion Microsoft", true);
        dialog.setSize(430, 285);
        dialog.setLocationRelativeTo(this);
        dialog.setResizable(false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(new Color(25, 25, 30));
        root.setBorder(new EmptyBorder(20, 24, 16, 24));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets  = new java.awt.Insets(4, 0, 4, 0);
        gc.fill    = GridBagConstraints.HORIZONTAL;
        gc.gridx   = 0;
        gc.weightx = 1;

        JLabel title = new JLabel("Connexion à votre compte Microsoft");
        title.setFont(new Font("Segoe UI", Font.BOLD, 14));
        title.setForeground(Color.WHITE);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        gc.gridy = 0;
        root.add(title, gc);

        JLabel instr = new JLabel("<html><center>Ouvrez <b style='color:#3b82f6'>" + dcr.verificationUri
            + "</b><br>et saisissez le code ci-dessous&nbsp;:</center></html>");
        instr.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        instr.setForeground(new Color(200, 200, 200));
        instr.setHorizontalAlignment(SwingConstants.CENTER);
        gc.gridy = 1; gc.insets = new java.awt.Insets(4, 0, 8, 0);
        root.add(instr, gc);

        JLabel codeLabel = new JLabel(dcr.userCode);
        codeLabel.setFont(new Font("Consolas", Font.BOLD, 28));
        codeLabel.setForeground(ACCENT);
        codeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        codeLabel.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(ACCENT, 1, true),
            new EmptyBorder(6, 16, 6, 16)
        ));
        gc.gridy = 2; gc.insets = new java.awt.Insets(0, 40, 8, 40);
        root.add(codeLabel, gc);

        JPanel btns = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 10, 0));
        btns.setOpaque(false);
        JButton copyBtn   = mkButton("Copier le code", new Color(50, 50, 55), Color.WHITE, 12, 32);
        JButton openBtn   = mkButton("Ouvrir", new Color(50, 50, 55), Color.WHITE, 12, 32);
        JButton cancelBtn = mkButton("Annuler", new Color(50, 50, 55), Color.WHITE, 12, 32);
        btns.add(copyBtn); btns.add(openBtn); btns.add(cancelBtn);

        copyBtn.addActionListener(ev -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(dcr.userCode), null);
            copyBtn.setText("\u2713 Copié !");
        });
        openBtn.addActionListener(ev -> {
            try { java.awt.Desktop.getDesktop().browse(new java.net.URI(dcr.verificationUri)); }
            catch (Exception ignored) {}
        });
        gc.gridy = 3; gc.insets = new java.awt.Insets(4, 0, 0, 0);
        root.add(btns, gc);

        JLabel pollLbl = new JLabel("En attente de votre validation...");
        pollLbl.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        pollLbl.setForeground(new Color(150, 150, 150));
        pollLbl.setHorizontalAlignment(SwingConstants.CENTER);
        gc.gridy = 4; gc.insets = new java.awt.Insets(8, 0, 0, 0);
        root.add(pollLbl, gc);

        dialog.setContentPane(root);

        boolean[] cancelled = {false};
        cancelBtn.addActionListener(ev -> { cancelled[0] = true; dialog.dispose(); });
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent ev) { cancelled[0] = true; }
        });

        new Thread(() -> {
            try {
                MicrosoftAuth.McProfile profile = MicrosoftAuth.pollAndAuthenticate(dcr, () -> cancelled[0]);
                config.saveMsaProfile(profile);
                appendLog("[MSA] Connecté en tant que : " + profile.username);
                updateAuthCard(profile);
                SwingUtilities.invokeLater(() -> {
                    pollLbl.setText("\u2713 Connecté : " + profile.username);
                    pollLbl.setForeground(new Color(52, 211, 153));
                });
                Thread.sleep(800);
                SwingUtilities.invokeLater(() -> {
                    dialog.dispose();
                    if (onSuccess != null) onSuccess.run();
                });
            } catch (InterruptedException ignored) {
                appendLog("[MSA] Connexion annulée.");
            } catch (Exception ex) {
                appendLog("[MSA] Erreur : " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    pollLbl.setText("Erreur : " + ex.getMessage());
                    pollLbl.setForeground(new Color(239, 68, 68));
                });
            } finally {
                SwingUtilities.invokeLater(() -> authActionBtn.setEnabled(true));
            }
        }, "msa-device-code-poll").start();

        dialog.setVisible(true);
    }

    private JPanel infoCard(String title, JLabel valueLabel) {
        JPanel card = new JPanel(new GridLayout(2, 1, 0, 2)) {
            @Override protected void paintComponent(Graphics g) {
                paintGlassCard(g, getWidth(), getHeight());
                super.paintComponent(g);
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(6, 12, 6, 12));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        card.setAlignmentX(LEFT_ALIGNMENT);

        JLabel t = new JLabel(title.toUpperCase());
        t.setFont(new Font("Segoe UI", Font.BOLD, 9));
        t.setForeground(TEXT_DIM);
        card.add(t);
        card.add(valueLabel);
        return card;
    }

    private JPanel versionCapsuleContainer;

    private JPanel buildVersionCapsule() {
        versionCapsuleContainer = new JPanel(new BorderLayout());
        versionCapsuleContainer.setOpaque(false);
        versionCapsuleContainer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        versionCapsuleContainer.setAlignmentX(LEFT_ALIGNMENT);

        launcherStatusLabel = new JLabel("v" + config.getLauncherVersion());
        launcherStatusLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        launcherStatusLabel.setForeground(TEXT_DIM);
        applyLauncherButton = new JButton(); 
        applyLauncherButton.setVisible(false);

        versionCapsuleContainer.add(buildUpToDateCapsule("v" + config.getLauncherVersion(), false), BorderLayout.CENTER);
        return versionCapsuleContainer;
    }

    private JPanel buildUpToDateCapsule(String version, boolean confirmed) {
        Color jade    = new Color(52, 211, 153);
        JPanel cap = new JPanel(new BorderLayout(8, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(20, 30, 25, 140));
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
                g2.setColor(new Color(52, 211, 153, 80));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
                g2.dispose();
            }
        };
        cap.setOpaque(false);
        cap.setBorder(new EmptyBorder(7, 10, 7, 10));
        cap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));

        JLabel icon = new JLabel(confirmed ? "\u2713" : "\u25cf");
        icon.setFont(new Font("Segoe UI Symbol", Font.BOLD, 14));
        icon.setForeground(jade);

        JLabel lbl = new JLabel(confirmed ? version + "  —  À jour" : version);
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lbl.setForeground(jade);

        cap.add(icon, BorderLayout.WEST);
        cap.add(lbl,  BorderLayout.CENTER);
        return cap;
    }

    private JPanel buildUpdateAvailableCapsule(String oldVer, String newVer) {
        JPanel cap = new JPanel(new BorderLayout(0, 6)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(40, 25, 10, 160)); 
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16);
                g2.setPaint(new GradientPaint(0, 0, new Color(225,170,60,180), getWidth(), 0, new Color(225,170,60,20)));
                g2.fillRoundRect(0, 0, getWidth()-1, 2, 16, 16);
                g2.setColor(new Color(225, 160, 50, 80));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16);
                g2.dispose();
            }
        };
        cap.setOpaque(false);
        cap.setBorder(new EmptyBorder(8, 10, 8, 10));
        cap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));

        JLabel title = new JLabel("\u25b2  Mise à jour disponible");
        title.setFont(new Font("Segoe UI Symbol", Font.BOLD, 11));
        title.setForeground(new Color(235, 185, 70));

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);

        JLabel versions = new JLabel("v" + oldVer + "  →  v" + newVer);
        versions.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        versions.setForeground(new Color(220, 200, 155));

        applyLauncherButton = mkButton("Installer", new Color(190, 130, 30), Color.WHITE, 10, 24);
        applyLauncherButton.setFont(new Font("Segoe UI", Font.BOLD, 11));
        applyLauncherButton.addActionListener(e -> {
            applyLauncherButton.setEnabled(false);
            applyLauncherButton.setText("...");
            new Thread(() -> {
                try {
                    JSONObject info = updater.getLauncherInfo();
                    if (updater.downloadAndInstallLauncher(info)) {
                        appendLog("Mise à jour prête. Redémarrage...");
                        try { Thread.sleep(1000); } catch (Exception ignored) {}
                        System.exit(0);
                    }
                } catch (Exception ex) {
                    appendLog("Erreur MAJ launcher : " + ex.getMessage());
                    SwingUtilities.invokeLater(() -> {
                        applyLauncherButton.setEnabled(true);
                        applyLauncherButton.setText("Réessayer");
                    });
                }
            }).start();
        });

        row.add(versions,          BorderLayout.CENTER);
        row.add(applyLauncherButton, BorderLayout.EAST);

        cap.add(title, BorderLayout.NORTH);
        cap.add(row,   BorderLayout.SOUTH);
        return cap;
    }

    // ── Zone principale (Main Area) ───────────────────────────────────────────
    private JPanel buildMainArea() {
        JPanel main = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int w = getWidth(), h = getHeight();

                if (bannerImg != null) {
                    // Centrage de l'image dans la zone principale uniquement
                    double scale = Math.max((double) w / bannerImg.getWidth(), (double) h / bannerImg.getHeight());
                    int imgW = (int)(bannerImg.getWidth()  * scale);
                    int imgH = (int)(bannerImg.getHeight() * scale);
                    int xOff = (w - imgW) / 2;
                    // Légèrement calé en haut pour garder les personnages visibles
                    int yOff = Math.min(0, (h - imgH) / 2);
                    g2.drawImage(bannerImg, xOff, yOff, imgW, imgH, null);
                } else {
                    g2.setColor(BG);
                    g2.fillRect(0, 0, w, h);
                }

                // Voile dégradé gauche : réduit en mode clair (SIDEBAR1 blanc sinon trop visible)
                boolean isDark = (BG.getRed() + BG.getGreen() + BG.getBlue()) < 200;
                int veilW   = isDark ? 55 : 20;
                int veilAlpha = isDark ? 180 : 120;
                g2.setPaint(new GradientPaint(
                    0, 0, new Color(SIDEBAR1.getRed(), SIDEBAR1.getGreen(), SIDEBAR1.getBlue(), veilAlpha),
                    veilW, 0, new Color(0, 0, 0, 0)));
                g2.fillRect(0, 0, veilW, h);

                // Voile sombre en bas pour lisibilité de la barre
                int r = BG.getRed(), gv = BG.getGreen(), b = BG.getBlue();
                g2.setPaint(new GradientPaint(0, h - 130, new Color(r, gv, b, 0), 0, h, new Color(r, gv, b, 210)));
                g2.fillRect(0, h - 130, w, 130);

                g2.dispose();
            }
        };
        main.setOpaque(false);

        JPanel topRow = new JPanel(new BorderLayout());
        topRow.setOpaque(false);
        topRow.setBorder(new EmptyBorder(12, 12, 12, 12));
        topRow.add(buildReleaseNotesBadge(), BorderLayout.WEST);
        topRow.add(buildZokkyenBadge(), BorderLayout.EAST);

        main.add(topRow, BorderLayout.NORTH);
        main.add(buildBottomBar(), BorderLayout.SOUTH);
        return main;
    }

    private JComponent buildZokkyenBadge() {
        boolean[] badgeHov = {false};
        String BADGE_TEXT = "Zokkyen GitHub \u2192";
        JComponent zokkyenBadge = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                int w = getWidth(), h = getHeight();
                if (w == 0 || h == 0) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,     RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                
                boolean isDark = (BG.getRed() + BG.getGreen() + BG.getBlue()) < 200;
                // Fond adapté au mode : sombre opaque en dark, clair avec légère teinte en light
                Color bgCol = isDark
                    ? new Color(20, 20, 25, badgeHov[0] ? 210 : 140)
                    : new Color(CARD_BG.getRed(), CARD_BG.getGreen(), CARD_BG.getBlue(), badgeHov[0] ? 245 : 200);
                Color acc = ACCENT;
                int arc = h;

                g2.setColor(bgCol);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                g2.setColor(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), badgeHov[0] ? 255 : 160));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);

                Font f = new Font("Segoe UI", Font.BOLD, 12);
                g2.setFont(f);
                g2.setColor(badgeHov[0] ? acc.brighter() : acc);
                FontMetrics fm = g2.getFontMetrics();
                int tx = 14;
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(BADGE_TEXT, tx, ty);
                g2.dispose();
            }
            @Override public Dimension getPreferredSize() {
                FontMetrics fm = getFontMetrics(new Font("Segoe UI", Font.BOLD, 12));
                return new Dimension(fm.stringWidth(BADGE_TEXT) + 28, fm.getHeight() + 14);
            }
        };
        zokkyenBadge.setOpaque(false);
        zokkyenBadge.setCursor(new Cursor(Cursor.HAND_CURSOR));
        zokkyenBadge.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                try { java.awt.Desktop.getDesktop().browse(
                    new java.net.URI("https://github.com/Zokkyen/Launcher-Zokkymon/tree/main")); }
                catch (Exception ignored) {}
            }
            public void mouseEntered(MouseEvent e) { badgeHov[0] = true;  zokkyenBadge.repaint(); }
            public void mouseExited (MouseEvent e) { badgeHov[0] = false; zokkyenBadge.repaint(); }
        });
        zokkyenBadge.setSize(zokkyenBadge.getPreferredSize());
        return zokkyenBadge;
    }

    private JComponent buildReleaseNotesBadge() {
        boolean[] badgeHov = {false};
        String BADGE_TEXT = "Release Notes \u2192";
        JComponent releaseBadge = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                int w = getWidth(), h = getHeight();
                if (w == 0 || h == 0) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,     RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                boolean isDark = (BG.getRed() + BG.getGreen() + BG.getBlue()) < 200;
                Color bgCol = isDark
                    ? new Color(20, 20, 25, badgeHov[0] ? 210 : 140)
                    : new Color(CARD_BG.getRed(), CARD_BG.getGreen(), CARD_BG.getBlue(), badgeHov[0] ? 245 : 200);
                Color acc = ACCENT;
                int arc = h;

                g2.setColor(bgCol);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                g2.setColor(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), badgeHov[0] ? 255 : 160));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);

                Font f = new Font("Segoe UI", Font.BOLD, 12);
                g2.setFont(f);
                g2.setColor(badgeHov[0] ? acc.brighter() : acc);
                FontMetrics fm = g2.getFontMetrics();
                int tx = 14;
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(BADGE_TEXT, tx, ty);
                g2.dispose();
            }

            @Override public Dimension getPreferredSize() {
                FontMetrics fm = getFontMetrics(new Font("Segoe UI", Font.BOLD, 12));
                return new Dimension(fm.stringWidth(BADGE_TEXT) + 28, fm.getHeight() + 14);
            }
        };
        releaseBadge.setOpaque(false);
        releaseBadge.setCursor(new Cursor(Cursor.HAND_CURSOR));
        releaseBadge.setToolTipText("Voir les nouveautés launcher + modpack");
        releaseBadge.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { showChangelogDialog(); }
            public void mouseEntered(MouseEvent e) { badgeHov[0] = true;  releaseBadge.repaint(); }
            public void mouseExited (MouseEvent e) { badgeHov[0] = false; releaseBadge.repaint(); }
        });
        releaseBadge.setSize(releaseBadge.getPreferredSize());
        return releaseBadge;
    }

    // ── BARRE INFÉRIEURE : AFFINÉE ET COLLÉE ─────────────────────────────────
    private JPanel buildBottomBar() {
        GlassPanel bottomPanel = new GlassPanel(18, CARD_BG);
        bottomPanel.setLayout(new BorderLayout(15, 0));
        bottomPanel.setBorder(new EmptyBorder(10, 15, 10, 15));

        // GAUCHE : Statut + Barre compacte (zone bornée pour éviter tout chevauchement)
        JPanel leftZone = new JPanel();
        leftZone.setLayout(new BoxLayout(leftZone, BoxLayout.Y_AXIS));
        leftZone.setOpaque(false);
        leftZone.setPreferredSize(new Dimension(230, 38));
        leftZone.setMinimumSize(new Dimension(210, 38));

        statusLabel = new JLabel("Initialisation...");
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        statusLabel.setForeground(TEXT);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        progressBar = new JProgressBar(0, 100) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Fond barre
                g2.setColor(new Color(TEXT_DIM.getRed(), TEXT_DIM.getGreen(), TEXT_DIM.getBlue(), 40));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
                // Progression
                int val = getValue();
                if (val > 0) {
                    g2.setPaint(new GradientPaint(0, 0, ACCENT, getWidth(), 0, ACCENT.brighter()));
                    g2.fillRoundRect(0, 0, (int)(getWidth() * (val / 100.0)), getHeight(), 4, 4);
                }
                g2.dispose();
            }
        };
        progressBar.setPreferredSize(new Dimension(220, 6));
        progressBar.setMinimumSize(new Dimension(220, 6));
        progressBar.setMaximumSize(new Dimension(220, 6));
        progressBar.setOpaque(false);
        progressBar.setBorderPainted(false);
        progressBar.setStringPainted(false);

        JPanel progressWrap = new JPanel(new BorderLayout());
        progressWrap.setOpaque(false);
        progressWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        progressWrap.setMaximumSize(new Dimension(220, 6));
        progressWrap.add(progressBar, BorderLayout.WEST);

        leftZone.add(statusLabel);
        leftZone.add(Box.createVerticalStrut(4));
        leftZone.add(progressWrap);

        // DROITE : Boutons Compacts
        JPanel rightZone = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightZone.setOpaque(false);

        Color btnBg = new Color(0, 0, 0, 15);
        JButton logsBtn = mkButton("Console", btnBg, TEXT, 10, 32);
        logsBtn.setToolTipText("Journal d'exploitation Zokkymon");
        logsBtn.addActionListener(e -> showLogsDialog());

        JButton settingsBtn = mkButton("Paramètres", btnBg, TEXT, 10, 32);
        settingsBtn.setToolTipText("Paramètres avancés du launcher");
        settingsBtn.addActionListener(e -> openSettings());

        Dimension compactActionBtn = new Dimension(120, 32);
        settingsBtn.setPreferredSize(compactActionBtn);
        logsBtn.setPreferredSize(compactActionBtn);

        playButton = new JButton("JOUER") {
            boolean hovered = false;
            {
                addMouseListener(new MouseAdapter() {
                    public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
                    public void mouseExited (MouseEvent e) { hovered = false; repaint(); }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (isEnabled()) {
                    if (hovered) {
                        g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 50));
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 15, 15);
                    }
                    g2.setPaint(new GradientPaint(0, 0, ACCENT.brighter(), 0, getHeight(), ACCENT));
                    g2.fillRoundRect(hovered?1:2, hovered?1:2, getWidth()-(hovered?2:4), getHeight()-(hovered?2:4), 14, 14);
                    g2.setColor(new Color(255, 255, 255, hovered ? 50 : 25));
                    g2.fillRoundRect(4, 3, getWidth()-8, (getHeight()-6)/2, 10, 10);
                } else {
                    g2.setColor(new Color(100, 100, 110, 150));
                    g2.fillRoundRect(2, 2, getWidth()-4, getHeight()-4, 14, 14);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        playButton.setFont(new Font("Segoe UI", Font.BOLD, 15));
        playButton.setForeground(Color.WHITE);
        playButton.setPreferredSize(new Dimension(140, 40));
        playButton.setContentAreaFilled(false);
        playButton.setBorderPainted(false);
        playButton.setFocusPainted(false);
        playButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        playButton.setEnabled(false);
        playButton.addActionListener(e -> handlePlayButton());

        rightZone.add(settingsBtn);
        rightZone.add(logsBtn);
        rightZone.add(playButton);

        bottomPanel.add(leftZone, BorderLayout.WEST);
        bottomPanel.add(rightZone, BorderLayout.EAST);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(new EmptyBorder(0, 15, 15, 15));
        wrapper.add(bottomPanel, BorderLayout.CENTER);
        return wrapper;
    }

    // ── Boîte de dialogue des Logs ───────────────────────────────────────────
    private void showLogsDialog() {
        JDialog dialog = new JDialog(this, "Zokkymon • Console d'exploitation", false);
        dialog.setSize(750, 450);
        dialog.setLocationRelativeTo(this);
        
        JPanel cp = new JPanel(new BorderLayout());
        cp.setBackground(BG);
        cp.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel logsHeader = new JLabel("Supervision en temps réel — session active");
        logsHeader.setFont(new Font("Segoe UI", Font.BOLD, 12));
        logsHeader.setForeground(TEXT_DIM);
        logsHeader.setBorder(new EmptyBorder(0, 2, 8, 2));
        cp.add(logsHeader, BorderLayout.NORTH);

        cp.add(logScrollPane, BorderLayout.CENTER);

        JButton exportFullBtn = mkButton("Export complet", CARD_BG, TEXT, 12, 34);
        exportFullBtn.addActionListener(e -> exportLogs(false, dialog));

        JButton exportTargetBtn = mkButton("Export ciblé", CARD_BG, TEXT, 12, 34);
        exportTargetBtn.addActionListener(e -> exportLogs(true, dialog));

        JButton crashReportBtn = mkButton("Rapport incident", CARD_BG, TEXT, 12, 34);
        crashReportBtn.addActionListener(e -> generateCrashReport(dialog));

        JButton openDirBtn = mkButton("Ouvrir archives", CARD_BG, ACCENT, 12, 34);
        openDirBtn.addActionListener(e -> openLogsFolder(dialog));

        JButton cleanBtn = mkButton("Purger anciens logs", CARD_BG, WARNING, 12, 34);
        cleanBtn.addActionListener(e -> cleanupLogsNow(dialog));
        
        JButton closeBtn = mkButton("Fermer", CARD_BG, TEXT, 12, 34);
        closeBtn.addActionListener(e -> dialog.dispose());

        Dimension logsActionBtn = new Dimension(162, 34);
        exportTargetBtn.setPreferredSize(logsActionBtn);
        exportFullBtn.setPreferredSize(logsActionBtn);
        crashReportBtn.setPreferredSize(logsActionBtn);
        openDirBtn.setPreferredSize(logsActionBtn);
        cleanBtn.setPreferredSize(logsActionBtn);
        closeBtn.setPreferredSize(new Dimension(120, 34));
        
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        bp.setOpaque(false);
        bp.add(exportTargetBtn);
        bp.add(exportFullBtn);
        bp.add(crashReportBtn);
        bp.add(openDirBtn);
        bp.add(cleanBtn);
        bp.add(closeBtn);
        cp.add(bp, BorderLayout.SOUTH);
        
        dialog.setContentPane(cp);
        dialog.setVisible(true);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Info-cards — chargement asynchrone
    // ═════════════════════════════════════════════════════════════════════════
    private void initInfoCards() {
        String cached = config.getModpackVersion();
        final String mv = (cached != null && !cached.isBlank()) ? cached : "–";

        String jvRaw = System.getProperty("java.version", "?");
        final String jv = "Java " + jvRaw.split("\\.")[0].replaceAll("[^0-9]", "");

        String rs;
        try {
            com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            long totalGb = Math.round(os.getTotalMemorySize() / 1_073_741_824.0);
            rs = config.getRamAllocation() + " / " + totalGb + " Go";
        } catch (Exception ignored) {
            rs = config.getRamAllocation();
        }
        final String ramStr = rs;

        String ms = "0";
        try {
            File base = new File(config.getInstallPath());
            File[] dirs = base.listFiles(f -> f.isDirectory() && f.getName().startsWith("zokkymon_v"));
            if (dirs != null && dirs.length > 0) {
                File mods = new File(dirs[dirs.length-1], "mods");
                if (mods.exists()) {
                    File[] jars = mods.listFiles(f -> f.getName().endsWith(".jar"));
                    ms = jars != null ? String.valueOf(jars.length) : "0";
                }
            }
        } catch (Exception ignored) {}
        final String modsStr = ms;

        String ds;
        try {
            java.io.File installFile = new java.io.File(config.getInstallPath());
            long freeBytes  = installFile.getUsableSpace();
            long totalBytes = installFile.getTotalSpace();
            if (freeBytes <= 0 || totalBytes <= 0) {
                // fallback sur la partition système
                java.io.File home = new java.io.File(System.getProperty("user.home"));
                freeBytes  = home.getUsableSpace();
                totalBytes = home.getTotalSpace();
            }
                ds = String.format("%.0f Go / %.0f Go",
                    freeBytes  / 1_073_741_824.0,
                    totalBytes / 1_073_741_824.0);
        } catch (Exception ignored) {
            ds = "–";
        }
        final String diskStr = ds;

        SwingUtilities.invokeLater(() -> {
            cachedInfoModpack = mv;
            cachedInfoJava    = jv;
            cachedInfoRam     = ramStr;
            cachedInfoMods    = modsStr;
            cachedInfoDisk    = diskStr;
            infoModpackVal.setText(mv);
            infoJavaVal   .setText(jv);
            infoRamVal    .setText(ramStr);
            infoModsVal   .setText(modsStr);
            infoDiskVal   .setText(diskStr);
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Logique métier
    // ═════════════════════════════════════════════════════════════════════════
    private void handlePlayButton() {
        String t = playButton.getText();
        if (t.contains("INSTALLATION"))    installModpack();
        else if (t.contains("METTRE À JOUR")) updateModpack();
        else if (t.contains("RÉPARER")) repairInstallation();
        else                               launchGame();
    }

    private void checkAndUpdate() {
        if (cachedServerOnline == null) {
            new Thread(this::checkServerOnce).start();
        }
        try {
            appendLog("Initialisation du client Zokkymon — édition opérateur...");
            appendLog("Version Minecraft : " + config.getMinecraftVersion());
            appendLog("Fabric : " + config.getFabricVersion());
            appendLog("─────────────────────────────────────");
            Thread.sleep(400);

            String token = config.getModpackToken();
            if (token == null || token.trim().isEmpty()) {
                setStatus("Configuration manquante");
                appendLog("[ERR] Token d'accès au modpack manquant !");
                SwingUtilities.invokeLater(() -> progressBar.setForeground(DANGER));
                return;
            }

            setStatus("Vérification du modpack...");
            appendLog("[PRÉPARATION] Vérification du modpack...");
            String status = updater.getModpackStatus();
            cachedModpackStatus = status; 
            
            String knownVer = config.getModpackVersion();
            if (knownVer != null && !knownVer.isBlank()) {
                final String verLabel = knownVer;
                SwingUtilities.invokeLater(() -> infoModpackVal.setText(verLabel));
            }

            SwingUtilities.invokeLater(() -> {
                if ("absent".equals(status)) {
                    setProgress(0);
                    setStatus("Installation requise");
                    appendLog("[!] Modpack non trouvé — cliquez sur INSTALLATION");
                    playButton.setText("INSTALLATION");
                    playButton.setBackground(new Color(234, 88, 12));
                    playButton.setEnabled(true);
                } else if ("outdated".equals(status)) {
                    setProgress(0);
                    setStatus("Mise à jour disponible");
                    appendLog("[!] Mise à jour disponible — cliquez sur METTRE À JOUR");
                    playButton.setText("METTRE À JOUR");
                    playButton.setBackground(WARNING);
                    playButton.setEnabled(true);
                } else {
                    setProgress(100);
                    setStatus("Prêt à jouer");
                    appendLog("[OK] Modpack à jour — cliquez sur JOUER !");
                    playButton.setText("JOUER");
                    playButton.setBackground(ACCENT);
                    playButton.setEnabled(true);
                }
            });
        } catch (Exception e) {
            setStatus("Erreur critique");
            appendLog("ERREUR : " + e.getMessage());
            SwingUtilities.invokeLater(() -> progressBar.setForeground(DANGER));
        }
    }

    private void installModpack() {
        playButton.setEnabled(false);
        setStatus("Installation en cours...");
        appendLog("\n>> Téléchargement du modpack...");
        new Thread(() -> {
            try {
                if (!updater.downloadAndExtractModpack()) {
                    setStatus("Erreur d'installation");
                    SwingUtilities.invokeLater(() -> {
                        playButton.setBackground(new Color(234, 88, 12));
                        playButton.setEnabled(true);
                    });
                    return;
                }
                cachedModpackStatus = "uptodate";
                setProgress(100);
                setStatus("Prêt à jouer");
                appendLog("[OK] Installation terminée !");
                SwingUtilities.invokeLater(() -> {
                    playButton.setText("JOUER");
                    playButton.setBackground(ACCENT);
                    playButton.setEnabled(true);
                });
                new Thread(this::initInfoCards).start();
            } catch (Exception e) {
                setStatus("Erreur d'installation");
                appendLog("[ERR] " + e.getMessage());
                SwingUtilities.invokeLater(() -> playButton.setEnabled(true));
            }
        }).start();
    }

    private void updateModpack() {
        playButton.setEnabled(false);
        setStatus("Mise à jour en cours...");
        appendLog("\n>> Mise à jour du modpack...");
        new Thread(() -> {
            try {
                File[] old = new File(config.getInstallPath())
                    .listFiles(d -> d.isDirectory() && d.getName().startsWith("zokkymon_v"));
                if (old != null) for (File d : old) {
                    appendLog("[*] Suppression : " + d.getName());
                    deleteDirectory(d);
                }
                if (!updater.downloadAndExtractModpack()) {
                    setStatus("Erreur de mise à jour");
                    SwingUtilities.invokeLater(() -> {
                        playButton.setBackground(WARNING);
                        playButton.setEnabled(true);
                    });
                    return;
                }
                cachedModpackStatus = "uptodate";
                setProgress(100);
                setStatus("Prêt à jouer");
                appendLog("[OK] Mise à jour terminée !");
                SwingUtilities.invokeLater(() -> {
                    playButton.setText("JOUER");
                    playButton.setBackground(ACCENT);
                    playButton.setEnabled(true);
                });
                new Thread(this::initInfoCards).start();
            } catch (Exception e) {
                setStatus("Erreur de mise à jour");
                appendLog("[ERR] " + e.getMessage());
                SwingUtilities.invokeLater(() -> playButton.setEnabled(true));
            }
        }).start();
    }

    private File findLatestInstalledModpackDir() {
        File base = new File(config.getInstallPath());
        File[] dirs = base.listFiles(d -> d.isDirectory() && d.getName().startsWith("zokkymon_v"));
        if (dirs == null || dirs.length == 0) return null;
        java.util.Arrays.sort(dirs);
        return dirs[dirs.length - 1];
    }

    private void copyRecursively(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            Files.createDirectories(target);
            try (var stream = Files.list(source)) {
                for (Path child : stream.toList()) {
                    copyRecursively(child, target.resolve(child.getFileName()));
                }
            }
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private File getPlayerConfigBackupRoot() {
        return new File(System.getProperty("user.home"), ".zokkymon/backups/player-configs");
    }

    private void backupPlayerConfigs() {
        try {
            File gameDir = findLatestInstalledModpackDir();
            if (gameDir == null) {
                appendLog("[Backup] Aucun modpack installé détecté.");
                JOptionPane.showMessageDialog(this,
                    "Aucun modpack installé détecté.\nInstalle ou lance d'abord le modpack, puis réessaie.",
                    "Sauvegarde configs joueur",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }

            File backupRoot = getPlayerConfigBackupRoot();
            if (!backupRoot.exists()) backupRoot.mkdirs();

            File backupDir = new File(backupRoot, "backup-" + LOG_FILE_TS.format(LocalDateTime.now()));
            backupDir.mkdirs();

            List<String> singleFiles = List.of("options.txt", "optionsof.txt", "keybindings.txt");
            List<String> folders = List.of("shaderpacks", "resourcepacks");

            int copied = 0;
            for (String name : singleFiles) {
                File src = new File(gameDir, name);
                if (src.exists() && src.isFile()) {
                    copyRecursively(src.toPath(), new File(backupDir, name).toPath());
                    copied++;
                }
            }
            for (String name : folders) {
                File src = new File(gameDir, name);
                if (src.exists() && src.isDirectory()) {
                    copyRecursively(src.toPath(), new File(backupDir, name).toPath());
                    copied++;
                }
            }

            if (copied == 0) {
                appendLog("[Backup] Aucune config joueur trouvée à sauvegarder dans: " + gameDir.getAbsolutePath());
                JOptionPane.showMessageDialog(this,
                    "Aucun élément joueur trouvé à sauvegarder.\n"
                    + "Éléments attendus : options.txt, optionsof.txt, keybindings.txt, shaderpacks/, resourcepacks/",
                    "Sauvegarde configs joueur",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            appendLog("[Backup] Sauvegarde configs joueur terminée: " + backupDir.getAbsolutePath() + " (" + copied + " élément(s)).");
            JOptionPane.showMessageDialog(this,
                "Sauvegarde terminée dans :\n" + backupDir.getAbsolutePath()
                + "\n\nÉléments inclus : options.txt, optionsof.txt, keybindings.txt, shaderpacks/, resourcepacks/"
                + "\nNombre d'éléments copiés : " + copied,
                "Sauvegarde configs joueur",
                JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            appendLog("[Backup] Échec sauvegarde: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                "Échec sauvegarde : " + e.getMessage(),
                "Sauvegarde configs joueur",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private void restoreLatestPlayerConfigsBackup() {
        try {
            File gameDir = findLatestInstalledModpackDir();
            if (gameDir == null) {
                appendLog("[Backup] Aucun modpack installé détecté.");
                JOptionPane.showMessageDialog(this,
                    "Aucun modpack installé détecté.\nInstalle ou lance d'abord le modpack, puis réessaie.",
                    "Restauration configs joueur",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            File backupRoot = getPlayerConfigBackupRoot();
            File[] backups = backupRoot.listFiles(File::isDirectory);
            if (backups == null || backups.length == 0) {
                JOptionPane.showMessageDialog(this,
                    "Aucune sauvegarde trouvée dans :\n" + backupRoot.getAbsolutePath(),
                    "Restauration configs joueur",
                    JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Arrays.sort(backups, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            File latest = backups[0];

            int confirm = JOptionPane.showConfirmDialog(this,
                "Restaurer la dernière sauvegarde ?\n" + latest.getName(),
                "Restauration configs joueur",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;

            try (var stream = Files.list(latest.toPath())) {
                for (Path child : stream.toList()) {
                    copyRecursively(child, gameDir.toPath().resolve(child.getFileName()));
                }
            }

            appendLog("[Backup] Restauration effectuée depuis: " + latest.getAbsolutePath());
            JOptionPane.showMessageDialog(this,
                "Restauration terminée depuis :\n" + latest.getAbsolutePath(),
                "Restauration configs joueur",
                JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            appendLog("[Backup] Échec restauration: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                "Échec restauration : " + e.getMessage(),
                "Restauration configs joueur",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    private String readUrlText(String urlStr) throws IOException {
        URLConnection connection = URI.create(urlStr).toURL().openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        if (connection instanceof HttpURLConnection http) {
            int code = http.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new IOException("HTTP " + code + " sur " + urlStr);
            }
        }
        try (InputStream is = connection.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private JSONObject fetchModpackInfoJson() throws Exception {
        String token = config.getModpackToken();
        String url = config.getModpackInfoUrl();
        if (token != null && !token.isBlank()) {
            url = url + (url.contains("?") ? "&" : "?")
                + "token=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8);
        }
        return new JSONObject(readUrlText(url));
    }

    private void showChangelogDialog() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Changelog Launcher ===\n");
        try {
            JSONObject launcherInfo = updater.getLauncherInfo();
            sb.append("Version distante: ").append(launcherInfo.optString("version", "?"))
              .append("\n");
            String launcherNotes = launcherInfo.optString("changelog", "");
            if (!launcherNotes.isBlank()) sb.append("\n").append(launcherNotes).append("\n");
            else sb.append("(Pas de champ 'changelog' dans info.json launcher)\n");
        } catch (Exception e) {
            sb.append("Impossible de lire le changelog launcher: ").append(e.getMessage()).append("\n");
        }

        sb.append("\n=== Changelog Modpack ===\n");
        try {
            JSONObject modpackInfo = fetchModpackInfoJson();
            sb.append("Version distante: ").append(modpackInfo.optString("version", "?"))
              .append("\n");
            String modpackNotes = modpackInfo.optString("changelog", "");
            if (!modpackNotes.isBlank()) sb.append("\n").append(modpackNotes).append("\n");
            else sb.append("(Pas de champ 'changelog' dans info.json modpack)\n");
        } catch (Exception e) {
            sb.append("Impossible de lire le changelog modpack: ").append(e.getMessage()).append("\n");
        }

        JDialog dialog = new JDialog(this, "Zokkymon • Release Notes", false);
        dialog.setSize(780, 470);
        dialog.setLocationRelativeTo(this);

        JTextArea area = new JTextArea(sb.toString());
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBackground(CONSOLE_BG);
        area.setForeground(TEXT);
        area.setFont(new Font("Consolas", Font.PLAIN, 12));
        area.setBorder(new EmptyBorder(10, 10, 10, 10));

        JScrollPane sp = new JScrollPane(area);
        sp.setBorder(BorderFactory.createLineBorder(
            new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
        sp.getViewport().setBackground(CONSOLE_BG);

        JLabel header = new JLabel("Historique des évolutions launcher + modpack");
        header.setFont(new Font("Segoe UI", Font.BOLD, 12));
        header.setForeground(TEXT_DIM);
        header.setBorder(new EmptyBorder(0, 2, 8, 2));

        JButton closeBtn = mkButton("Fermer", CARD_BG, TEXT, 12, 34);
        closeBtn.setPreferredSize(new Dimension(120, 34));
        closeBtn.addActionListener(e -> dialog.dispose());

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 10));
        bottom.setOpaque(false);
        bottom.add(closeBtn);

        JPanel cp = new JPanel(new BorderLayout());
        cp.setBackground(BG);
        cp.setBorder(new EmptyBorder(10, 10, 10, 10));
        cp.add(header, BorderLayout.NORTH);
        cp.add(sp, BorderLayout.CENTER);
        cp.add(bottom, BorderLayout.SOUTH);

        dialog.setContentPane(cp);
        dialog.setVisible(true);
    }

    private String normalizeModKey(String fileName) {
        String key = fileName.toLowerCase().replace(".jar", "");
        key = key.replaceAll("-\\d[\\w\\.-]*$", "");
        key = key.replaceAll("_(fabric|forge|quilt)$", "");
        return key;
    }

    private void runModsSanityScan(File gameDir) {
        try {
            File modsDir = new File(gameDir, "mods");
            if (!modsDir.exists() || !modsDir.isDirectory()) {
                appendLog("[ModsScan] Dossier mods absent, scan ignoré.");
                return;
            }

            File[] jars = modsDir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
            if (jars == null || jars.length == 0) {
                appendLog("[ModsScan] Aucun mod détecté.");
                return;
            }

            Map<String, List<File>> byExact = new HashMap<>();
            Map<String, List<File>> byKey = new HashMap<>();
            List<String> incompatibleHints = new ArrayList<>();

            for (File jar : jars) {
                String exact = jar.getName().toLowerCase();
                byExact.computeIfAbsent(exact, k -> new ArrayList<>()).add(jar);

                String key = normalizeModKey(jar.getName());
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(jar);

                String n = jar.getName().toLowerCase();
                if (n.contains("forge") || n.contains("neoforge") || n.contains("quilt")) {
                    incompatibleHints.add(jar.getName());
                }
            }

            boolean hasWarnings = false;
            for (Map.Entry<String, List<File>> e : byExact.entrySet()) {
                if (e.getValue().size() > 1) {
                    hasWarnings = true;
                    appendLog("[ModsScan][WARN] Doublon exact: " + e.getKey() + " x" + e.getValue().size());
                }
            }

            for (Map.Entry<String, List<File>> e : byKey.entrySet()) {
                if (e.getValue().size() > 1) {
                    hasWarnings = true;
                    String names = e.getValue().stream().map(File::getName).reduce((a, b) -> a + ", " + b).orElse("");
                    appendLog("[ModsScan][WARN] Doublon probable (versions): " + names);
                    appendLog("[ModsScan][SUGGEST] Garder la version la plus récente et retirer les autres.");
                }
            }

            if (!incompatibleHints.isEmpty()) {
                hasWarnings = true;
                appendLog("[ModsScan][WARN] Mods potentiellement incompatibles Fabric détectés :");
                for (String n : incompatibleHints) appendLog("  - " + n);
                appendLog("[ModsScan][SUGGEST] Vérifier la variante Fabric de ces mods.");
            }

            if (!hasWarnings) {
                appendLog("[ModsScan] OK — pas de doublon/incompatibilité évidente.");
            }
        } catch (Exception e) {
            appendLog("[ModsScan] Scan ignoré: " + e.getMessage());
        }
    }

    private void repairInstallation() {
        playButton.setEnabled(false);
        setStatus("Réparation en cours...");
        appendLog("\n>> Réparation ciblée de l'installation...");

        new Thread(() -> {
            try {
                File gameDir = findLatestInstalledModpackDir();
                if (gameDir == null) {
                    setStatus("Réparation impossible");
                    appendLog("[Repair] Aucun modpack installé à réparer.");
                    SwingUtilities.invokeLater(() -> {
                        playButton.setText("INSTALLATION");
                        playButton.setBackground(new Color(234, 88, 12));
                        playButton.setEnabled(true);
                    });
                    return;
                }

                appendLog("[Repair] Cible : " + gameDir.getName());
                Launcher.repairInstallation(config, this, gameDir.getAbsolutePath());

                setStatus("Réparation terminée");
                setProgress(100);
                appendLog("[OK] Réparation terminée avec succès.");
                SwingUtilities.invokeLater(() -> {
                    playButton.setText("JOUER");
                    playButton.setBackground(ACCENT);
                });
            } catch (Exception e) {
                setStatus("Erreur de réparation");
                appendLog("[ERR] Réparation échouée : " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    playButton.setText("RÉPARER");
                    playButton.setBackground(WARNING);
                });
            } finally {
                SwingUtilities.invokeLater(() -> playButton.setEnabled(true));
                new Thread(this::initInfoCards).start();
            }
        }).start();
    }

    private void applyLaunchProfilePreset(String profile) {
        String p = profile == null ? "performance" : profile;
        switch (p) {
            case "quality" -> {
                config.setRamAllocation("10 Go");
                config.setFullscreen(true);
                config.setWindowSize(1920, 1080);
                config.setCustomJvmArgs("-XX:MaxGCPauseMillis=180");
            }
            case "low-end" -> {
                config.setRamAllocation("4 Go");
                config.setFullscreen(false);
                config.setWindowSize(1280, 720);
                config.setCustomJvmArgs("-XX:MaxGCPauseMillis=250 -XX:G1HeapRegionSize=4M");
            }
            case "custom" -> {
                // Aucun preset imposé
            }
            default -> {
                config.setRamAllocation("8 Go");
                config.setFullscreen(false);
                config.setWindowSize(1600, 900);
                config.setCustomJvmArgs("-XX:MaxGCPauseMillis=150");
            }
        }
    }

    private void launchGame() {
        if (!config.hasMsaProfile()) {
            showPlayModeDialog();
            return;
        }
        doLaunchGame();
    }

    private void showPlayModeDialog() {
        JDialog dialog = new JDialog(this, "Mode de jeu", true);
        dialog.setUndecorated(true);
        dialog.setSize(460, 300);
        dialog.setLocationRelativeTo(this);
        dialog.setBackground(new Color(0, 0, 0, 0));

        // ── Fond principal ────────────────────────────────────────────────
        JPanel root = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // fond fenêtre
                g2.setColor(new Color(BG.getRed(), BG.getGreen(), BG.getBlue(), 250));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                // bordure accent
                GradientPaint gp = new GradientPaint(0, 0,
                    new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 180),
                    getWidth(), getHeight(),
                    new Color(ACCENT.getRed() / 3, ACCENT.getGreen() / 3, ACCENT.getBlue(), 80));
                g2.setPaint(gp);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 20, 20);
                // ligne accent en haut
                g2.setPaint(new GradientPaint(40, 0,
                    new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 0),
                    getWidth() / 2, 0, ACCENT,
                    true));
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(40, 0, getWidth() - 40, 0);
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.setBorder(new EmptyBorder(28, 32, 24, 32));

        // ── Icône + Titre ─────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setOpaque(false);

        JPanel iconLbl = new JPanel() {
            private final String emoji = "\uD83D\uDD13";
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int d = Math.min(getWidth(), getHeight()) - 2;
                int ox = (getWidth() - d) / 2;
                int oy = (getHeight() - d) / 2;
                // cercle de fond plus lumineux
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80));
                g2.fillOval(ox, oy, d, d);
                // bordure accent
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 160));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawOval(ox, oy, d, d);
                // emoji centré en blanc via FontMetrics
                g2.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 24));
                g2.setColor(Color.WHITE);
                FontMetrics fm = g2.getFontMetrics();
                int tx = (getWidth()  - fm.stringWidth(emoji)) / 2;
                int ty = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(emoji, tx, ty);
                g2.dispose();
            }
        };
        iconLbl.setOpaque(false);
        iconLbl.setPreferredSize(new Dimension(52, 52));

        JPanel titleBlock = new JPanel(new GridLayout(2, 1, 0, 2));
        titleBlock.setOpaque(false);

        JLabel titleLbl = new JLabel("Vous n'êtes pas connecté");
        titleLbl.setFont(new Font("Segoe UI", Font.BOLD, 16));
        titleLbl.setForeground(TEXT);

        JLabel subLbl = new JLabel("Choisissez comment vous souhaitez lancer le jeu");
        subLbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subLbl.setForeground(TEXT_DIM);

        titleBlock.add(titleLbl);
        titleBlock.add(subLbl);

        header.add(iconLbl, BorderLayout.WEST);
        header.add(titleBlock, BorderLayout.CENTER);

        // ── Séparateur ────────────────────────────────────────────────────
        JSeparator sep = new JSeparator() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 0),
                    getWidth() / 2, 0, new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), true));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        sep.setPreferredSize(new Dimension(0, 1));
        sep.setOpaque(false);

        // ── Boutons de choix ──────────────────────────────────────────────
        JPanel btnRow = new JPanel(new GridLayout(1, 2, 14, 0));
        btnRow.setOpaque(false);

        // Bouton "En ligne"
        JButton onlineBtn = new JButton("<html><center>"
            + "<span style='font-size:18px'>\uD83C\uDF10</span><br>"
            + "<b style='font-size:13px'>En ligne</b><br>"
            + "<span style='font-size:10px; color:#c0a0e0'>Compte Microsoft</span>"
            + "</center></html>") {
            private boolean hovered = false;
            {
                addMouseListener(new MouseAdapter() {
                    public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
                    public void mouseExited (MouseEvent e) { hovered = false; repaint(); }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = hovered
                    ? new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 220)
                    : new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 140);
                GradientPaint gp = new GradientPaint(0, 0, base,
                    0, getHeight(), new Color(ACCENT.getRed() / 2, 0, ACCENT.getBlue() / 2, 160));
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g2.setColor(new Color(255, 255, 255, hovered ? 60 : 30));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        onlineBtn.setForeground(Color.WHITE);
        onlineBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        onlineBtn.setFocusPainted(false);
        onlineBtn.setContentAreaFilled(false);
        onlineBtn.setBorderPainted(false);
        onlineBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        onlineBtn.setPreferredSize(new Dimension(0, 90));

        // Bouton "Hors ligne"
        JButton offlineBtn = new JButton("<html><center>"
            + "<span style='font-size:18px'>\uD83D\uDEAB</span><br>"
            + "<b style='font-size:13px'>Hors ligne</b><br>"
            + "<span style='font-size:10px'>Mode sans compte</span>"
            + "</center></html>") {
            private boolean hovered = false;
            {
                addMouseListener(new MouseAdapter() {
                    public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
                    public void mouseExited (MouseEvent e) { hovered = false; repaint(); }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = hovered
                    ? new Color(CARD_BG.getRed() + 20, CARD_BG.getGreen() + 10, CARD_BG.getBlue() + 30, 240)
                    : new Color(CARD_BG.getRed(), CARD_BG.getGreen(), CARD_BG.getBlue(), 200);
                g2.setColor(base);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), hovered ? 120 : 50));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        offlineBtn.setForeground(TEXT);
        offlineBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        offlineBtn.setFocusPainted(false);
        offlineBtn.setContentAreaFilled(false);
        offlineBtn.setBorderPainted(false);
        offlineBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));

        btnRow.add(onlineBtn);
        btnRow.add(offlineBtn);

        // ── Bouton Annuler ────────────────────────────────────────────────
        JLabel cancelLnk = new JLabel("Annuler", SwingConstants.CENTER) {
            private boolean hovered = false;
            {
                setOpaque(false);
                setCursor(new Cursor(Cursor.HAND_CURSOR));
                setFont(new Font("Segoe UI", Font.PLAIN, 11));
                setForeground(TEXT_DIM);
                addMouseListener(new MouseAdapter() {
                    public void mouseEntered(MouseEvent e) { hovered = true;  setForeground(TEXT);     repaint(); }
                    public void mouseExited (MouseEvent e) { hovered = false; setForeground(TEXT_DIM); repaint(); }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // fond pilule
                if (hovered) {
                    g2.setColor(new Color(CARD_BG.getRed(), CARD_BG.getGreen(), CARD_BG.getBlue(), 130));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                }
                // bordure pilule
                g2.setColor(new Color(TEXT_DIM.getRed(), TEXT_DIM.getGreen(), TEXT_DIM.getBlue(), hovered ? 180 : 80));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 20, 20);
                // croix dessinée à la main (6px, proche du texte)
                int cy = getHeight() / 2;
                int cx = 18;
                int r  = 4;
                Color xCol = hovered ? TEXT : TEXT_DIM;
                g2.setColor(xCol);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(cx - r, cy - r, cx + r, cy + r);
                g2.drawLine(cx + r, cy - r, cx - r, cy + r);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        cancelLnk.setBorder(new EmptyBorder(0, 22, 0, 8));
        cancelLnk.setPreferredSize(new Dimension(120, 28));

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        footer.setOpaque(false);
        footer.add(cancelLnk);

        // ── Assemblage ────────────────────────────────────────────────────
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);
        center.add(header);
        center.add(Box.createRigidArea(new Dimension(0, 16)));
        center.add(sep);
        center.add(Box.createRigidArea(new Dimension(0, 20)));
        center.add(btnRow);
        center.add(Box.createRigidArea(new Dimension(0, 14)));
        center.add(footer);

        root.add(center, BorderLayout.CENTER);
        dialog.setContentPane(root);

        // ── Actions ───────────────────────────────────────────────────────
        boolean[] chosen = {false};

        onlineBtn.addActionListener(e -> {
            chosen[0] = true;
            dialog.dispose();
            showMicrosoftLoginDialog(this::doLaunchGame);
        });
        offlineBtn.addActionListener(e -> {
            chosen[0] = true;
            dialog.dispose();
            doLaunchGame();
        });
        cancelLnk.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { dialog.dispose(); }
        });

        dialog.setVisible(true);
    }

    private void doLaunchGame() {
        playButton.setEnabled(false);
        setStatus("Pré-vérification...");
        appendLog(">> Démarrage du processus de pré-vérification...");
        long t0 = System.currentTimeMillis();
        new Thread(() -> {
            try {
                if (!runPreLaunchHealthCheck()) {
                    setStatus("Lancement annulé");
                    SwingUtilities.invokeLater(() -> {
                        playButton.setText("RÉPARER");
                        playButton.setBackground(WARNING);
                        playButton.setEnabled(true);
                    });
                    return;
                }

                setStatus("Lancement en cours...");
                appendLog("[HealthCheck] OK — lancement autorisé.");

                if (config.hasMsaProfile()) {
                    long expiresAt = config.getMsaExpiresAt();
                    boolean expired = System.currentTimeMillis() > expiresAt - 5 * 60 * 1000L;
                    if (expired) {
                        appendLog("[MSA] Token expiré, rafraîchissement silencieux...");
                        try {
                            MicrosoftAuth.McProfile fresh = MicrosoftAuth.refreshProfile(config.getMsaRefreshToken());
                            config.saveMsaProfile(fresh);
                            updateAuthCard(fresh);
                            appendLog("[MSA] Token rafraîchis pour : " + fresh.username);
                        } catch (Exception ex) {
                            appendLog("[MSA] Rafraîchissement échoué : " + ex.getMessage());
                            appendLog("[MSA] Reconnectez-vous via la sidebar. Lancement en mode offline.");
                            config.clearMsaProfile();
                            updateAuthCard(null);
                        }
                    } else {
                        appendLog("[MSA] Token valide pour : " + config.getMsaUsername());
                    }
                } else {
                    appendLog("[Offline] Pas de compte Microsoft — lancement en mode offline.");
                }

                File base = new File(config.getInstallPath());
                File[] dirs = base.listFiles(d -> d.isDirectory() && d.getName().startsWith("zokkymon_v"));
                if (dirs == null || dirs.length == 0) {
                    setStatus("Modpack introuvable");
                    appendLog("[ERR] Dossier modpack non trouvé dans " + config.getInstallPath());
                    SwingUtilities.invokeLater(() -> playButton.setEnabled(true));
                    return;
                }
                File gameDir = dirs[dirs.length-1];
                appendLog(">> Modpack : " + gameDir.getName());

                runModsSanityScan(gameDir);

                if (!updater.installFabricLoader(gameDir.getAbsolutePath())) {
                    setStatus("Erreur d'installation");
                    SwingUtilities.invokeLater(() -> playButton.setEnabled(true));
                    return;
                }
                Launcher.launchMinecraft(config, this, gameDir.getAbsolutePath());
                long elapsed = (System.currentTimeMillis() - t0) / 1000;
                appendLog(">> Session terminée en " + elapsed + "s.");
                setStatus("Session terminée");
                SwingUtilities.invokeLater(() -> {
                    playButton.setBackground(ACCENT);
                    playButton.setEnabled(true);
                });
            } catch (Exception e) {
                setStatus("Échec du lancement");
                appendLog("ERREUR CRITIQUE : " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    playButton.setText("RÉPARER");
                    playButton.setBackground(WARNING);
                    playButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void openSettings() {
        JPanel panel = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CARD_BG);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));

        UIManager.put("OptionPane.background",        BG);
        UIManager.put("Panel.background",             BG);
        UIManager.put("OptionPane.messageForeground", TEXT);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(7, 7, 7, 7);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0;
        JLabel lblRam = settingsLbl("RAM allouée");
        panel.add(lblRam, c);
        c.gridx = 1;
        JComboBox<String> cRam = new JComboBox<>(new String[]{"2 Go","4 Go","6 Go","8 Go","10 Go","12 Go","14 Go","16 Go"});
        cRam.setSelectedItem(config.getRamAllocation());
        cRam.setForeground(TEXT);
        cRam.setBorder(BorderFactory.createLineBorder(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
        applySettingsComboTheme(cRam);
        panel.add(cRam, c);

        c.gridx = 0; c.gridy = 2;
        JLabel lblPath = settingsLbl("Dossier d'installation");
        panel.add(lblPath, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;

        JTextField fPath = new JTextField(config.getInstallPath(), 22);
        fPath.setOpaque(false);
        fPath.setBorder(new EmptyBorder(4, 8, 4, 8));
        fPath.setForeground(TEXT);
        fPath.setCaretColor(TEXT);

        JPanel pathBg = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(CARD_BG);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 100));
                g2.drawRect(0, 0, getWidth()-1, getHeight()-1);
                g2.dispose();
            }
        };
        pathBg.setOpaque(true);
        pathBg.add(fPath, BorderLayout.CENTER);

        JButton browse = mkButton("Parcourir", CARD_BG, TEXT, 10, 28);
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                fPath.setText(fc.getSelectedFile().getAbsolutePath());
        });

        JButton openFolder = mkButton("Ouvrir", CARD_BG, TEXT, 10, 28);
        openFolder.setToolTipText("Ouvrir le dossier du modpack dans l'explorateur");
        openFolder.addActionListener(e -> {
            String path = fPath.getText();
            if (path == null || path.isBlank()) { appendLog("Chemin non défini."); return; }
            java.io.File dir = new java.io.File(path);
            if (!dir.exists()) { appendLog("Dossier introuvable : " + path); return; }
            try { java.awt.Desktop.getDesktop().open(dir); }
            catch (Exception ex) { appendLog("Impossible d'ouvrir : " + ex.getMessage()); }
        });

        JPanel browseRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        browseRow.setOpaque(false);
        browseRow.add(browse);
        browseRow.add(openFolder);

        JPanel pathRow = new JPanel(new BorderLayout(6, 0));
        pathRow.setOpaque(false);
        pathRow.add(pathBg,    BorderLayout.CENTER);
        pathRow.add(browseRow, BorderLayout.EAST);
        panel.add(pathRow, c);

        c.gridx = 0; c.gridy = 3; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        JLabel lblTheme = settingsLbl("Mode");
        panel.add(lblTheme, c);
        c.gridx = 1;

        boolean[] darkState = {config.isDarkMode()};
        Runnable[] refreshDialog = {null};

        JLabel switchLbl = new JLabel(darkState[0] ? " Sombre" : " Lumineux");
        switchLbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        switchLbl.setForeground(TEXT);

        JToggleButton themeSwitch = new JToggleButton() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight(), r = h;
                Color track = ACCENT;
                g2.setColor(track);
                g2.fillRoundRect(0, 0, w, h, r, r);
                int lum = (track.getRed() * 299 + track.getGreen() * 587 + track.getBlue() * 114) / 1000;
                Color onTrack = lum > 160 ? new Color(30, 30, 30) : Color.WHITE;
                g2.setColor(onTrack);
                g2.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 11));
                String icon = darkState[0] ? "☽" : "☀";
                FontMetrics fm = g2.getFontMetrics();
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                if (darkState[0]) g2.drawString(icon, 6, ty);
                else g2.drawString(icon, w - fm.stringWidth(icon) - 6, ty);
                int knobX = darkState[0] ? w - h + 3 : 3;
                g2.setColor(onTrack);
                g2.fillOval(knobX, 3, h - 6, h - 6);
                g2.dispose();
            }
            @Override public Dimension getPreferredSize() { return new Dimension(52, 24); }
        };
        themeSwitch.setOpaque(false);
        themeSwitch.setFocusPainted(false);
        themeSwitch.setContentAreaFilled(false);
        themeSwitch.setBorderPainted(false);
        themeSwitch.setSelected(darkState[0]);
        themeSwitch.addActionListener(e -> {
            darkState[0] = !darkState[0];
            switchLbl.setText(darkState[0] ? " Sombre" : " Lumineux");
            applyTheme(darkState[0]);
            if (refreshDialog[0] != null) refreshDialog[0].run();
            themeSwitch.repaint();
            rebuildUI(darkState[0]);
        });

        JPanel switchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        switchRow.setOpaque(false);
        switchRow.add(themeSwitch);
        switchRow.add(switchLbl);
        panel.add(switchRow, c);

        c.gridx = 0; c.gridy = 4; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        JLabel lblChannel = settingsLbl("Canal de mise à jour");
        panel.add(lblChannel, c);
        c.gridx = 1;

        JComboBox<String> cChannel = new JComboBox<>(new String[]{"Stable", "Bêta"});
        cChannel.setSelectedItem(toChannelLabel(config.getLauncherChannel()));
        cChannel.setForeground(TEXT);
        cChannel.setBackground(CARD_BG);
        cChannel.setBorder(BorderFactory.createLineBorder(
            new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
        applySettingsComboTheme(cChannel);
        panel.add(cChannel, c);

        c.gridx = 0; c.gridy = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        JLabel lblProfile = settingsLbl("Profil de performances");
        panel.add(lblProfile, c);
        c.gridx = 1;

        JComboBox<String> cProfile = new JComboBox<>(new String[]{"Performance", "Qualité", "PC modeste", "Personnalisé"});
        cProfile.setSelectedItem(toProfileLabel(config.getLaunchProfile()));
        cProfile.setForeground(TEXT);
        cProfile.setBackground(CARD_BG);
        cProfile.setBorder(BorderFactory.createLineBorder(
            new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
        applySettingsComboTheme(cProfile);
        panel.add(cProfile, c);

        c.gridx = 0; c.gridy = 5; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        JLabel lblJvm = settingsLbl("Arguments JVM (expert)");
        panel.add(lblJvm, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;

        JTextField fJvmArgs = new JTextField(config.getCustomJvmArgs(), 22);
        fJvmArgs.setOpaque(false);
        fJvmArgs.setBorder(new EmptyBorder(4, 8, 4, 8));
        fJvmArgs.setForeground(TEXT);
        fJvmArgs.setCaretColor(TEXT);

        JPanel jvmBg = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(CARD_BG);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 100));
                g2.drawRect(0, 0, getWidth()-1, getHeight()-1);
                g2.dispose();
            }
        };
        jvmBg.setOpaque(true);
        jvmBg.add(fJvmArgs, BorderLayout.CENTER);
        panel.add(jvmBg, c);

        // ── Résolution de la fenêtre Minecraft ───────────────────────────────
        c.gridx = 0; c.gridy = 6; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        JLabel lblRes = settingsLbl("Résolution Minecraft");
        panel.add(lblRes, c);
        c.gridx = 1;

        String[] resolutions = {
            "Plein écran",
            "854 × 480", "1280 × 720", "1366 × 768",
            "1600 × 900", "1920 × 1080", "2560 × 1440"
        };
        int curW = config.getWindowWidth(), curH = config.getWindowHeight();
        String curRes = config.isFullscreen() ? "Plein écran" : curW + " × " + curH;
        // Si la résolution actuelle n'est pas dans la liste prédéfinie, l'ajouter
        boolean found = false;
        for (String r : resolutions) if (r.equals(curRes)) { found = true; break; }
        if (!found) {
            String[] tmp = new String[resolutions.length + 1];
            tmp[0] = curRes;
            System.arraycopy(resolutions, 0, tmp, 1, resolutions.length);
            resolutions = tmp;
        }

        JComboBox<String> cRes = new JComboBox<>(resolutions);
        cRes.setSelectedItem(curRes);
        cRes.setForeground(TEXT);
        cRes.setBorder(BorderFactory.createLineBorder(
            new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
        cRes.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override protected JButton createArrowButton() {
                JButton btn = new JButton() {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(CARD_BG);
                        g2.fillRect(0, 0, getWidth(), getHeight());
                        g2.setColor(ACCENT);
                        int cx = getWidth()/2, cy = getHeight()/2;
                        g2.fillPolygon(new int[]{cx-4,cx+4,cx}, new int[]{cy-2,cy-2,cy+3}, 3);
                        g2.dispose();
                    }
                };
                btn.setBorder(BorderFactory.createEmptyBorder());
                btn.setFocusPainted(false); btn.setContentAreaFilled(false);
                return btn;
            }
            @Override public void installUI(JComponent c) {
                super.installUI(c); comboBox.setBackground(CARD_BG);
            }
            @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                g.setColor(CARD_BG); g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
            @Override public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
                Component comp = comboBox.getRenderer().getListCellRendererComponent(
                    listBox, comboBox.getSelectedItem(), -1, false, false);
                comp.setBackground(CARD_BG); comp.setForeground(TEXT);
                currentValuePane.paintComponent(g, comp, comboBox,
                    bounds.x, bounds.y, bounds.width, bounds.height, false);
            }
        });
        cRes.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                lbl.setBackground(isSelected ? SIDEBAR1 : CARD_BG);
                lbl.setForeground(isSelected ? ACCENT : TEXT);
                lbl.setBorder(new EmptyBorder(4, 10, 4, 10));
                return lbl;
            }
        });
        panel.add(cRes, c);

        c.gridx = 0; c.gridy = 7; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        JLabel lblCfgBackup = settingsLbl("Sauvegarde joueur");
        panel.add(lblCfgBackup, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;

        JButton backupCfgBtn = mkButton("Sauvegarder maintenant", CARD_BG, TEXT, 10, 30);
        backupCfgBtn.addActionListener(e -> backupPlayerConfigs());
        JButton restoreCfgBtn = mkButton("Restaurer dernière", CARD_BG, TEXT, 10, 30);
        restoreCfgBtn.addActionListener(e -> restoreLatestPlayerConfigsBackup());
        applySettingsButtonEffects(backupCfgBtn);
        applySettingsButtonEffects(restoreCfgBtn);
        backupCfgBtn.setPreferredSize(new Dimension(170, 30));
        restoreCfgBtn.setPreferredSize(new Dimension(170, 30));

        JPanel backupRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        backupRow.setOpaque(false);
        backupRow.add(backupCfgBtn);
        backupRow.add(restoreCfgBtn);
        panel.add(backupRow, c);

        JDialog dialog = new JDialog(this, "Paramètres", true);
        dialog.setUndecorated(false);
        dialog.setResizable(false);

        JButton btnOk     = mkButton("OK", ACCENT, BG, 10, 32);
        JButton btnCancel = mkButton("Annuler", CARD_BG, TEXT, 10, 32);
        btnOk    .setPreferredSize(new Dimension(90, 32));
        btnCancel.setPreferredSize(new Dimension(90, 32));

        final int[] result = {JOptionPane.CANCEL_OPTION};
        btnOk.addActionListener(e     -> { result[0] = JOptionPane.OK_OPTION; dialog.dispose(); });
        btnCancel.addActionListener(e -> dialog.dispose());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.setOpaque(false);
        btnRow.add(btnOk);
        btnRow.add(btnCancel);

        JPanel wrapper = new JPanel(new BorderLayout(0, 12));
        wrapper.setBackground(BG);
        wrapper.setBorder(new EmptyBorder(4, 4, 12, 4));
        wrapper.add(panel,  BorderLayout.CENTER);
        wrapper.add(btnRow, BorderLayout.SOUTH);

        refreshDialog[0] = () -> {
            wrapper.setBackground(BG);
            UIManager.put("OptionPane.background",        BG);
            UIManager.put("Panel.background",             BG);
            UIManager.put("OptionPane.messageForeground", TEXT);
            lblRam.setForeground(TEXT);
            lblPath.setForeground(TEXT);
            lblTheme.setForeground(TEXT);
            lblChannel.setForeground(TEXT);
            lblProfile.setForeground(TEXT);
            lblJvm.setForeground(TEXT);
            lblCfgBackup.setForeground(TEXT);
            switchLbl.setForeground(TEXT);
            fPath.setForeground(TEXT);
            fPath.setCaretColor(TEXT);
            fJvmArgs.setForeground(TEXT);
            fJvmArgs.setCaretColor(TEXT);
            pathBg.repaint();
            jvmBg.repaint();
            btnOk.setBackground(ACCENT);
            btnOk.setForeground(BG);
            btnCancel.setBackground(CARD_BG);
            btnCancel.setForeground(TEXT);
            browse.setBackground(CARD_BG);
            browse.setForeground(TEXT);
            browse.repaint();
            openFolder.setBackground(CARD_BG);
            openFolder.setForeground(TEXT);
            openFolder.repaint();
            backupCfgBtn.setBackground(CARD_BG);
            backupCfgBtn.setForeground(TEXT);
            backupCfgBtn.repaint();
            restoreCfgBtn.setBackground(CARD_BG);
            restoreCfgBtn.setForeground(TEXT);
            restoreCfgBtn.repaint();
            pathBg.repaint();
            cRam.setForeground(TEXT);
            cRam.setBackground(CARD_BG);
            cRam.setBorder(BorderFactory.createLineBorder(
                new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
            cRam.repaint();
            cChannel.setForeground(TEXT);
            cChannel.setBackground(CARD_BG);
            cChannel.setBorder(BorderFactory.createLineBorder(
                new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
            cChannel.repaint();
            cProfile.setForeground(TEXT);
            cProfile.setBackground(CARD_BG);
            cProfile.setBorder(BorderFactory.createLineBorder(
                new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
            cProfile.repaint();
            lblRes.setForeground(TEXT);
            cRes.setForeground(TEXT);
            cRes.setBackground(CARD_BG);
            cRes.setBorder(BorderFactory.createLineBorder(
                new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
            cRes.repaint();
            panel.repaint();
            wrapper.repaint();
            dialog.repaint();
        };

        dialog.setContentPane(wrapper);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);

        UIManager.put("OptionPane.background",        null);
        UIManager.put("Panel.background",             null);
        UIManager.put("OptionPane.messageForeground", null);

        if (result[0] == JOptionPane.OK_OPTION) {
            String selectedProfileLabel = (String) cProfile.getSelectedItem();
            String selectedProfile = toProfileKey(selectedProfileLabel);
            config.setLaunchProfile(selectedProfile);

            if ("custom".equals(selectedProfile)) {
                config.setRamAllocation((String) cRam.getSelectedItem());
                config.setCustomJvmArgs(fJvmArgs.getText());
                appendLog("[Profil] Mode personnalisé enregistré.");
            } else {
                applyLaunchProfilePreset(selectedProfile);
                appendLog("[Profil] Preset appliqué : " + (selectedProfileLabel == null ? "Performance" : selectedProfileLabel));
            }

            config.setInstallPath(fPath.getText());
            String oldChannel = config.getLauncherChannel();
            String newChannelLabel = (String) cChannel.getSelectedItem();
            String newChannel = toChannelKey(newChannelLabel);
            if (!newChannel.equals(oldChannel)) {
                config.setLauncherChannel(newChannel);
                cachedLauncherStatus = null;
                cachedLauncherNewVer = null;
                appendLog("[Canal] Canal de mise à jour changé : "
                    + toChannelLabel(oldChannel) + " -> "
                    + (newChannelLabel == null ? "Stable" : newChannelLabel));
                new Thread(this::startBackgroundChecks).start();
            }
            // Sauvegarder la résolution
            String selRes = (String) cRes.getSelectedItem();
            if (selRes != null && "custom".equals(selectedProfile)) {
                if ("Plein écran".equals(selRes)) {
                    config.setFullscreen(true);
                } else {
                    config.setFullscreen(false);
                    String[] parts = selRes.split("\\s*×\\s*");
                    try { config.setWindowSize(Integer.parseInt(parts[0].trim()),
                                               Integer.parseInt(parts[1].trim())); }
                    catch (NumberFormatException ignored) {}
                }
            }
            if (darkState[0] != config.isDarkMode()) config.setDarkMode(darkState[0]);
            appendLog("Paramètres enregistrés.");
            new Thread(this::initInfoCards).start();
        } else {
            boolean modeChanged = darkState[0] != config.isDarkMode();
            if (modeChanged) {
                SwingUtilities.invokeLater(() -> rebuildUI(config.isDarkMode()));
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  API publique
    // ═════════════════════════════════════════════════════════════════════════
    public void setProgress(int value) {
        SwingUtilities.invokeLater(() -> progressBar.setValue(value));
    }

    /**
     * Met à jour la barre de progression et le statut pendant le chargement des mods Fabric.
     *
     * @param current  nombre de mods déjà chargés
     * @param total    nombre total de mods à charger
     * @param modName  nom du mod en cours de chargement (peut être null)
     */
    public void setModLoadingProgress(int current, int total, String modName) {
        int pct = (total > 0) ? Math.min(100, current * 100 / total) : 0;
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(pct);
            String label = "Chargement des mods : " + current + "/" + total
                + (modName != null && !modName.isBlank() ? " (" + modName + ")" : "");
            statusLabel.setText(label);
            statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            statusLabel.setForeground(TEXT);
        });
    }

    public void setStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(status);
            if (status.startsWith("Prêt") || status.startsWith("Session")) {
                statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
                statusLabel.setForeground(ACCENT);
            } else if (status.toLowerCase().contains("erreur") || status.contains("introuvable")) {
                statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
                statusLabel.setForeground(DANGER);
            } else if (status.contains("requise") || status.contains("disponible")) {
                statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
                statusLabel.setForeground(WARNING);
            } else {
                statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
                statusLabel.setForeground(TEXT);
            }
        });
    }

    public void appendLog(String message) {
        String safeMessage = (message == null) ? "" : message;
        writeLogLineToFiles(safeMessage);
        SwingUtilities.invokeLater(() -> {
            logArea.append(safeMessage + "\n");
            if (logScrollPane != null) {
                javax.swing.JScrollBar bar = logScrollPane.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers design
    // ═════════════════════════════════════════════════════════════════════════

    private JButton mkButton(String txt, Color bg, Color fg, int arc, int h) {
        JButton btn = new JButton(txt) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 120));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, arc, arc);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setForeground(fg);
        btn.setBackground(bg);
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(btn.getPreferredSize().width + 20, h));
        return btn;
    }

    private Color blend(Color base, Color accent, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        int red = Math.round(base.getRed() * (1f - r) + accent.getRed() * r);
        int green = Math.round(base.getGreen() * (1f - r) + accent.getGreen() * r);
        int blue = Math.round(base.getBlue() * (1f - r) + accent.getBlue() * r);
        return new Color(red, green, blue);
    }

    private void applySettingsComboTheme(JComboBox<?> combo) {
        combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                JButton btn = new JButton() {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(CARD_BG);
                        g2.fillRect(0, 0, getWidth(), getHeight());
                        g2.setColor(ACCENT);
                        int cx = getWidth() / 2;
                        int cy = getHeight() / 2;
                        int[] xp = {cx - 4, cx + 4, cx};
                        int[] yp = {cy - 2, cy - 2, cy + 3};
                        g2.fillPolygon(xp, yp, 3);
                        g2.dispose();
                    }
                };
                btn.setBorder(BorderFactory.createEmptyBorder());
                btn.setFocusPainted(false);
                btn.setContentAreaFilled(false);
                return btn;
            }

            @Override
            public void installUI(JComponent c) {
                super.installUI(c);
                comboBox.setBackground(CARD_BG);
            }

            @Override
            public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                g.setColor(CARD_BG);
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }

            @Override
            public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
                ListCellRenderer<Object> renderer = comboBox.getRenderer();
                Component comp = renderer.getListCellRendererComponent(
                    listBox, comboBox.getSelectedItem(), -1, false, false);
                comp.setBackground(CARD_BG);
                comp.setForeground(TEXT);
                currentValuePane.paintComponent(g, comp, comboBox,
                    bounds.x, bounds.y, bounds.width, bounds.height, false);
            }
        });

        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                lbl.setBackground(isSelected ? SIDEBAR1 : CARD_BG);
                lbl.setForeground(isSelected ? ACCENT : TEXT);
                lbl.setBorder(new EmptyBorder(4, 10, 4, 10));
                return lbl;
            }
        });
    }

    private String toProfileLabel(String profileKey) {
        return switch (profileKey == null ? "" : profileKey.toLowerCase(Locale.ROOT).trim()) {
            case "quality", "qualite" -> "Qualité";
            case "low-end", "lowend" -> "PC modeste";
            case "custom" -> "Personnalisé";
            default -> "Performance";
        };
    }

    private String toChannelLabel(String channelKey) {
        return switch (channelKey == null ? "" : channelKey.toLowerCase(Locale.ROOT).trim()) {
            case "beta", "bêta", "béta" -> "Bêta";
            default -> "Stable";
        };
    }

    private String toChannelKey(String channelLabel) {
        return switch (channelLabel == null ? "" : channelLabel.toLowerCase(Locale.ROOT).trim()) {
            case "bêta", "beta", "béta" -> "beta";
            default -> "stable";
        };
    }

    private String toProfileKey(String profileLabel) {
        return switch (profileLabel == null ? "" : profileLabel.toLowerCase(Locale.ROOT).trim()) {
            case "qualité", "qualite" -> "quality";
            case "pc modeste" -> "low-end";
            case "personnalisé", "personnalise" -> "custom";
            default -> "performance";
        };
    }

    private void applySettingsButtonEffects(JButton button) {
        button.addMouseListener(new MouseAdapter() {
            private boolean pressed;

            private void applyBase() {
                button.setBackground(CARD_BG);
                button.repaint();
            }

            private void applyHover() {
                button.setBackground(blend(CARD_BG, ACCENT, 0.16f));
                button.repaint();
            }

            private void applyPressed() {
                button.setBackground(blend(CARD_BG, ACCENT, 0.30f));
                button.repaint();
            }

            @Override public void mouseEntered(MouseEvent e) {
                if (!button.isEnabled()) return;
                if (!pressed) applyHover();
            }

            @Override public void mouseExited(MouseEvent e) {
                if (!button.isEnabled()) return;
                pressed = false;
                applyBase();
            }

            @Override public void mousePressed(MouseEvent e) {
                if (!button.isEnabled()) return;
                if (SwingUtilities.isLeftMouseButton(e)) {
                    pressed = true;
                    applyPressed();
                }
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (!button.isEnabled()) return;
                pressed = false;
                if (button.contains(e.getPoint())) applyHover();
                else applyBase();
            }
        });
    }

    private JLabel infoVal(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.BOLD, 12));
        l.setForeground(ACCENT);
        return l;
    }

    private JLabel settingsLbl(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        l.setForeground(TEXT);
        return l;
    }

    private Component vSep(int h) {
        return Box.createRigidArea(new Dimension(0, h));
    }

    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) deleteDirectory(f);
        }
        dir.delete();
    }

    // ── CLASSE INTERNE POUR EFFET GLASSMORPHISM ──────────────────────────────
    private class GlassPanel extends JPanel {
        private final int radius;
        private final Color base;
        public GlassPanel(int r, Color b) { this.radius = r; this.base = b; setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Plus opaque en mode clair pour garder le texte lisible
            int alpha = (base.getRed() > 220) ? 235 : 190;
            g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.setColor(new Color(255, 255, 255, 40));
            g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}