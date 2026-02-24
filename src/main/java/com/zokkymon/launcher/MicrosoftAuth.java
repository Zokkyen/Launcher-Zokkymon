package com.zokkymon.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Gère l'authentification Microsoft → Xbox Live → XSTS → Minecraft Services.
 *
 * <p>Utilise le <b>Device Code Flow</b> : pas de popup navigateur intégré dans le launcher.
 * L'utilisateur reçoit un court code à saisir sur {@code microsoft.com/devicelogin}
 * dans son navigateur habituel. L'application poll silencieusement en arrière-plan.
 *
 * <h3>Prérequis Azure</h3>
 * <ol>
 *   <li>Aller sur <a href="https://portal.azure.com">portal.azure.com</a> → Entra ID →
 *       App registrations → New registration</li>
 *   <li>Cocher « Accounts in any organizational directory … and personal Microsoft accounts »</li>
 *   <li>Ajouter un Redirect URI de type « Public client/native » :
 *       {@code https://login.microsoftonline.com/common/oauth2/nativeclient}</li>
 *   <li>Dans « Authentication », activer « Allow public client flows »</li>
 *   <li>Copier l'Application (client) ID dans {@code launcher_config.json} → champ {@code msaClientId}</li>
 * </ol>
 */
public class MicrosoftAuth {

    // ── Enregistrement Azure ──────────────────────────────────────────────────
    /**
     * Client ID de l'app Azure (Entra ID).
     *
     * <p>Valeur injectée au démarrage via {@link #setClientId(String)} à partir de
     * {@code launcher_config.json} (champ {@code msaClientId}). Elle n'est jamais
     * stockée en dur dans le code source.
     *
     * <p>Pour configurer l'app Azure (gratuit, 5 min) :
     * <ol>
     *   <li>Allez sur <b>https://portal.azure.com</b>.<br>
     *       Connectez-vous avec votre compte Microsoft personnel.</li>
     *   <li>Entra ID → App registrations → New registration</li>
     *   <li>Audience : <b>Personal Microsoft accounts only</b></li>
     *   <li>Redirect URI : Public client/native →
     *       {@code https://login.microsoftonline.com/common/oauth2/nativeclient}</li>
     *   <li>Authentication → activer <b>Allow public client flows</b></li>
     *   <li>Copiez l'Application (client) ID dans
     *       {@code launcher_config.json} → champ {@code msaClientId}.</li>
     * </ol>
     */
    private static String clientId = "";

    /** Injecte le Client ID depuis la configuration (appelé au démarrage). */
    public static void setClientId(String id) {
        clientId = (id != null) ? id.strip() : "";
    }

    /** @deprecated Accès interne uniquement — utiliser {@link #setClientId(String)} pour définir. */
    static String getClientId() { return clientId; }

    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL        = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_URL          = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL         = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_AUTH_URL      = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL   = "https://api.minecraftservices.com/minecraft/profile";
    private static final String SCOPE            = "XboxLive.signin offline_access";

    // ── Modèles de données ───────────────────────────────────────────────────

    /** Résultat de l'étape 1 : les informations du code à présenter à l'utilisateur. */
    public static class DeviceCodeResult {
        public final String userCode;
        public final String verificationUri;
        public final String deviceCode;
        public final int    intervalSec;
        public final int    expiresIn;

        DeviceCodeResult(String userCode, String verificationUri, String deviceCode, int intervalSec, int expiresIn) {
            this.userCode        = userCode;
            this.verificationUri = verificationUri;
            this.deviceCode      = deviceCode;
            this.intervalSec     = intervalSec;
            this.expiresIn       = expiresIn;
        }
    }

    /** Profil Minecraft complet, résultat de l'authentification réussie. */
    public static class McProfile {
        public final String username;
        /** UUID sans tirets, tel que fourni par l'API Mojang. */
        public final String uuid;
        public final String accessToken;
        public final String refreshToken;
        /** Timestamp (ms) au-delà duquel le mc_access_token est expiré. */
        public final long   expiresAtMs;

        McProfile(String username, String uuid, String accessToken, String refreshToken, long expiresAtMs) {
            this.username     = username;
            this.uuid         = uuid;
            this.accessToken  = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtMs  = expiresAtMs;
        }

        /** UUID formaté avec tirets (ex : 550e8400-e29b-41d4-a716-446655440000). */
        public String formattedUuid() {
            if (uuid == null || uuid.contains("-")) return uuid;
            return uuid.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
        }

        /** Retourne true si le mc_access_token a expiré (marge de 5 minutes). */
        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAtMs - 5 * 60 * 1000L;
        }
    }

    /** Callback pour permettre l'annulation du polling. */
    public interface PollCallback {
        /** Retourne true si le polling doit être annulé. */
        boolean isCancelled();
    }

    // ── API publique ─────────────────────────────────────────────────────────

    /**
     * Étape 1 : demande un Device Code au serveur Microsoft OAuth.
     * Durée : rapide (appel HTTP unique).
     *
     * @return les infos du code à afficher à l'utilisateur
     * @throws Exception si la configuration Azure est incorrecte ou si le réseau est indisponible
     */
    public static DeviceCodeResult requestDeviceCode() throws Exception {
        if (clientId.isBlank()) {
            throw new Exception(
                "Client ID Azure non configuré !\n" +
                "Ajoutez \"msaClientId\": \"<votre-client-id>\" dans launcher_config.json."
            );
        }
        String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&scope="     + URLEncoder.encode(SCOPE,     StandardCharsets.UTF_8);
        JSONObject resp = httpPost(DEVICE_CODE_URL, body, "application/x-www-form-urlencoded", null);

        // Si Microsoft retourne une erreur, l'afficher clairement plutôt que de planter sur user_code
        if (!resp.has("user_code")) {
            String err  = resp.optString("error", "?");
            String desc = resp.optString("error_description", resp.toString());
            throw new Exception("Microsoft a rejeté la demande (" + err + ") :\n" + desc);
        }

        return new DeviceCodeResult(
            resp.getString("user_code"),
            resp.getString("verification_uri"),
            resp.getString("device_code"),
            resp.optInt("interval",   5),
            resp.optInt("expires_in", 900)
        );
    }

    /**
     * Étape 2 : poll le serveur Microsoft jusqu'à ce que l'utilisateur valide dans son navigateur,
     * puis enchaîne la chaîne XBL → XSTS → Minecraft pour obtenir le profil final.
     *
     * @param dcr      résultat de {@link #requestDeviceCode()}
     * @param callback appelé avant chaque tentative pour vérifier l'annulation (peut être null)
     * @return le profil Minecraft complet
     */
    public static McProfile pollAndAuthenticate(DeviceCodeResult dcr, PollCallback callback) throws Exception {
        long   deadline = System.currentTimeMillis() + dcr.expiresIn * 1000L;
        String body = "client_id="    + URLEncoder.encode(clientId,   StandardCharsets.UTF_8)
                    + "&grant_type=urn:ietf:params:oauth:grant-type:device_code"
                    + "&device_code=" + URLEncoder.encode(dcr.deviceCode, StandardCharsets.UTF_8);

        while (System.currentTimeMillis() < deadline) {
            if (callback != null && callback.isCancelled()) {
                throw new InterruptedException("Authentification annulée.");
            }
            Thread.sleep(dcr.intervalSec * 1000L);

            JSONObject resp;
            try {
                resp = httpPost(TOKEN_URL, body, "application/x-www-form-urlencoded", null);
            } catch (Exception ignored) {
                continue; // erreur réseau temporaire — réessayer
            }

            if (resp.has("access_token")) {
                return buildMcProfile(resp);
            }

            String err = resp.optString("error", "unknown");
            switch (err) {
                case "authorization_pending" -> { /* normal, l'utilisateur n'a pas encore validé */ }
                case "slow_down"             -> Thread.sleep(5_000);
                case "expired_token"         -> throw new Exception("Le code a expiré (15 min). Relancez la connexion.");
                default -> throw new Exception(
                    "Erreur Microsoft OAuth : " + err +
                    (resp.has("error_description") ? "\n" + resp.getString("error_description") : "")
                );
            }
        }
        throw new Exception("Délai expiré. Relancez la connexion.");
    }

    /**
     * Reconnexion <b>silencieuse</b> : échange le {@code refresh_token} contre
     * un nouveau profil complet (MS token → XBL → XSTS → MC).
     * Utilisé à chaque lancement si le mc_access_token est expiré.
     *
     * @param refreshToken le refresh_token stocké lors de la dernière connexion complète
     * @return un profil Minecraft frais
     */
    public static McProfile refreshProfile(String refreshToken) throws Exception {
        String body = "client_id="     + URLEncoder.encode(clientId,    StandardCharsets.UTF_8)
                    + "&grant_type=refresh_token"
                    + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);
        JSONObject resp = httpPost(TOKEN_URL, body, "application/x-www-form-urlencoded", null);
        if (!resp.has("access_token")) {
            String err = resp.optString("error", "?");
            throw new Exception("Refresh token invalide (" + err + "). Reconnectez-vous.");
        }
        return buildMcProfile(resp);
    }

    // ── Chaîne interne MS → XBL → XSTS → MC ─────────────────────────────────

    private static McProfile buildMcProfile(JSONObject msTokenResp) throws Exception {
        String msToken      = msTokenResp.getString("access_token");
        String refreshToken = msTokenResp.optString("refresh_token", "");
        // Note : expires_in du token MS (~1h) est ignoré ; c'est mcExpires (token MC) qui pilote

        // ── Xbox Live ────────────────────────────────────────────────────────
        JSONObject xblBody = new JSONObject()
            .put("Properties", new JSONObject()
                .put("AuthMethod", "RPS")
                .put("SiteName",   "user.auth.xboxlive.com")
                .put("RpsTicket",  "d=" + msToken))
            .put("RelyingParty", "http://auth.xboxlive.com")
            .put("TokenType",    "JWT");

        JSONObject xblResp  = httpPostJson(XBL_URL, xblBody.toString());
        String     xblToken = xblResp.getString("Token");
        String     uhs      = xblResp.getJSONObject("DisplayClaims")
                                     .getJSONArray("xui")
                                     .getJSONObject(0)
                                     .getString("uhs");

        // ── XSTS ─────────────────────────────────────────────────────────────
        JSONObject xstsBody = new JSONObject()
            .put("Properties", new JSONObject()
                .put("SandboxId",   "RETAIL")
                .put("UserTokens",  new JSONArray().put(xblToken)))
            .put("RelyingParty", "rp://api.minecraftservices.com/")
            .put("TokenType",    "JWT");

        JSONObject xstsResp = httpPostJson(XSTS_URL, xstsBody.toString());
        // Vérifier les codes d'erreur XSTS connus
        if (xstsResp.has("XErr")) {
            long xerr = xstsResp.optLong("XErr", 0);
            if (xerr == 2148916233L)
                throw new Exception("Ce compte Microsoft n'a pas de compte Xbox associé.\nConnectez-vous d'abord sur xbox.com.");
            if (xerr == 2148916238L)
                throw new Exception("Ce compte est un compte enfant.\nAjoutez-le à un groupe familial sur account.microsoft.com.");
            throw new Exception("Erreur XSTS (XErr=" + xerr + ").");
        }
        if (!xstsResp.has("Token")) {
            throw new Exception("XSTS n'a pas retourné de token.\nRéponse : " + xstsResp.toString());
        }
        String xstsToken = xstsResp.getString("Token");

        // ── Minecraft Services ───────────────────────────────────────────────
        JSONObject mcAuthBody = new JSONObject()
            .put("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        JSONObject mcResp = httpPostJson(MC_AUTH_URL, mcAuthBody.toString());
        if (!mcResp.has("access_token")) {
            throw new Exception(
                "Minecraft Services a refusé la connexion.\n" +
                "Réponse : " + mcResp.toString()
            );
        }
        String mcToken   = mcResp.getString("access_token");
        int    mcExpires = mcResp.optInt("expires_in", 86_400);

        // ── Profil joueur ────────────────────────────────────────────────────
        JSONObject profile = httpGetJson(MC_PROFILE_URL, mcToken);

        // Vérification : ce compte possède-t-il Minecraft ?
        if (!profile.has("name")) {
            throw new Exception(
                "Ce compte Microsoft ne possède pas Minecraft Java Edition.\n" +
                "Achetez le jeu sur minecraft.net."
            );
        }

        return new McProfile(
            profile.getString("name"),
            profile.getString("id"),   // UUID sans tirets
            mcToken,
            refreshToken,
            System.currentTimeMillis() + mcExpires * 1000L
        );
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private static JSONObject httpPost(String url, String body, String contentType, String bearer) throws Exception {
        HttpURLConnection conn = openConn(url, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", contentType);
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readJson(conn);
    }

    private static JSONObject httpPostJson(String url, String json) throws Exception {
        HttpURLConnection conn = openConn(url, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return readJson(conn);
    }

    private static JSONObject httpGetJson(String url, String bearer) throws Exception {
        HttpURLConnection conn = openConn(url, "GET");
        conn.setRequestProperty("Authorization", "Bearer " + bearer);
        return readJson(conn);
    }

    private static HttpURLConnection openConn(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private static JSONObject readJson(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) throw new Exception("Réponse vide du serveur (HTTP " + code + ").");
        String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            throw new Exception("Réponse non-JSON (HTTP " + code + "): "
                + raw.substring(0, Math.min(200, raw.length())));
        }
    }
}
