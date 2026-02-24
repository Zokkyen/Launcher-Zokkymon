package com.zokkymon.launcher;

import org.json.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Gère le chargement et la sélection des thèmes du launcher.
 *
 * <p>Les thèmes externes sont placés dans {@code ~/.zokkymon/themes/<id>/theme.json},
 * avec en option un {@code banner.png} dans le même dossier.
 * Le thème {@code "default"} est toujours disponible (palette Zokkymon intégrée).</p>
 *
 * <h3>Format de theme.json :</h3>
 * <pre>
 * {
 *   "displayName": "Mon Thème",
 *   "light": {
 *     "bg":       [R, G, B],
 *     "cardBg":   [R, G, B],
 *     "sidebar1": [R, G, B],
 *     "sidebar2": [R, G, B],
 *     "console":  [R, G, B],
 *     "accent":   [R, G, B],
 *     "warning":  [R, G, B],
 *     "danger":   [R, G, B],
 *     "text":     [R, G, B],
 *     "textDim":  [R, G, B],
 *     "btm1":     [R, G, B],
 *     "btm2":     [R, G, B]
 *   },
 *   "dark": { ... mêmes clés ... }
 * }
 * </pre>
 */
public class ThemeManager {

    private final File themesDir;
    private final LinkedHashMap<String, ThemeDefinition> themes = new LinkedHashMap<>();
    private String activeId = "default";

    public ThemeManager(File zokkymonBaseDir) {
        this.themesDir = new File(zokkymonBaseDir, "themes");
        addBuiltinDefault();
        loadBuiltinThemesFromClasspath();
        loadExternalThemes();
    }

    // ── Thème intégré "default" ───────────────────────────────────────────────

    private void addBuiltinDefault() {
        // Palette Graphite · Magenta
        Color[] light = {
            new Color(249, 247, 255), // 0  bg          — blanc lavande
            new Color(238, 234, 250), // 1  cardBg       — lavande clair
            new Color(230, 226, 245), // 2  sidebar1
            new Color(218, 213, 238), // 3  sidebar2
            new Color(252, 250, 255), // 4  console      — quasi-blanc
            new Color(168,  20, 155), // 5  accent       — magenta profond
            new Color(180, 108,   0), // 6  warning      — ambre sombre
            new Color(196,  30,  30), // 7  danger       — rouge sombre
            new Color( 20,  15,  30), // 8  text         — quasi-noir bleuté
            new Color(108, 100, 128), // 9  textDim      — gris-violet
            new Color(228, 223, 243), // 10 btm1
            new Color(215, 210, 233)  // 11 btm2
        };
        Color[] dark = {
            new Color( 13,  13,  15), // 0  bg          — noir mat bleuté
            new Color( 22,  22,  28), // 1  cardBg       — graphite
            new Color( 18,  18,  23), // 2  sidebar1
            new Color( 10,  10,  13), // 3  sidebar2
            new Color(  8,   8,  10), // 4  console      — presque noir
            new Color(220,  50, 195), // 5  accent       — magenta vif
            new Color(245, 158,  11), // 6  warning      — ambre
            new Color(239,  68,  68), // 7  danger       — rouge
            new Color(240, 240, 248), // 8  text         — blanc bleuté
            new Color( 95,  95, 115), // 9  textDim      — gris bleuté
            new Color( 16,  16,  21), // 10 btm1
            new Color( 10,  10,  13)  // 11 btm2
        };
        themes.put("default", new ThemeDefinition("default", "Zokkymon (Défaut)", light, dark, null));
    }

    // ── Thèmes embarqués dans le JAR (/themes/<id>/) ─────────────────────────

    private void loadBuiltinThemesFromClasspath() {
        try (InputStream idx = ThemeManager.class.getResourceAsStream("/themes/index.txt")) {
            if (idx == null) return;
            new String(idx.readAllBytes(), StandardCharsets.UTF_8)
                .lines().map(String::strip).filter(s -> !s.isEmpty())
                .filter(id -> !"default".equals(id))
                .forEach(id -> {
                    try (InputStream jsonIs = ThemeManager.class.getResourceAsStream("/themes/" + id + "/theme.json")) {
                        if (jsonIs == null) return;
                        JSONObject obj = new JSONObject(new String(jsonIs.readAllBytes(), StandardCharsets.UTF_8));
                        String displayName = obj.optString("displayName", id);
                        Color[] light = parsePalette(obj.getJSONObject("light"));
                        Color[] dark  = parsePalette(obj.getJSONObject("dark"));
                        BufferedImage banner = null;
                        try (InputStream bi = ThemeManager.class.getResourceAsStream("/themes/" + id + "/banner.png")) {
                            if (bi != null) banner = ImageIO.read(bi);
                        } catch (Exception ignored) {}
                        themes.put(id, new ThemeDefinition(id, displayName, light, dark, banner));
                    } catch (Exception e) {
                        System.err.println("[ThemeManager] Erreur thème embarqué '" + id + "': " + e.getMessage());
                    }
                });
        } catch (Exception e) {
            System.err.println("[ThemeManager] Erreur lecture /themes/index.txt: " + e.getMessage());
        }
    }

    // ── Thèmes externes dans ~/.zokkymon/themes/ (priorité sur les embarqués) ─

    private void loadExternalThemes() {
        if (!themesDir.isDirectory()) return;
        File[] dirs = themesDir.listFiles(File::isDirectory);
        if (dirs == null) return;
        Arrays.sort(dirs, Comparator.comparing(File::getName));

        for (File dir : dirs) {
            String id = dir.getName();

            if ("default".equals(id)) {
                // Cas spécial : permet de surcharger uniquement la bannière du thème par défaut
                File bannerF = new File(dir, "banner.png");
                if (bannerF.exists()) {
                    try {
                        BufferedImage banner = ImageIO.read(bannerF);
                        ThemeDefinition def = themes.get("default");
                        themes.put("default", new ThemeDefinition(
                            def.id, def.displayName, def.light, def.dark, banner));
                    } catch (Exception ignored) {}
                }
                continue;
            }

            File jsonFile = new File(dir, "theme.json");
            if (!jsonFile.exists()) continue;

            try {
                String content = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
                JSONObject obj = new JSONObject(content);
                String displayName = obj.optString("displayName", id);
                Color[] light = parsePalette(obj.getJSONObject("light"));
                Color[] dark  = parsePalette(obj.getJSONObject("dark"));
                BufferedImage banner = null;
                File bannerFile = new File(dir, "banner.png");
                if (bannerFile.exists()) {
                    try { banner = ImageIO.read(bannerFile); } catch (Exception ignored) {}
                }
                themes.put(id, new ThemeDefinition(id, displayName, light, dark, banner));
            } catch (Exception e) {
                System.err.println("[ThemeManager] Erreur chargement thème '" + id + "': " + e.getMessage());
            }
        }
    }

    private static Color[] parsePalette(JSONObject p) {
        return new Color[]{
            parseColor(p, "bg"),       parseColor(p, "cardBg"),
            parseColor(p, "sidebar1"), parseColor(p, "sidebar2"),
            parseColor(p, "console"),  parseColor(p, "accent"),
            parseColor(p, "warning"),  parseColor(p, "danger"),
            parseColor(p, "text"),     parseColor(p, "textDim"),
            parseColor(p, "btm1"),     parseColor(p, "btm2")
        };
    }

    private static Color parseColor(JSONObject p, String key) {
        JSONArray arr = p.getJSONArray(key);
        return new Color(arr.getInt(0), arr.getInt(1), arr.getInt(2));
    }

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Retourne tous les thèmes chargés (default en premier, puis les externes par ordre alpha).
     */
    public Collection<ThemeDefinition> getAll() {
        return Collections.unmodifiableCollection(themes.values());
    }

    /** Retourne un thème par son id, ou "default" si introuvable. */
    public ThemeDefinition get(String id) {
        return themes.getOrDefault(id, themes.get("default"));
    }

    /** Retourne le thème actuellement actif. */
    public ThemeDefinition getCurrent() {
        return get(activeId);
    }

    /** Sélectionne le thème actif. L'id doit exister dans {@link #getAll()}. */
    public void setActiveId(String id) {
        if (themes.containsKey(id)) this.activeId = id;
    }

    /** Retourne l'id du thème actif. */
    public String getActiveId() {
        return activeId;
    }

    /**
     * Recharge tous les thèmes (embarqués + externes) — utile si l'utilisateur
     * a ajouté un thème dans ~/.zokkymon/themes/ pendant que le launcher tourne.
     * Le thème actif est restauré si toujours disponible, sinon remis à "default".
     */
    public void reload() {
        String savedId = activeId;
        themes.clear();
        addBuiltinDefault();
        loadBuiltinThemesFromClasspath();
        loadExternalThemes();
        activeId = themes.containsKey(savedId) ? savedId : "default";
    }
}
