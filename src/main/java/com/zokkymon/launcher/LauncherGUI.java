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
import java.io.InputStream;
import java.lang.management.ManagementFactory;

/**
 * Interface principale du launcher — design style Modrinth/Prism.
 *
 * Layout : sidebar 230px (gauche) | zone principale (droite)
 *   Sidebar  : logo + 4 info-cards + capsule version launcher
 *   Principal: bannière cover-fill + console + barre du bas (status + boutons)
 */
public class LauncherGUI extends JFrame {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    // ── Gestionnaire de thèmes ────────────────────────────────────────────────
    // Initialisé dans le constructeur avant tout appel à applyTheme().
    static ThemeManager themeManager;

    // ── Couleurs actives (mises à jour par applyTheme) ───────────────────────
    static Color BG, CARD_BG, SIDEBAR1, SIDEBAR2, CONSOLE_BG,
                 ACCENT, WARNING, DANGER, TEXT, TEXT_DIM, BTM1, BTM2;

    static void applyTheme(boolean dark) {
        ThemeDefinition t = themeManager.getCurrent();
        Color[] p = dark ? t.dark : t.light;
        BG         = p[ThemeDefinition.IDX_BG];
        CARD_BG    = p[ThemeDefinition.IDX_CARD_BG];
        SIDEBAR1   = p[ThemeDefinition.IDX_SIDEBAR1];
        SIDEBAR2   = p[ThemeDefinition.IDX_SIDEBAR2];
        CONSOLE_BG = p[ThemeDefinition.IDX_CONSOLE];
        ACCENT     = p[ThemeDefinition.IDX_ACCENT];
        WARNING    = p[ThemeDefinition.IDX_WARNING];
        DANGER     = p[ThemeDefinition.IDX_DANGER];
        TEXT       = p[ThemeDefinition.IDX_TEXT];
        TEXT_DIM   = p[ThemeDefinition.IDX_TEXT_DIM];
        BTM1       = p[ThemeDefinition.IDX_BTM1];
        BTM2       = p[ThemeDefinition.IDX_BTM2];
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

    // ── Cache des états réseau (évite les appels superflus au rebuild) ────────
    private volatile Boolean cachedServerOnline   = null;
    private volatile String  cachedLauncherStatus = null; // "ok", "update", ou null
    private volatile String  cachedLauncherNewVer = null;
    private volatile String  cachedModpackStatus  = null; // "absent", "outdated", "uptodate", ou null
    private volatile String  cachedInfoModpack    = null;
    private volatile String  cachedInfoJava       = null;
    private volatile String  cachedInfoRam        = null;
    private volatile String  cachedInfoMods       = null;

    // ── Polices statiques (évite des allocations à chaque repaint) ───────────
    private static final Font FONT_MONO  = new Font("Consolas", Font.PLAIN,  12);

    // ── Cache images (chargées une seule fois, réutilisées au rebuild) ────────
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
        // Injecter le CLIENT_ID Azure depuis la config — jamais en dur dans le code source
        MicrosoftAuth.setClientId(config.getClientId());
        themeManager = new ThemeManager(new java.io.File(System.getProperty("user.home"), ".zokkymon"));
        themeManager.setActiveId(config.getActiveTheme());
        applyTheme(config.isDarkMode());
        updater = new Updater(this, config);

        // Chargement unique des images (réutilisées à chaque rebuild de thème)
        loadBannerForCurrentTheme();
        logoImg   = loadImage("/zokkymon.png", "/zokkymon.ico");

        setTitle("Launcher Zokkymon");
        setSize(1100, 680);
        setMinimumSize(new Dimension(900, 600));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setResizable(true);
        loadWindowIcon();

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(buildSidebar(),  BorderLayout.WEST);
        root.add(buildMainArea(), BorderLayout.CENTER);
        setContentPane(root);

        setVisible(true);

        startBackgroundChecks();
        new Thread(this::checkAndUpdate).start();
    }

    /**
     * Lance les tâches légères de vérification au démarrage et après un changement de thème.
     * Restaure les états mis en cache immédiatement — les appels réseau ne sont faits qu'au premier lancement.
     * NE relance PAS checkAndUpdate (mise à jour du jeu).
     */
    private void startBackgroundChecks() {
        // ── Info-cards (locales, très rapides — mais cache quand même) ──────
        if (cachedInfoModpack != null) {
            SwingUtilities.invokeLater(() -> {
                infoModpackVal.setText(cachedInfoModpack);
                infoJavaVal   .setText(cachedInfoJava);
                infoRamVal    .setText(cachedInfoRam);
                infoModsVal   .setText(cachedInfoMods);
            });
        } else {
            new Thread(this::initInfoCards).start();
        }

        // ── Bouton Play : restaure l'état sans refaire le check réseau ──────
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
                    default -> { // "uptodate"
                        setProgress(100);
                        setStatus("Prêt à jouer");
                        playButton.setText("JOUER");
                        playButton.setBackground(ACCENT);
                        playButton.setEnabled(true);
                    }
                }
            });
        }

        // ── Serveur : affiche le dernier état connu immédiatement ───────────
        // La vérification réseau réelle est déclenchée par checkAndUpdate() au démarrage.
        if (cachedServerOnline != null) {
            final boolean s = cachedServerOnline;
            SwingUtilities.invokeLater(() -> applyServerState(s));
        }

        // ── Capsule version launcher ─────────────────────────────────────────
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

    /**
     * Applique le thème et reconstruit toute l'interface dans la même fenêtre — sans dispose().
     */
    private void rebuildUI(boolean dark) {
        // Préserver le contenu de la console
        String savedLog = logArea != null ? logArea.getText() : "";

        applyTheme(dark);
        loadBannerForCurrentTheme();
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.add(buildSidebar(),  BorderLayout.WEST);
        root.add(buildMainArea(), BorderLayout.CENTER);
        setContentPane(root);
        revalidate();
        repaint();

        // Restaurer le contenu de la console
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

    /** Charge la première image ressource trouvée parmi les chemins donnés. */
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

    /**
     * Charge la bannière du thème actif dans {@code bannerImg}.
     * Si le thème possède un banner.png externe il est utilisé en priorité,
     * sinon la bannière intégrée (/banner.png) est utilisée.
     */
    private void loadBannerForCurrentTheme() {
        ThemeDefinition t = themeManager.getCurrent();
        if (t.banner != null) {
            bannerImg = t.banner;
            return;
        }
        bannerImg = loadImage("/banner.png", "/zokkymon.png");
    }

    // ── Sidebar ──────────────────────────────────────────────────────────────
    private JPanel buildSidebar() {
        JPanel s = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, SIDEBAR1, 0, getHeight(), SIDEBAR2));
                g2.fillRect(0, 0, getWidth(), getHeight());
                // Ligne séparatrice droite
                Color sep = ACCENT;
                g2.setPaint(new GradientPaint(getWidth()-1, 0, new Color(sep.getRed(), sep.getGreen(), sep.getBlue(), 130), getWidth()-1, getHeight(), new Color(sep.getRed(), sep.getGreen(), sep.getBlue(), 50)));
                g2.fillRect(getWidth()-1, 0, 1, getHeight());
                g2.dispose();
            }
        };
        s.setLayout(new BoxLayout(s, BoxLayout.Y_AXIS));
        s.setOpaque(true);
        s.setPreferredSize(new Dimension(230, 0));
        s.setBorder(new EmptyBorder(20, 16, 16, 16));

        s.add(buildLogoBlock());
        s.add(vSep(12));
        s.add(buildServerStatusPanel());
        s.add(vSep(8));
        s.add(buildAuthCard());
        s.add(vSep(16));
        s.add(hLine());
        s.add(vSep(16));

        JLabel sec = new JLabel("INFORMATIONS");
        sec.setFont(new Font("Segoe UI", Font.BOLD, 9));
        sec.setForeground(TEXT_DIM);
        sec.setAlignmentX(LEFT_ALIGNMENT);
        s.add(sec);
        s.add(vSep(10));

        infoModpackVal = infoVal("...");
        infoJavaVal    = infoVal("...");
        infoRamVal     = infoVal("...");
        infoModsVal    = infoVal("...");

        s.add(infoCard("Modpack",    infoModpackVal));
        s.add(vSep(6));
        s.add(infoCard("Java",       infoJavaVal));
        s.add(vSep(6));
        s.add(infoCard("RAM",        infoRamVal));
        s.add(vSep(6));
        s.add(infoCard("Mods actifs", infoModsVal));

        s.add(Box.createVerticalGlue());
        s.add(buildVersionCapsule());
        return s;
    }

    private JPanel buildLogoBlock() {
        JPanel block = new JPanel(new BorderLayout(10, 0));
        block.setOpaque(false);
        block.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        block.setAlignmentX(LEFT_ALIGNMENT);

        JLabel logo = new JLabel() {
            @Override protected void paintComponent(Graphics g) {
                if (logoImg == null) { super.paintComponent(g); return; }
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int arc = getWidth(); // cercle complet — mettre 12 pour juste arrondi
                g2.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
                g2.drawImage(logoImg, 0, 0, getWidth(), getHeight(), null);
                g2.dispose();
            }
        };
        logo.setPreferredSize(new Dimension(48, 48));
        logo.setOpaque(false);

        JPanel txt = new JPanel(new GridLayout(2, 1, 0, 2));
        txt.setOpaque(false);
        JLabel name = new JLabel(config.getModpackName());
        name.setFont(new Font("Segoe UI", Font.BOLD, 13));
        name.setForeground(ACCENT);
        JLabel sub = new JLabel("Minecraft " + config.getMinecraftVersion());
        sub.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        sub.setForeground(TEXT_DIM);
        txt.add(name);
        txt.add(sub);

        block.add(logo, BorderLayout.WEST);
        block.add(txt,  BorderLayout.CENTER);
        return block;
    }

    // ── Voyant serveur ───────────────────────────────────────────────────────
    private JPanel buildServerStatusPanel() {
        JPanel card = new JPanel(new BorderLayout(10, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, CARD_BG.brighter(), 0, getHeight(), CARD_BG));
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 12, 12);
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(8, 10, 8, 10));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        card.setAlignmentX(LEFT_ALIGNMENT);

        // Colonne gauche : gros point lumineux
        serverDot = new JLabel("\u25cf");
        serverDot.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 22));
        serverDot.setForeground(TEXT_DIM);
        serverDot.setHorizontalAlignment(SwingConstants.CENTER);
        serverDot.setPreferredSize(new Dimension(28, 28));

        // Colonne droite : libellé haut + statut bas
        JPanel txt = new JPanel(new GridLayout(2, 1, 0, 1));
        txt.setOpaque(false);
        JLabel topLbl = new JLabel("SERVEUR");
        topLbl.setFont(new Font("Segoe UI", Font.BOLD, 9));
        topLbl.setForeground(TEXT_DIM);
        serverStatusLbl = new JLabel("Vérification...");
        serverStatusLbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        serverStatusLbl.setForeground(TEXT_DIM);
        txt.add(topLbl);
        txt.add(serverStatusLbl);

        // Bouton refresh manuel ⟳
        serverRefreshBtn = new JButton() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                // Fond cercle
                g2.setColor(getModel().isPressed() ? ACCENT : CONSOLE_BG);
                g2.fillOval(0, 0, getWidth()-1, getHeight()-1);
                // Bordure
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 120));
                g2.drawOval(0, 0, getWidth()-1, getHeight()-1);
                // Icône centrée précisément via TextLayout (limites visuelles réelles)
                String icon = "\u27F3";
                java.awt.Font font = new Font("Segoe UI Symbol", Font.PLAIN, 20);
                g2.setFont(font);
                g2.setColor(getModel().isPressed() ? CONSOLE_BG : ACCENT);
                java.awt.font.TextLayout tl = new java.awt.font.TextLayout(icon, font, g2.getFontRenderContext());
                java.awt.geom.Rectangle2D vb = tl.getBounds();
                int x = (int) Math.round((getWidth()  - vb.getWidth())  / 2.0 - vb.getX());
                int y = (int) Math.round((getHeight() - vb.getHeight()) / 2.0 - vb.getY());
                g2.drawString(icon, x, y);
                g2.dispose();
            }
        };
        serverRefreshBtn.setPreferredSize(new Dimension(32, 32));
        serverRefreshBtn.setToolTipText("V\u00e9rifier maintenant");
        serverRefreshBtn.setFocusPainted(false);
        serverRefreshBtn.setContentAreaFilled(false);
        serverRefreshBtn.setBorderPainted(false);
        serverRefreshBtn.setOpaque(false);
        // Désactive le rendu shadow/focus de FlatLaf qui s'affiche par-dessus
        serverRefreshBtn.putClientProperty("JButton.buttonType", "borderless");
        serverRefreshBtn.putClientProperty("FlatLaf.focusWidth", 0);
        serverRefreshBtn.addActionListener(e -> {
            serverStatusLbl.setText("V\u00e9rification...");
            serverStatusLbl.setForeground(TEXT_DIM);
            serverDot.setForeground(TEXT_DIM);
            cachedServerOnline = null; // invalide le cache pour forcer un vrai check
            serverRefreshBtn.setEnabled(false);
            new Thread(() -> {
                checkServerOnce();
                SwingUtilities.invokeLater(() -> serverRefreshBtn.setEnabled(true));
            }).start();
        });

        card.add(serverDot,        BorderLayout.WEST);
        card.add(txt,              BorderLayout.CENTER);
        // Enveloppe pour garder le bouton carré (BorderLayout.EAST étire en hauteur)
        JPanel refreshWrap = new JPanel(new GridBagLayout());
        refreshWrap.setOpaque(false);
        refreshWrap.add(serverRefreshBtn);
        card.add(refreshWrap, BorderLayout.EAST);
        return card;
    }

    /** Vérifie une seule fois si le serveur est joignable, met à jour le cache et l'UI. */
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

    /** Applique visuellement l'état du serveur (sans appel réseau). */
    private void applyServerState(boolean isOnline) {
        if (serverDot == null || serverStatusLbl == null) return;
        Color onlineColor = new Color(52, 211, 153); // vert-jade sémantique
        if (isOnline) {
            serverDot.setForeground(onlineColor);
            serverStatusLbl.setText("Serveur en ligne");
            serverStatusLbl.setForeground(onlineColor);
        } else {
            serverDot.setForeground(DANGER);
            serverStatusLbl.setText("Serveur hors ligne");
            serverStatusLbl.setForeground(DANGER);
        }
        serverDot.setToolTipText(isOnline ? "Connecté à " + SERVER_HOST : "Impossible de joindre " + SERVER_HOST);
    }

    // ── Carte Auth Microsoft ─────────────────────────────────────

    /** Construit la carte d'authentification Microsoft de la sidebar. */
    private JPanel buildAuthCard() {
        JPanel card = new JPanel(new BorderLayout(10, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, CARD_BG.brighter(), 0, getHeight(), CARD_BG));
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 12, 12);
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        card.setOpaque(false);
        card.setBorder(new javax.swing.border.EmptyBorder(8, 10, 8, 10));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
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
        authStatusLbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        authStatusLbl.setForeground(config.hasMsaProfile() ? new Color(52, 211, 153) : TEXT_DIM);
        txt.add(topLbl);
        txt.add(authStatusLbl);

        authActionBtn = new JButton(config.hasMsaProfile() ? "\u2715" : "\u2192") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                // Fond cercle — même style que le bouton refresh serveur
                g2.setColor(getModel().isPressed() ? ACCENT : CONSOLE_BG);
                g2.fillOval(0, 0, getWidth()-1, getHeight()-1);
                // Bordure
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 120));
                g2.drawOval(0, 0, getWidth()-1, getHeight()-1);
                // Icône centrée précisément via TextLayout (même méthode que le bouton serveur)
                java.awt.Font font = new Font("Segoe UI Symbol", Font.PLAIN, 18);
                g2.setFont(font);
                g2.setColor(getModel().isPressed() ? CONSOLE_BG : ACCENT);
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
        authActionBtn.putClientProperty("JButton.buttonType", "borderless");
        authActionBtn.putClientProperty("FlatLaf.focusWidth", 0);
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
                showMicrosoftLoginDialog();
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

    /**
     * Met à jour la carte d'auth après connexion ou déconnexion.
     * @param profile profil obtenu après auth, {@code null} si déconnecté.
     */
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
                authStatusLbl.setForeground(TEXT_DIM);
                authActionBtn.setText("\u2192");
                authActionBtn.setToolTipText("Se connecter avec Microsoft");
            }
            authStatusLbl.repaint();
            authActionBtn.repaint();
        });
    }

    /**
     * Affiche un dialogue avec le code Device Code et lance le polling en arrière-plan.
     */
    private void showMicrosoftLoginDialog() {
        authActionBtn.setEnabled(false);
        appendLog("[MSA] Démarrage du Device Code Flow...");
        new Thread(() -> {
            MicrosoftAuth.DeviceCodeResult dcr;
            try {
                dcr = MicrosoftAuth.requestDeviceCode();
            } catch (Exception ex) {
                appendLog("[MSA] Erreur : " + ex.getMessage());
                SwingUtilities.invokeLater(() -> authActionBtn.setEnabled(true));
                return;
            }

            JDialog dialog = new JDialog(this, "Connexion Microsoft", true);
            dialog.setSize(430, 285);
            dialog.setLocationRelativeTo(this);
            dialog.setResizable(false);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

            JPanel root = new JPanel(new GridBagLayout());
            root.setBackground(CONSOLE_BG);
            root.setBorder(new EmptyBorder(20, 24, 16, 24));
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets  = new java.awt.Insets(4, 0, 4, 0);
            gc.fill    = GridBagConstraints.HORIZONTAL;
            gc.gridx   = 0;
            gc.weightx = 1;

            JLabel title = new JLabel("Connexion à votre compte Microsoft");
            title.setFont(new Font("Segoe UI", Font.BOLD, 14));
            title.setForeground(TEXT);
            title.setHorizontalAlignment(SwingConstants.CENTER);
            gc.gridy = 0;
            root.add(title, gc);

            JLabel instr = new JLabel("<html><center>Ouvrez <b>" + dcr.verificationUri
                + "</b><br>et saisissez le code ci-dessous&nbsp;:</center></html>");
            instr.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            instr.setForeground(TEXT_DIM);
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
            JButton copyBtn   = new JButton("Copier le code");
            JButton openBtn   = new JButton("Ouvrir le navigateur");
            JButton cancelBtn = new JButton("Annuler");
            for (JButton b : new JButton[]{copyBtn, openBtn, cancelBtn}) {
                b.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                b.setBackground(CARD_BG);
                b.setForeground(TEXT);
                b.setFocusPainted(false);
                btns.add(b);
            }
            copyBtn.addActionListener(ev -> {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(dcr.userCode), null);
                copyBtn.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 12));
                copyBtn.setText("\u2713 Copié !");
            });
            openBtn.addActionListener(ev -> {
                try { java.awt.Desktop.getDesktop().browse(new java.net.URI(dcr.verificationUri)); }
                catch (Exception ignored) { appendLog("[MSA] Ouvrez : " + dcr.verificationUri); }
            });
            gc.gridy = 3; gc.insets = new java.awt.Insets(4, 0, 0, 0);
            root.add(btns, gc);

            JLabel pollLbl = new JLabel("En attente de votre validation...");
            pollLbl.setFont(new Font("Segoe UI", Font.ITALIC, 11));
            pollLbl.setForeground(TEXT_DIM);
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
                    SwingUtilities.invokeLater(dialog::dispose);
                } catch (InterruptedException ignored) {
                    appendLog("[MSA] Connexion annulée.");
                } catch (Exception ex) {
                    appendLog("[MSA] Erreur : " + ex.getMessage());
                    SwingUtilities.invokeLater(() -> {
                        pollLbl.setText("Erreur : " + ex.getMessage());
                        pollLbl.setForeground(DANGER);
                    });
                } finally {
                    SwingUtilities.invokeLater(() -> authActionBtn.setEnabled(true));
                }
            }).start();

            dialog.setVisible(true); // modal — bloque jusqu'à fermeture
        }).start();
    }

    private JPanel infoCard(String title, JLabel valueLabel) {
        JPanel card = new JPanel(new GridLayout(2, 1, 0, 2)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Fond dégradé adapté au thème
                g2.setPaint(new GradientPaint(0, 0, CARD_BG.brighter(), 0, getHeight(), CARD_BG));
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 12, 12);
                // Bordure
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(8, 10, 8, 10));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        card.setAlignmentX(LEFT_ALIGNMENT);

        JLabel t = new JLabel(title);
        t.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        t.setForeground(TEXT_DIM);
        card.add(t);
        card.add(valueLabel);
        return card;
    }

    /** Conteneur principal de la capsule (remplacé dynamiquement). */
    private JPanel versionCapsuleContainer;

    private JPanel buildVersionCapsule() {
        versionCapsuleContainer = new JPanel(new BorderLayout());
        versionCapsuleContainer.setOpaque(false);
        versionCapsuleContainer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        versionCapsuleContainer.setAlignmentX(LEFT_ALIGNMENT);

        // État initial : chargement
        launcherStatusLabel = new JLabel("v" + config.getLauncherVersion());
        launcherStatusLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        launcherStatusLabel.setForeground(TEXT_DIM);
        applyLauncherButton = new JButton(); // placeholder
        applyLauncherButton.setVisible(false);

        versionCapsuleContainer.add(buildUpToDateCapsule("v" + config.getLauncherVersion(), false), BorderLayout.CENTER);
        return versionCapsuleContainer;
    }

    /** Capsule sobre « à jour » — jade cohérent avec le reste de l'UI */
    private JPanel buildUpToDateCapsule(String version, boolean confirmed) {
        // Jade identique au voyant serveur et à l'auth
        Color jade    = new Color(52, 211, 153);
        Color bgGreen  = new Color(jade.getRed(), jade.getGreen(), jade.getBlue(), 30);
        Color rimGreen = new Color(jade.getRed(), jade.getGreen(), jade.getBlue(), 110);
        JPanel cap = new JPanel(new BorderLayout(8, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bgGreen);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 14, 14);
                g2.setColor(rimGreen);
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

    /** Bloc « mise à jour disponible » — élégant, avec transition v_old → v_new + bouton */
    private JPanel buildUpdateAvailableCapsule(String oldVer, String newVer) {
        Color bgAmber  = new Color(180, 110, 20, 70);
        Color rimAmber = new Color(225, 160, 50, 150);

        JPanel cap = new JPanel(new BorderLayout(0, 6)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bgAmber);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16);
                // Liseré lumineux ambre en haut
                g2.setPaint(new GradientPaint(0, 0, new Color(225,170,60,180), getWidth(), 0, new Color(225,170,60,40)));
                g2.fillRoundRect(0, 0, getWidth()-1, 2, 16, 16);
                g2.setColor(rimAmber);
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16);
                g2.dispose();
            }
        };
        cap.setOpaque(false);
        cap.setBorder(new EmptyBorder(8, 10, 8, 10));
        cap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));

        // Ligne du haut : ▲ Mise à jour disponible
        JLabel title = new JLabel("\u25b2  Mise \u00e0 jour disponible");
        title.setFont(new Font("Segoe UI Symbol", Font.BOLD, 11));
        title.setForeground(new Color(235, 185, 70));

        // Ligne du bas : v0.0.3 → v0.0.4  [bouton]
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

    // ── Zone principale ───────────────────────────────────────────────────────
    private JPanel buildMainArea() {
        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(BG);
        main.add(buildBanner(),    BorderLayout.NORTH);
        main.add(buildConsole(),   BorderLayout.CENTER);
        main.add(buildBottomBar(), BorderLayout.SOUTH);
        return main;
    }

    private JPanel buildBanner() {
        JPanel banner = new JPanel(null) {  // null layout pour le badge overlay
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int w = getWidth(), h = getHeight();
                if (bannerImg != null) {
                    // Cover centré : l'image remplit toute la zone sans bandes ni étirement
                    double scaleW = (double) w / bannerImg.getWidth();
                    double scaleH = (double) h / bannerImg.getHeight();
                    double scale  = Math.max(scaleW, scaleH);
                    int imgW = (int)(bannerImg.getWidth()  * scale);
                    int imgH = (int)(bannerImg.getHeight() * scale);
                    int xOff = (w - imgW) / 2;
                    int yOff = (h - imgH) / 2 + 30; // +30 → décale l'image vers le bas
                    g2.drawImage(bannerImg, xOff, yOff, imgW, imgH, null);
                    g2.setColor(new Color(0, 0, 0, 30));
                    g2.fillRect(0, 0, w, h);
                } else {
                    g2.setPaint(new GradientPaint(0, 0, SIDEBAR1, 0, h, BG));
                    g2.fillRect(0, 0, w, h);
                }
                // Dégradé en bas → BG
                g2.setPaint(new GradientPaint(0, h - 30, new Color(0, 0, 0, 0), 0, h, BG));
                g2.fillRect(0, h - 30, w, 30);
                // Lueur accent en bas à gauche
                g2.setPaint(new RadialGradientPaint(new Point(100, h), 200,
                    new float[]{0f, 1f}, new Color[]{new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 30), new Color(0, 0, 0, 0)}));
                g2.fillRect(0, h - 200, 260, 200);
                g2.dispose();
            }
        };
        banner.setPreferredSize(new Dimension(0, 330));
        banner.setBackground(BG);

        // ── Badge « Zokkyen » cliquable → GitHub ────────────────────────────────
        boolean[] badgeHov = {false};
        JLabel zokkyenBadge = new JLabel("Zokkyen GitHub \u2197") {
            @Override protected void paintComponent(Graphics g) {
                int w = getWidth(), h = getHeight();
                if (w == 0 || h == 0) { super.paintComponent(g); return; }
                int arc = h; // pilule : arc = hauteur
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color[] dark = themeManager.getCurrent().dark;
                Color bg2 = new Color(dark[ThemeDefinition.IDX_BG].getRed(),
                                      dark[ThemeDefinition.IDX_BG].getGreen(),
                                      dark[ThemeDefinition.IDX_BG].getBlue(), badgeHov[0] ? 230 : 190);
                Color acc = dark[ThemeDefinition.IDX_ACCENT];
                // fond arrondi
                g2.setColor(bg2);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                // bordure
                g2.setColor(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), badgeHov[0] ? 220 : 130));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
                g2.dispose();
                // texte clippé à la pilule pour éviter tout débordement rectangulaire
                Graphics2D gt = (Graphics2D) g.create();
                gt.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                gt.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, arc, arc));
                super.paintComponent(gt);
                gt.dispose();
            }
        };
        Color badgeAccent = themeManager.getCurrent().dark[ThemeDefinition.IDX_ACCENT];
        zokkyenBadge.setFont(new Font("Segoe UI", Font.BOLD, 12));
        zokkyenBadge.setForeground(badgeAccent);
        zokkyenBadge.setBorder(new EmptyBorder(4, 10, 4, 10));
        zokkyenBadge.setCursor(new Cursor(Cursor.HAND_CURSOR));
        zokkyenBadge.setOpaque(false);
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
        banner.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                Dimension ps = zokkyenBadge.getPreferredSize();
                zokkyenBadge.setSize(ps);
                zokkyenBadge.setLocation(banner.getWidth() - ps.width - 10, 10);
            }
        });
        banner.add(zokkyenBadge);
        return banner;
    }

    private JPanel buildConsole() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(BG);
        wrap.setBorder(new EmptyBorder(0, 16, 0, 16));

        JLabel title = new JLabel("JOURNAL");
        title.setFont(new Font("Segoe UI", Font.BOLD, 9));
        title.setForeground(TEXT_DIM);
        title.setBorder(new EmptyBorder(8, 4, 6, 0));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(FONT_MONO);
        logArea.setBackground(CONSOLE_BG);
        logArea.setForeground(TEXT);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        // Empêcher le JTextArea de scroller automatiquement — on gère ça dans appendLog
        ((javax.swing.text.DefaultCaret) logArea.getCaret())
            .setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);

        JScrollPane scroll = new JScrollPane(logArea);
        logScrollPane = scroll;
        scroll.setBorder(BorderFactory.createLineBorder(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 100), 1));
        scroll.getViewport().setBackground(CONSOLE_BG);

        wrap.add(title,  BorderLayout.NORTH);
        wrap.add(scroll, BorderLayout.CENTER);
        return wrap;
    }

    private JPanel buildBottomBar() {
        JPanel bar = new JPanel(new BorderLayout(16, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, BTM1, 0, getHeight(), BTM2));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 100)),
            new EmptyBorder(10, 16, 10, 16)
        ));

        // Gauche : statut + progression
        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.setOpaque(false);
        statusLabel = new JLabel("Initialisation...");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusLabel.setForeground(TEXT);
        progressBar = new JProgressBar(0, 100);
        progressBar.setPreferredSize(new Dimension(100, 5));
        progressBar.setForeground(ACCENT);
        progressBar.setBackground(CONSOLE_BG);
        progressBar.setStringPainted(false);
        left.add(statusLabel,  BorderLayout.NORTH);
        left.add(progressBar, BorderLayout.SOUTH);

        // Droite : boutons
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        JButton settingsBtn = mkButton("Paramètres", CARD_BG, TEXT, 14, 40);
        settingsBtn.setPreferredSize(new Dimension(120, 40));
        settingsBtn.addActionListener(e -> openSettings());

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
                    int sh = hovered ? 22 : 0;
                    Color top = new Color(
                        Math.min(255, ACCENT.getRed()   + 45 + sh),
                        Math.min(255, ACCENT.getGreen() + 22 + sh),
                        Math.min(255, ACCENT.getBlue()  + 35 + sh));
                    Color bot = new Color(
                        Math.max(0, ACCENT.getRed()   - 12),
                        Math.max(0, ACCENT.getGreen() -  6),
                        Math.max(0, ACCENT.getBlue()  -  9));
                    g2.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bot));
                } else {
                    g2.setColor(new Color(50, 50, 60));
                }
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 22, 22);
                // Sheen — filet blanc en haut pour l'effet brillant
                if (isEnabled()) {
                    g2.setColor(new Color(255, 255, 255, hovered ? 50 : 28));
                    g2.fillRoundRect(3, 2, getWidth()-7, (getHeight()-4)/2, 18, 18);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        playButton.setForeground(Color.WHITE);
        playButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
        playButton.setFocusPainted(false);
        playButton.setContentAreaFilled(false);
        playButton.setBorderPainted(false);
        playButton.setOpaque(false);
        playButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        playButton.setBorder(new EmptyBorder(0, 24, 0, 24));
        playButton.setPreferredSize(new Dimension(160, 44));
        playButton.setEnabled(false);
        playButton.addActionListener(e -> handlePlayButton());

        right.add(settingsBtn);
        right.add(playButton);

        bar.add(left,  BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Info-cards — chargement asynchrone
    // ═════════════════════════════════════════════════════════════════════════
    private void initInfoCards() {
        // Version du modpack installé (depuis launcher_config)
        String cached = config.getModpackVersion();
        final String mv = (cached != null && !cached.isBlank()) ? cached : "–";

        // Java version (JRE actuel)
        String jvRaw = System.getProperty("java.version", "?");
        final String jv = "Java " + jvRaw.split("\\.")[0].replaceAll("[^0-9]", "");

        // RAM allouée vs totale
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

        // Mods actifs
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

        SwingUtilities.invokeLater(() -> {
            cachedInfoModpack = mv;
            cachedInfoJava    = jv;
            cachedInfoRam     = ramStr;
            cachedInfoMods    = modsStr;
            infoModpackVal.setText(mv);
            infoJavaVal   .setText(jv);
            infoRamVal    .setText(ramStr);
            infoModsVal   .setText(modsStr);
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Logique métier
    // ═════════════════════════════════════════════════════════════════════════
    private void handlePlayButton() {
        String t = playButton.getText();
        if (t.contains("INSTALLATION"))    installModpack();
        else if (t.contains("METTRE À JOUR")) updateModpack();
        else                               launchGame();
    }

    private void checkAndUpdate() {
        // Vérification du serveur Minecraft en parallèle du check modpack
        if (cachedServerOnline == null) {
            new Thread(this::checkServerOnce).start();
        }
        try {
            appendLog("Initialisation du client Zokkymon...");
            appendLog("Version Minecraft : " + config.getMinecraftVersion());
            appendLog("Fabric : " + config.getFabricVersion());
            appendLog("─────────────────────────────────────");
            Thread.sleep(400);

            String token = config.getModpackToken();
            if (token == null || token.trim().isEmpty()) {
                setStatus("Configuration manquante");
                appendLog("[ERR] Token d'accès au modpack manquant !");
                appendLog("[>] Configurez launcher_config.json → \"modpackToken\"");
                SwingUtilities.invokeLater(() -> progressBar.setForeground(DANGER));
                return;
            }

            setStatus("Vérification du modpack...");
            appendLog("[PRÉPARATION] Vérification du modpack...");
            String status = updater.getModpackStatus();
            cachedModpackStatus = status; // mise en cache pour le rebuild de thème
            // Mettre à jour la carte modpack avec la version désormais connue
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

    private void launchGame() {
        playButton.setEnabled(false);
        setStatus("Lancement en cours...");
        appendLog(">> Démarrage du processus de jeu...");
        long t0 = System.currentTimeMillis();
        new Thread(() -> {
            try {
                // ── Vérification / rafraîchissement du token MSA ──────────────
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
                SwingUtilities.invokeLater(() -> playButton.setEnabled(true));
            }
        }).start();
    }

    private void openSettings() {
        // ── Panel principal aux couleurs du launcher ──
        JPanel panel = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CONSOLE_BG);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));

        UIManager.put("OptionPane.background",        CONSOLE_BG);
        UIManager.put("Panel.background",             CONSOLE_BG);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Button.background",            CONSOLE_BG);
        UIManager.put("Button.foreground",            TEXT);

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
        cRam.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                JButton btn = new JButton() {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(CONSOLE_BG);
                        g2.fillRect(0, 0, getWidth(), getHeight());
                        // Flèche
                        g2.setColor(ACCENT);
                        int cx = getWidth() / 2, cy = getHeight() / 2;
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
                comboBox.setBackground(CONSOLE_BG);
            }
            @Override
            public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                g.setColor(CONSOLE_BG);
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
            @Override
            public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
                ListCellRenderer<Object> renderer = comboBox.getRenderer();
                Component c = renderer.getListCellRendererComponent(
                        listBox, comboBox.getSelectedItem(), -1, false, false);
                c.setBackground(CONSOLE_BG);
                c.setForeground(TEXT);
                currentValuePane.paintComponent(g, c, comboBox,
                        bounds.x, bounds.y, bounds.width, bounds.height, false);
            }
        });
        // Popup : fond et sélection
        cRam.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                lbl.setBackground(isSelected ? CARD_BG.darker() : CONSOLE_BG);
                lbl.setForeground(isSelected ? ACCENT : TEXT);
                lbl.setBorder(new EmptyBorder(4, 10, 4, 10));
                return lbl;
            }
        });
        panel.add(cRam, c);

        c.gridx = 0; c.gridy = 1;
        JLabel lblPath = settingsLbl("Dossier d'installation");
        panel.add(lblPath, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;

        JTextField fPath = new JTextField(config.getInstallPath(), 22) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CONSOLE_BG);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        fPath.setOpaque(false);
        fPath.setBackground(CONSOLE_BG);
        fPath.setForeground(TEXT);
        fPath.setCaretColor(TEXT);
        fPath.setBorder(new EmptyBorder(4, 8, 4, 8));

        JButton browse = mkButton("Parcourir", CONSOLE_BG, TEXT, 10, 28);
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                fPath.setText(fc.getSelectedFile().getAbsolutePath());
        });

        JPanel pathRow = new JPanel(new BorderLayout(6, 0));
        pathRow.setOpaque(false);
        pathRow.add(fPath,  BorderLayout.CENTER);
        pathRow.add(browse, BorderLayout.EAST);
        panel.add(pathRow, c);

        // ── Ligne sélecteur de thème ──────────────────────────────────────────
        c.gridx = 0; c.gridy = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        JLabel lblThemeSelect = settingsLbl("Thème");
        panel.add(lblThemeSelect, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;

        // Snapshot du thème initial pour pouvoir annuler
        final String[] originalThemeId = {themeManager.getActiveId()};

        java.util.List<ThemeDefinition> themeList = new java.util.ArrayList<>(themeManager.getAll());
        JComboBox<ThemeDefinition> cTheme = buildThemeCombo(themeList);
        for (int ti = 0; ti < themeList.size(); ti++) {
            if (themeList.get(ti).id.equals(themeManager.getActiveId())) {
                cTheme.setSelectedIndex(ti);
                break;
            }
        }
        panel.add(cTheme, c);

        // ── Ligne mode clair/sombre ───────────────────────────────────────────
        c.gridx = 0; c.gridy = 3; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        JLabel lblTheme = settingsLbl("Mode");
        panel.add(lblTheme, c);
        c.gridx = 1;

        boolean[] darkState = {config.isDarkMode()};
        // Runnable de rafraîchissement du dialog (assigné après la création de tous les composants)
        Runnable[] refreshDialog = {null};

        // Mode — libellé (déclaré avant le listener du switch)
        JLabel switchLbl = new JLabel(darkState[0] ? " Mode sombre" : " Mode clair");
        switchLbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        switchLbl.setForeground(TEXT);

        JToggleButton themeSwitch = new JToggleButton() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight(), r = h;
                // Effacer d'abord avec la couleur du fond du dialog pour éviter les artefacts FlatLaf
                g2.setColor(CONSOLE_BG);
                g2.fillRect(0, 0, w, h);
                // Piste : ACCENT quand actif (mode sombre),
                // mélange CONSOLE_BG+ACCENT quand inactif → couleur thème sans gris parasite
                Color inactiveTrack = new Color(
                    (CONSOLE_BG.getRed()   + ACCENT.getRed())   / 2,
                    (CONSOLE_BG.getGreen() + ACCENT.getGreen()) / 2,
                    (CONSOLE_BG.getBlue()  + ACCENT.getBlue())  / 2
                );
                Color track = darkState[0] ? ACCENT : inactiveTrack;
                g2.setColor(track);
                g2.fillRoundRect(0, 0, w, h, r, r);
                // Luminance perçue de la piste (formule BT.601) pour choisir le contraste icône/pastille
                int lum = (track.getRed() * 299 + track.getGreen() * 587 + track.getBlue() * 114) / 1000;
                Color onTrack = lum > 160 ? new Color(30, 30, 30) : Color.WHITE;
                // Icône thème
                g2.setColor(onTrack);
                g2.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 11));
                String icon = darkState[0] ? "☽" : "☀";
                FontMetrics fm = g2.getFontMetrics();
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                if (darkState[0]) {
                    g2.drawString(icon, 6, ty);
                } else {
                    g2.drawString(icon, w - fm.stringWidth(icon) - 6, ty);
                }
                // Pastille — couleur contrastante calculée selon la luminance de la piste
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
            themeSwitch.repaint();
            // Aperçu live sans sauvegarder
            rebuildUI(darkState[0]);
            // Rafraîchit aussi les couleurs du dialog
            if (refreshDialog[0] != null) refreshDialog[0].run();
        });

        // Met à jour le libellé du switch quand on bascule
        themeSwitch.addActionListener(e -> switchLbl.setText(darkState[0] ? " Mode sombre" : " Mode clair"));

        JPanel switchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        switchRow.setOpaque(false);
        switchRow.add(themeSwitch);
        switchRow.add(switchLbl);
        panel.add(switchRow, c);

        // Changement de thème en live depuis le sélecteur
        cTheme.addActionListener(e -> {
            ThemeDefinition sel = (ThemeDefinition) cTheme.getSelectedItem();
            if (sel != null && !sel.id.equals(themeManager.getActiveId())) {
                themeManager.setActiveId(sel.id);
                rebuildUI(darkState[0]);
                if (refreshDialog[0] != null) refreshDialog[0].run();
            }
        });

        // ── Dialog personnalisée ──
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
        wrapper.setBackground(CONSOLE_BG);
        wrapper.setBorder(new EmptyBorder(4, 4, 12, 4));
        wrapper.add(panel,  BorderLayout.CENTER);
        wrapper.add(btnRow, BorderLayout.SOUTH);

        // Assigner le runnable de refresh maintenant que tous les composants existent
        refreshDialog[0] = () -> {
            wrapper.setBackground(CONSOLE_BG);
            for (JLabel l : new JLabel[]{lblRam, lblPath, lblThemeSelect, lblTheme, switchLbl}) l.setForeground(TEXT);
            fPath.setForeground(TEXT);
            fPath.setCaretColor(TEXT);
            btnOk.setBackground(ACCENT);
            btnOk.setForeground(BG);
            btnCancel.setBackground(CARD_BG);
            btnCancel.setForeground(TEXT);
            browse.setBackground(CONSOLE_BG);
            browse.setForeground(TEXT);
            cRam.setForeground(TEXT);
            cRam.setBorder(BorderFactory.createLineBorder(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
            cTheme.setBorder(BorderFactory.createLineBorder(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
            dialog.repaint();
        };

        dialog.setContentPane(wrapper);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);

        // Réinitialiser UIManager
        UIManager.put("OptionPane.background",        null);
        UIManager.put("Panel.background",             null);
        UIManager.put("OptionPane.messageForeground", null);
        UIManager.put("Button.background",            null);
        UIManager.put("Button.foreground",            null);

        if (result[0] == JOptionPane.OK_OPTION) {
            config.setRamAllocation((String) cRam.getSelectedItem());
            config.setInstallPath(fPath.getText());
            // Thème (id) — déjà appliqué en live, sauvegarder seulement
            ThemeDefinition selectedTheme = (ThemeDefinition) cTheme.getSelectedItem();
            if (selectedTheme != null) config.setActiveTheme(selectedTheme.id);
            if (darkState[0] != config.isDarkMode()) {
                // Mode clair/sombre déjà appliqué en live, on sauvegarde juste
                config.setDarkMode(darkState[0]);
            }
            appendLog("Paramètres enregistrés.");
            new Thread(this::initInfoCards).start();
        } else {
            // Annuler : revenir au thème et au mode d'origine si changés en live
            boolean themeChanged = !themeManager.getActiveId().equals(originalThemeId[0]);
            boolean modeChanged  = darkState[0] != config.isDarkMode();
            if (themeChanged) themeManager.setActiveId(originalThemeId[0]);
            if (themeChanged || modeChanged) {
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

    public void setStatus(String status) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(status));
    }

    public void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            if (logScrollPane != null) {
                javax.swing.JScrollBar bar = logScrollPane.getVerticalScrollBar();
                // Auto-scroll uniquement si l'utilisateur est déjà en bas (marge 40px)
                boolean atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
                logArea.append(message + "\n");
                if (atBottom) {
                    // Forcer le scroll après que le layout a été mis à jour
                    SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
                }
            } else {
                logArea.append(message + "\n");
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Helpers design
    // ═════════════════════════════════════════════════════════════════════════

    /** Bouton arrondi avec effet de survol. */
    private JButton mkButton(String text, Color bg, Color fg, int arc, int height) {
        JButton btn = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, arc, arc);
                // Bordure subtile
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 60));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, arc, arc);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setOpaque(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setBorder(new EmptyBorder(0, 14, 0, 14));
        btn.setPreferredSize(new Dimension(btn.getPreferredSize().width, height));
        btn.addMouseListener(new MouseAdapter() {
            private Color base = bg;
            public void mouseEntered(MouseEvent e) { base = btn.getBackground(); btn.setBackground(base.brighter()); }
            public void mouseExited (MouseEvent e) { btn.setBackground(base); }
        });
        return btn;
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

    private JComponent hLine() {
        JPanel line = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                int r = ACCENT.getRed(), gv = ACCENT.getGreen(), b = ACCENT.getBlue();
                g2.setPaint(new GradientPaint(0, 0, new Color(r, gv, b, 110), getWidth()/2, 0, new Color(r, gv, b, 40)));
                g2.fillRect(0, 0, getWidth(), 1);
                g2.dispose();
            }
        };
        line.setOpaque(false);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        line.setAlignmentX(LEFT_ALIGNMENT);
        return line;
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

    /**
     * Crée une JComboBox stylée pour la sélection du thème,
     * cohérente avec le design du dialog paramètres (même style que cRam).
     */
    private JComboBox<ThemeDefinition> buildThemeCombo(java.util.List<ThemeDefinition> themeList) {
        JComboBox<ThemeDefinition> combo = new JComboBox<>(themeList.toArray(new ThemeDefinition[0]));
        combo.setForeground(TEXT);
        combo.setBorder(BorderFactory.createLineBorder(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
        combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                JButton btn = new JButton() {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(CONSOLE_BG);
                        g2.fillRect(0, 0, getWidth(), getHeight());
                        g2.setColor(ACCENT);
                        int cx = getWidth() / 2, cy = getHeight() / 2;
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
                comboBox.setBackground(CONSOLE_BG);
            }
            @Override
            public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                g.setColor(CONSOLE_BG);
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
            @Override
            public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
                ListCellRenderer<Object> renderer = comboBox.getRenderer();
                Component c2 = renderer.getListCellRendererComponent(
                        listBox, comboBox.getSelectedItem(), -1, false, false);
                c2.setBackground(CONSOLE_BG);
                c2.setForeground(TEXT);
                currentValuePane.paintComponent(g, c2, comboBox,
                        bounds.x, bounds.y, bounds.width, bounds.height, false);
            }
        });
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                lbl.setBackground(isSelected ? CARD_BG.darker() : CONSOLE_BG);
                lbl.setForeground(isSelected ? ACCENT : TEXT);
                lbl.setBorder(new EmptyBorder(4, 10, 4, 10));
                return lbl;
            }
        });
        return combo;
    }
}
