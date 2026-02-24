package com.zokkymon.launcher;

import java.awt.Color;
import java.io.File;

/**
 * Définition d'un thème du launcher.
 * Chaque thème contient deux palettes de couleurs (mode clair + mode sombre)
 * et un éventuel fichier bannière externe.
 *
 * <p>Indices dans les tableaux {@code light} et {@code dark} :</p>
 * <pre>
 *  0 = bg       1 = cardBg    2 = sidebar1  3 = sidebar2
 *  4 = console  5 = accent    6 = warning   7 = danger
 *  8 = text     9 = textDim  10 = btm1     11 = btm2
 * </pre>
 */
public class ThemeDefinition {

    public static final int IDX_BG       = 0;
    public static final int IDX_CARD_BG  = 1;
    public static final int IDX_SIDEBAR1 = 2;
    public static final int IDX_SIDEBAR2 = 3;
    public static final int IDX_CONSOLE  = 4;
    public static final int IDX_ACCENT   = 5;
    public static final int IDX_WARNING  = 6;
    public static final int IDX_DANGER   = 7;
    public static final int IDX_TEXT     = 8;
    public static final int IDX_TEXT_DIM = 9;
    public static final int IDX_BTM1     = 10;
    public static final int IDX_BTM2     = 11;

    /** Identifiant interne (= nom du dossier dans ~/.zokkymon/themes/). */
    public final String id;

    /** Nom affiché dans l'interface. */
    public final String displayName;

    /** Palette mode clair (12 couleurs dans l'ordre des IDX_*). */
    public final Color[] light;

    /** Palette mode sombre (12 couleurs dans l'ordre des IDX_*). */
    public final Color[] dark;

    /**
     * Fichier bannière PNG propre à ce thème, ou {@code null} pour utiliser
     * la bannière intégrée au JAR ({@code /banniere.png}).
     */
    public final File bannerFile;

    public ThemeDefinition(String id, String displayName,
                           Color[] light, Color[] dark, File bannerFile) {
        this.id          = id;
        this.displayName = displayName;
        this.light       = light;
        this.dark        = dark;
        this.bannerFile  = bannerFile;
    }

    @Override
    public String toString() { return displayName; }
}
