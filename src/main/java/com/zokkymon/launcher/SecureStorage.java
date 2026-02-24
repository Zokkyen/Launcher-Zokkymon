package com.zokkymon.launcher;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.Base64;

/**
 * Chiffrement AES-256-GCM transparent pour les données sensibles (tokens MSA).
 *
 * <h3>Fonctionnement</h3>
 * <ul>
 *   <li>Une clé AES-256 est générée une seule fois par installation et stockée dans un
 *       KeyStore PKCS12 ({@code ~/.zokkymon/config/.ks}).</li>
 *   <li>Ce KeyStore est protégé par un mot de passe dérivé d'informations propres
 *       à la machine (username + hostname, hashés en SHA-256). La clé ne peut donc
 *       pas être utilisée depuis un autre profil ou une autre machine.</li>
 *   <li>Chaque appel à {@link #encrypt} génère un IV aléatoire (12 octets) — même
 *       texte ↦ chiffré différent à chaque fois.</li>
 *   <li>Si la clé ne peut plus être lue (machine changée, profil copié ailleurs…),
 *       {@link #decrypt} retourne {@code null} pour déclencher une reconnexion propre.</li>
 * </ul>
 */
public class SecureStorage {

    private static final String KS_PATH = System.getProperty("user.home")
            + File.separator + ".zokkymon"
            + File.separator + "config"
            + File.separator + ".ks";

    private static final String KEY_ALIAS    = "zokkymon-aes";
    private static final String ALGORITHM    = "AES/GCM/NoPadding";
    private static final int    IV_LEN       = 12;    // octets — recommandé pour GCM
    private static final int    GCM_TAG_BITS = 128;

    /** Cache de la clé en mémoire pour éviter de relire le keystore à chaque opération. */
    private static SecretKey cachedKey = null;

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Chiffre {@code plaintext} et retourne une chaîne Base64 sûre pour être stockée
     * dans un fichier JSON.
     *
     * @param plaintext texte clair (ex : access_token JWT)
     * @return Base64( IV[12] || ciphertext+GCM-tag ) ou {@code null} si {@code plaintext} est vide
     */
    public static String encrypt(String plaintext) throws Exception {
        if (plaintext == null || plaintext.isBlank()) return null;

        SecretKey key = getOrCreateKey();
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // Combiner IV + ciphertext dans un seul tableau
        byte[] combined = new byte[IV_LEN + encrypted.length];
        System.arraycopy(iv,        0, combined, 0,       IV_LEN);
        System.arraycopy(encrypted, 0, combined, IV_LEN,  encrypted.length);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
    }

    /**
     * Déchiffre une valeur produite par {@link #encrypt}.
     *
     * @param base64 valeur chiffrée (Base64 URL-safe)
     * @return texte clair, ou {@code null} si le déchiffrement échoue
     *         (clé inaccessible, valeur corrompue, ancienne valeur non chiffrée…)
     */
    public static String decrypt(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        try {
            SecretKey key = getOrCreateKey();
            byte[] combined    = Base64.getUrlDecoder().decode(base64);
            if (combined.length <= IV_LEN) return null;

            byte[] iv         = new byte[IV_LEN];
            byte[] ciphertext = new byte[combined.length - IV_LEN];
            System.arraycopy(combined, 0,       iv,         0, IV_LEN);
            System.arraycopy(combined, IV_LEN,  ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Valeur non chiffrée, clé changée, ou donnée corrompue → déclenche une reconnexion
            return null;
        }
    }

    /**
     * Indique si une valeur semble déjà chiffrée par cette classe
     * (Base64 URL-safe sans padding, longueur > IV).
     * Utilisé pour la migration transparente depuis l'ancien format en clair.
     */
    public static boolean looksEncrypted(String value) {
        if (value == null || value.isBlank()) return false;
        // Un JWT commence toujours par "ey" ; notre format chiffré commence par du Base64 URL-safe
        if (value.startsWith("ey")) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length > IV_LEN;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Gestion du KeyStore ───────────────────────────────────────────────────

    private static synchronized SecretKey getOrCreateKey() throws Exception {
        if (cachedKey != null) return cachedKey;

        Path ksPath = Paths.get(KS_PATH);
        char[] ksPass = deriveKeystorePassword();
        KeyStore ks = KeyStore.getInstance("PKCS12");

        if (Files.exists(ksPath)) {
            // Charger le KeyStore existant
            try (InputStream is = Files.newInputStream(ksPath)) {
                ks.load(is, ksPass);
            }
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry)
                    ks.getEntry(KEY_ALIAS, new KeyStore.PasswordProtection(ksPass));
            if (entry != null) {
                cachedKey = entry.getSecretKey();
                return cachedKey;
            }
        } else {
            ks.load(null, ksPass);
        }

        // Première utilisation : générer et persister une clé AES-256
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, new SecureRandom());
        SecretKey newKey = kg.generateKey();

        ks.setEntry(KEY_ALIAS,
                new KeyStore.SecretKeyEntry(newKey),
                new KeyStore.PasswordProtection(ksPass));

        Files.createDirectories(ksPath.getParent());
        try (OutputStream os = Files.newOutputStream(ksPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ks.store(os, ksPass);
        }

        cachedKey = newKey;
        return cachedKey;
    }

    /**
     * Dérive le mot de passe du KeyStore à partir d'informations machine-locales.
     * Résultat : SHA-256( "username@hostname:zokkymon" ) encodé en Base64.
     * Impossible à utiliser sur un autre profil ou une autre machine.
     */
    private static char[] deriveKeystorePassword() throws Exception {
        String hostname;
        try { hostname = InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { hostname = "unknown-host"; }

        String raw = System.getProperty("user.name", "user") + "@" + hostname + ":zokkymon";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash).toCharArray();
    }
}
