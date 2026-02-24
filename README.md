# Zokkymon Launcher — Branche `beta`

Launcher Java/Swing pour Minecraft Fabric, avec mise à jour automatique du modpack, authentification Microsoft et interface thémable clair/sombre.

> Cette branche est la branche de développement actif.  
> Les releases stables sont publiées sur `main`.

---

## Fonctionnalités

- Authentification Microsoft (Device Code Flow → Xbox → XSTS → Minecraft)
- Vérification et téléchargement automatique du modpack (SHA-256)
- Progression unifiée 0→100 % : téléchargement (0–60 %) puis extraction (60–100 %)
- Mise à jour automatique du launcher via `info.json` hébergé sur GitHub
- Interface Swing entièrement peinte à la main (pas de look-and-feel natif), palette clair/sombre
- Stockage chiffré des tokens MSA **et** du token modpack (AES-256-GCM, clé machine-locale PKCS12)
- Configuration par fichier JSON externe (non embarqué dans le JAR)

---

## Stack technique

| Composant | Version |
|---|---|
| Java | 21 |
| Maven | 3.8+ |
| FlatLaf | 3.5.4 |
| Target platform | Windows (EXE via Launch4j) |

---

## Structure du projet

```
ZokkymonLauncher/
├── src/main/java/com/zokkymon/launcher/
│   ├── Main.java               # Point d'entrée
│   ├── LauncherGUI.java        # UI Swing — fenêtre principale + dialogs
│   ├── Launcher.java           # Lancement du processus Minecraft
│   ├── Updater.java            # Vérification/téléchargement modpack et launcher
│   ├── UpdaterService.java     # Orchestration des mises à jour
│   ├── ConfigManager.java      # Lecture/écriture config JSON + profil MSA
│   ├── MicrosoftAuth.java      # Flux d'auth Microsoft Device Code
│   └── SecureStorage.java      # Chiffrement AES-256-GCM des tokens
├── src/main/resources/
│   └── launcher_config.json    # Config embarquée dans le JAR (sans secrets MSA)
├── config/
│   ├── launcher_config.json    # Config locale (ignorée par git, contient les secrets)
│   └── version.json.example    # Format du fichier version du modpack
├── info.json                   # Version + URL + SHA256 du dernier EXE publié
├── .github/workflows/
│   └── update-info.yml         # Met à jour info.json automatiquement à la release
├── launch4j.xml                # Config Launch4j pour la génération de l'EXE
└── pom.xml
```

---

## Compilation

```bash
mvn clean package
```

Produit `target/ZokkymonLauncher.jar` (fat JAR via maven-shade).  
Pour générer l'EXE, ouvrir `launch4j.xml` dans Launch4j après la compilation.

---

## Configuration locale

Copier `config/launcher_config.json` depuis la config embarquée et remplir les champs sensibles :

```json
{
  "msaClientId":       "<Azure App Registration Client ID>",
  "modpackInfoUrl":    "<URL du info.json du modpack>",
  "launcherInfoUrl":   "<URL du info.json du launcher>",
  "installPath":       "",
  "ram":               "4 Go"
}
```

> `config/launcher_config.json` est dans le `.gitignore`. Ne jamais committer ce fichier.

> Le `modpackToken` est déjà embarqué dans le JAR. Il est automatiquement chiffré avec la clé machine dès le premier lancement (voir section Sécurité).

---

## Sécurité

- Les tokens **MSA** (access + refresh) sont chiffrés sur disque via `SecureStorage` (AES-256-GCM).
- Le **token modpack** suit le même mécanisme : stocké en clair dans le JAR à la distribution, puis chiffré automatiquement sur le poste de l'utilisateur dès la première lecture.
- La clé de chiffrement est dérivée de `SHA-256(username@hostname)` et stockée dans un KeyStore PKCS12 local (`~/.zokkymon/config/.ks`). Elle est propre à chaque machine/profil.
- Le `msaClientId` n'est pas embarqué dans le JAR — il doit être renseigné dans la config locale uniquement.

---

## Système de mise à jour du launcher

À chaque publication d'une release GitHub, le workflow `.github/workflows/update-info.yml` :

1. Télécharge l'EXE attaché à la release
2. Calcule son SHA-256
3. Met à jour `info.json` (version, URL, hash) sur la branche correspondante :
   - pre-release → `beta`
   - release stable → `main`

Au démarrage, le launcher compare `launcherVersion` (config) avec `info.json` distant et propose la mise à jour si nécessaire.

---

## Branches

| Branche | Rôle |
|---|---|
| `main` | Releases stables |
| `beta` | Développement — pre-releases |
