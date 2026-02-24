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
 * Interface principale du launcher — Design moderne "Glassmorphism"
 *
 * Layout : Full Background Cover
 * Sidebar  : Flottante semi-transparente (gauche)
 * Principal: Épuré, bouton Logs, énorme bouton Jouer avec effet Glow.
 */
public class LauncherGUI extends JFrame {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    // ── Gestionnaire de thèmes ────────────────────────────────────────────────
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

    // ── Cache des états réseau ────────────────────────────────────────────────
    private volatile Boolean cachedServerOnline   = null;
    private volatile String  cachedLauncherStatus = null;
    private volatile String  cachedLauncherNewVer = null;
    private volatile String  cachedModpackStatus  = null;
    private volatile String  cachedInfoModpack    = null;
    private volatile String  cachedInfoJava       = null;
    private volatile String  cachedInfoRam        = null;
    private volatile String  cachedInfoMods       = null;

    // ── Polices statiques ─────────────────────────────────────────────────────
    private static final Font FONT_MONO  = new Font("Consolas", Font.PLAIN,  12);

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
        themeManager = new ThemeManager(new java.io.File(System.getProperty("user.home"), ".zokkymon"));
        themeManager.setActiveId(config.getActiveTheme());
        applyTheme(config.isDarkMode());
        updater = new Updater(this, config);

        loadBannerForCurrentTheme();
        logoImg = loadImage("/zokkymon.png", "/zokkymon.ico");

        setTitle("Launcher Zokkymon");
        setSize(1100, 680);
        setMinimumSize(new Dimension(900, 600));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setResizable(true);
        loadWindowIcon();

        // Initialisation des logs avant l'UI pour éviter les NullPointer
        initLogs();

        // On crée la racine qui peindra le fond complet "Cover"
        JPanel root = buildRootPanel();
        setContentPane(root);
        setVisible(true);

        startBackgroundChecks();
        new Thread(this::checkAndUpdate).start();
    }

    /**
     * Initialise la console de logs en arrière-plan. 
     * Elle n'est plus affichée par défaut, mais est prête pour la fenêtre de logs.
     */
    private void initLogs() {
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(FONT_MONO);
        logArea.setBackground(CONSOLE_BG);
        logArea.setForeground(TEXT);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        ((javax.swing.text.DefaultCaret) logArea.getCaret()).setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);

        logScrollPane = new JScrollPane(logArea);
        logScrollPane.setBorder(BorderFactory.createLineBorder(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 80), 1));
        logScrollPane.getViewport().setBackground(CONSOLE_BG);
    }

    private void startBackgroundChecks() {
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

    /**
     * Panneau racine dessinant l'image de fond sur l'intégralité de la fenêtre (Glassmorphism bg)
     */
    private JPanel buildRootPanel() {
        JPanel root = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                
                int w = getWidth(), h = getHeight();
                
                if (bannerImg != null) {
                    // Calcul cover pour remplir l'écran sans distorsion
                    double scaleW = (double) w / bannerImg.getWidth();
                    double scaleH = (double) h / bannerImg.getHeight();
                    double scale  = Math.max(scaleW, scaleH);
                    int imgW = (int)(bannerImg.getWidth()  * scale);
                    int imgH = (int)(bannerImg.getHeight() * scale);
                    int xOff = (w - imgW) / 2;
                    int yOff = (h - imgH) / 2; 
                    
                    g2.drawImage(bannerImg, xOff, yOff, imgW, imgH, null);
                } else {
                    g2.setPaint(new GradientPaint(0, 0, SIDEBAR1, 0, h, BG));
                    g2.fillRect(0, 0, w, h);
                }
                
                // Overlay uniquement en mode sombre, très léger — en mode clair : aucun filtre
                boolean isDark = (BG.getRed() + BG.getGreen() + BG.getBlue()) < 200;
                if (isDark) {
                    g2.setColor(new Color(BG.getRed(), BG.getGreen(), BG.getBlue(), 55));
                    g2.fillRect(0, 0, w, h);
                }
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
                // Fond Glassmorphism : Opaque sombre semi-transparent au lieu du dégradé plein
                g2.setColor(new Color(SIDEBAR1.getRed(), SIDEBAR1.getGreen(), SIDEBAR1.getBlue(), 210));
                g2.fillRect(0, 0, getWidth(), getHeight());
                
                // Ligne séparatrice subtile
                Color sep = new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 40);
                g2.setColor(sep);
                g2.fillRect(getWidth()-1, 0, 1, getHeight());
                g2.dispose();
            }
        };
        s.setLayout(new BoxLayout(s, BoxLayout.Y_AXIS));
        s.setOpaque(false);
        s.setPreferredSize(new Dimension(240, 0));
        s.setBorder(new EmptyBorder(20, 16, 16, 16));

        s.add(buildLogoBlock());
        s.add(vSep(24));
        s.add(buildServerStatusPanel());
        s.add(vSep(8));
        s.add(buildAuthCard());
        s.add(vSep(24));

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
                int arc = 20; // Arrondi doux
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

    // Helper pour dessiner les cartes style Verre/Glassmorphism
    private void paintGlassCard(Graphics g, int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Fond carte — suit la couleur du thème
        g2.setColor(new Color(CARD_BG.getRed(), CARD_BG.getGreen(), CARD_BG.getBlue(), 200));
        g2.fillRoundRect(0, 0, width-1, height-1, 16, 16);
        
        // Liseré accent subtil
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
                authStatusLbl.setForeground(Color.WHITE);
                authActionBtn.setText("\u2192");
                authActionBtn.setToolTipText("Se connecter avec Microsoft");
            }
            authStatusLbl.repaint();
            authActionBtn.repaint();
        });
    }

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
                    SwingUtilities.invokeLater(dialog::dispose);
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
            }).start();

            dialog.setVisible(true);
        }).start();
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

        JLabel t = new JLabel(title);
        t.setFont(new Font("Segoe UI", Font.PLAIN, 11));
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
                g2.setColor(new Color(20, 30, 25, 140)); // Vert très foncé translucide
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
                g2.setColor(new Color(40, 25, 10, 160)); // Ambre foncé translucide
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

    // ── Zone principale (Main Area) Épurée ───────────────────────────────────
    private JPanel buildMainArea() {
        // Le panneau est transparent pour laisser apparaitre le full cover
        JPanel main = new JPanel(new BorderLayout());
        main.setOpaque(false); 
        
        // --- Badge Zokkyen ---
        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        topRow.setOpaque(false);
        topRow.setBorder(new EmptyBorder(12, 12, 12, 12));
        topRow.add(buildZokkyenBadge());
        
        main.add(topRow, BorderLayout.NORTH);
        // On ne met plus de console au centre ! L'espace est libre pour l'image de fond
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
                
                Color bgCol = new Color(20, 20, 25, badgeHov[0] ? 200 : 120);
                Color acc = ACCENT;
                int arc = h;
                
                g2.setColor(bgCol);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                g2.setColor(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), badgeHov[0] ? 255 : 120));
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

    private JPanel buildBottomBar() {
        JPanel bar = new JPanel(new BorderLayout(16, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                // Voile noir au bas pour que la barre se détache bien
                g2.setPaint(new GradientPaint(0, 0, new Color(BG.getRed(), BG.getGreen(), BG.getBlue(), 0), 0, getHeight(), new Color(BG.getRed(), BG.getGreen(), BG.getBlue(), 220)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(20, 30, 30, 30));

        // Gauche : statut + progression
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.setOpaque(false);
        statusLabel = new JLabel("Initialisation...");
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        statusLabel.setForeground(TEXT);
        progressBar = new JProgressBar(0, 100);
        progressBar.setPreferredSize(new Dimension(200, 6));
        progressBar.setForeground(ACCENT);
        progressBar.setBackground(CONSOLE_BG);
        progressBar.setStringPainted(false);
        progressBar.setBorderPainted(false);
        left.add(statusLabel,  BorderLayout.NORTH);
        left.add(progressBar, BorderLayout.SOUTH);

        // Droite : boutons (Logs, Paramètres, Jouer)
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        right.setOpaque(false);

        JButton logsBtn = mkButton("Logs", new Color(40, 40, 45, 180), Color.WHITE, 16, 46);
        logsBtn.addActionListener(e -> showLogsDialog());

        JButton settingsBtn = mkButton("Paramètres", new Color(40, 40, 45, 180), Color.WHITE, 16, 46);
        settingsBtn.addActionListener(e -> openSettings());

        // Bouton Jouer : Massive Glow Effect
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
                    // Ombre portée / Lueur externe (Glow)
                    if (hovered) {
                        g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 60));
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 30, 30);
                        g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 100));
                        g2.fillRoundRect(2, 2, getWidth()-4, getHeight()-4, 26, 26);
                    }
                    
                    // Dégradé saturé interne
                    g2.setPaint(new GradientPaint(0, 0, ACCENT.brighter(), 0, getHeight(), ACCENT.darker()));
                    g2.fillRoundRect(6, 6, getWidth()-12, getHeight()-12, 22, 22);
                    
                    // Sheen (Reflet)
                    g2.setColor(new Color(255, 255, 255, hovered ? 60 : 30));
                    g2.fillRoundRect(8, 8, getWidth()-16, (getHeight()-16)/2, 18, 18);
                } else {
                    g2.setColor(new Color(40, 40, 50, 200));
                    g2.fillRoundRect(6, 6, getWidth()-12, getHeight()-12, 22, 22);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        playButton.setForeground(Color.WHITE);
        playButton.setFont(new Font("Segoe UI", Font.BOLD, 18));
        playButton.setFocusPainted(false);
        playButton.setContentAreaFilled(false);
        playButton.setBorderPainted(false);
        playButton.setOpaque(false);
        playButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        playButton.setPreferredSize(new Dimension(220, 56));
        playButton.setEnabled(false);
        playButton.addActionListener(e -> handlePlayButton());

        right.add(logsBtn);
        right.add(settingsBtn);
        right.add(playButton);

        bar.add(left,  BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ── Boîte de dialogue des Logs ───────────────────────────────────────────
    private void showLogsDialog() {
        JDialog dialog = new JDialog(this, "Journal d'événements", false);
        dialog.setSize(750, 450);
        dialog.setLocationRelativeTo(this);
        
        JPanel cp = new JPanel(new BorderLayout());
        cp.setBackground(BG);
        cp.setBorder(new EmptyBorder(10, 10, 10, 10));
        cp.add(logScrollPane, BorderLayout.CENTER);
        
        JButton closeBtn = mkButton("Fermer", CARD_BG, TEXT, 12, 34);
        closeBtn.addActionListener(e -> dialog.dispose());
        
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 10));
        bp.setOpaque(false);
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

    private void launchGame() {
        playButton.setEnabled(false);
        setStatus("Lancement en cours...");
        appendLog(">> Démarrage du processus de jeu...");
        long t0 = System.currentTimeMillis();
        new Thread(() -> {
            try {
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
        cRam.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                JButton btn = new JButton() {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(CARD_BG);
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
        cRam.setRenderer(new DefaultListCellRenderer() {
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
        panel.add(cRam, c);

        c.gridx = 0; c.gridy = 1;
        JLabel lblPath = settingsLbl("Dossier d'installation");
        panel.add(lblPath, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;

        JTextField fPath = new JTextField(config.getInstallPath(), 22) {
            @Override public Color getBackground() { return CARD_BG; }
            @Override public Color getForeground()  { return TEXT; }
        };
        fPath.setOpaque(true);
        fPath.setForeground(TEXT);
        fPath.setCaretColor(TEXT);
        fPath.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 100), 1),
            new EmptyBorder(4, 8, 4, 8)
        ));

        JButton browse = mkButton("Parcourir", CARD_BG, TEXT, 10, 28);
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

        c.gridx = 0; c.gridy = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        JLabel lblThemeSelect = settingsLbl("Thème");
        panel.add(lblThemeSelect, c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;

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
                // Pas de fillRect: le composant est opaque=false, le fond du parent transparaît
                Color inactiveTrack = CARD_BG;
                Color track = darkState[0] ? ACCENT : inactiveTrack;
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
            themeSwitch.repaint();
            switchLbl.setText(darkState[0] ? " Sombre" : " Lumineux");
            rebuildUI(darkState[0]);
            if (refreshDialog[0] != null) refreshDialog[0].run();
        });

        JPanel switchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        switchRow.setOpaque(false);
        switchRow.add(themeSwitch);
        switchRow.add(switchLbl);
        panel.add(switchRow, c);

        cTheme.addActionListener(e -> {
            ThemeDefinition sel = (ThemeDefinition) cTheme.getSelectedItem();
            if (sel != null && !sel.id.equals(themeManager.getActiveId())) {
                themeManager.setActiveId(sel.id);
                rebuildUI(darkState[0]);
                if (refreshDialog[0] != null) refreshDialog[0].run();
            }
        });

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
            // Fonds
            wrapper.setBackground(BG);
            UIManager.put("OptionPane.background",        BG);
            UIManager.put("Panel.background",             BG);
            UIManager.put("OptionPane.messageForeground", TEXT);
            // Labels
            lblRam.setForeground(TEXT);
            lblPath.setForeground(TEXT);
            lblThemeSelect.setForeground(TEXT);
            lblTheme.setForeground(TEXT);
            switchLbl.setForeground(TEXT);
            // Champ chemin
            fPath.setForeground(TEXT);
            fPath.setCaretColor(TEXT);
            fPath.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 100), 1),
                new EmptyBorder(4, 8, 4, 8)
            ));
            fPath.repaint();
            // Boutons
            btnOk.setBackground(ACCENT);
            btnOk.setForeground(BG);
            btnCancel.setBackground(CARD_BG);
            btnCancel.setForeground(TEXT);
            browse.setBackground(CARD_BG);
            browse.setForeground(TEXT);
            // Combos
            cRam.setForeground(TEXT);
            cTheme.setForeground(TEXT);
            // Repaint global
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
            config.setRamAllocation((String) cRam.getSelectedItem());
            config.setInstallPath(fPath.getText());
            ThemeDefinition selectedTheme = (ThemeDefinition) cTheme.getSelectedItem();
            if (selectedTheme != null) config.setActiveTheme(selectedTheme.id);
            if (darkState[0] != config.isDarkMode()) config.setDarkMode(darkState[0]);
            appendLog("Paramètres enregistrés.");
            new Thread(this::initInfoCards).start();
        } else {
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
                boolean atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
                logArea.append(message + "\n");
                if (atBottom) {
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

    private JButton mkButton(String text, Color bg, Color fg, int arc, int height) {
        JButton btn = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, arc, arc);
                g2.setColor(new Color(255, 255, 255, 20)); // Bordure très discrète
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, arc, arc);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setOpaque(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setBorder(new EmptyBorder(0, 16, 0, 16));
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
                        g2.setColor(CARD_BG);
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
                Component c2 = renderer.getListCellRendererComponent(
                        listBox, comboBox.getSelectedItem(), -1, false, false);
                c2.setBackground(CARD_BG);
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
                lbl.setBackground(isSelected ? SIDEBAR1 : CARD_BG);
                lbl.setForeground(isSelected ? ACCENT : TEXT);
                lbl.setBorder(new EmptyBorder(4, 10, 4, 10));
                return lbl;
            }
        });
        return combo;
    }
}