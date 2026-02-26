# Zokkymon Launcher — Branche `beta`

Launcher Java/Swing pour Minecraft Fabric, avec mise à jour automatique du modpack, authentification Microsoft, interface thémable clair/sombre, vérification SHA-256 stricte des artefacts critiques, et sécurité renforcée.

> Cette branche est la branche de développement actif.  
> Les releases stables sont publiées sur `main`.

---

## Fonctionnalités

 - Authentification Microsoft (Device Code Flow → Xbox → XSTS → Minecraft)
 - Vérification et téléchargement automatique du modpack (SHA-256)
 - Vérification SHA-256 stricte sur les artefacts critiques (JRE ZIP, Fabric Loader) avant utilisation
 - Progression unifiée 0→100 % : téléchargement (0–60 %) puis extraction (60–100 %)
 - Mise à jour automatique du launcher via `info.json` hébergé sur GitHub
 - Interface Swing entièrement peinte à la main (pas de look-and-feel natif), palette clair/sombre
 - Stockage chiffré des tokens MSA **et** du token modpack (AES-256-GCM, clé machine-locale PKCS12)
 - Configuration par fichier JSON externe (non embarqué dans le JAR)
 - **Splash screen Minecraft personnalisé** aux couleurs Zokkymon (mod Fabric injecté automatiquement)
 - **Résolution de fenêtre Minecraft configurable** depuis les paramètres du launcher
 - **Cache de classpath** : le classpath Fabric est calculé une fois puis mis en cache (revalidé si les librairies changent)
 - **Injection automatique de mods** : les JARs placés dans `mods/` du launcher sont copiés dans le modpack avant chaque lancement (optimisé, injection incrémentale)
 - **Fenêtre de logs avancée** : auto-scroll permanent, export complet/ciblé en `.txt`, ouverture rapide du dossier logs
 - **Logs persistants** : création automatique de `~/.zokkymon/logs` au premier lancement, journal global + journal de session
 - **Hygiène des logs** : rotation du log global, rétention automatique et nettoyage manuel depuis l'UI
 - Mode strict/relax pour la vérification des checksums (configurable)
 - Sécurité réseau : téléchargements uniquement via HTTPS, liste blanche des hosts, extraction ZIP sécurisée (anti Zip-Slip)
 - Code maintenable et lisible, avec helpers pour la vérification et le téléchargement

---

## Stack technique

| Composant | Version |
|---|---|
| Java | 21 |
| Maven | 3.8+ |
| FlatLaf | 3.5.4 |
| Fabric Loader | 0.16.5+ |
| Module splash-mod | Gradle + Fabric Loom 1.6.12 |
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
│   ├── ConfigManager.java      # Lecture/écriture config JSON + profil MSA
│   ├── MicrosoftAuth.java      # Flux d'auth Microsoft Device Code
│   └── SecureStorage.java      # Chiffrement AES-256-GCM des tokens
├── src/main/resources/
│   └── launcher_config.json    # Config embarquée dans le JAR (sans secrets MSA)
├── splash-mod/                 # Module Gradle — splash screen Fabric
│   ├── src/main/java/com/zokkymon/splash/
│   │   ├── ZokkySplashMod.java       # Entrypoint Fabric
│   │   └── mixin/SplashOverlayMixin.java  # Mixin remplace le rendu du splash
│   ├── src/main/resources/
│   │   ├── fabric.mod.json
│   │   └── zokkymon_splash.mixins.json
│   ├── build.gradle
│   └── gradle.properties
├── mods/                       # JARs injectés automatiquement dans le modpack
│   └── zokkymon-splash-x.x.x.jar
├── config/
│   ├── launcher_config.json    # Config locale (ignorée par git, contient les secrets)
│   └── version.json.example    # Format du fichier version du modpack
├── info.json                   # Version + URL + SHA256 du dernier EXE publié
├── .github/workflows/
│   ├── beta-auto-release.yml   # Build EXE + pre-release beta + update info.json (beta)
│   ├── promote-stable.yml      # Promotion stable + build EXE + update info.json (main)
│   └── update-info.yml         # Fallback: met à jour info.json après release publiée manuellement
├── launch4j.xml                # Config Launch4j pour la génération de l'EXE
└── pom.xml
```

---

## Compilation

### Launcher principal

```bash
mvn clean package
```

Produit `target/ZokkymonLauncher.jar` (fat JAR via maven-shade).  
Pour générer l'EXE, ouvrir `launch4j.xml` dans Launch4j après la compilation.

### Module splash-mod

```powershell
cd splash-mod
.\gradlew.bat build
# Copier le JAR généré dans mods/
Copy-Item .\build\libs\zokkymon-splash-*.jar .\..\mods\
```

Le JAR dans `mods/` est automatiquement injecté dans le dossier `mods/` du modpack avant chaque lancement.

---

## Configuration locale

Copier `config/launcher_config.json` depuis la config embarquée et remplir les champs sensibles :

```json
{
  "msaClientId":       "<Azure App Registration Client ID>",
  "modpackInfoUrl":    "<URL du info.json du modpack>",
  "launcherInfoUrl":   "<URL du info.json du launcher>",
  "installPath":       "",
  "ram":               "4 Go",
  "windowWidth":       1280,
  "windowHeight":      720
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
- Vérification SHA-256 stricte sur les artefacts critiques (JRE ZIP, Fabric Loader) : le launcher bloque toute utilisation si le hash ne correspond pas à celui attendu.
- Sécurité réseau : téléchargements uniquement via HTTPS, liste blanche des hosts, extraction ZIP sécurisée (anti Zip-Slip).

---

## Optimisations de lancement

- **Cache classpath** : `Launcher.buildClasspath()` sauvegarde le classpath calculé dans `.classpath.cache` au sein du répertoire du modpack. Il est revalidé automatiquement si `libraries/` ou le JAR Fabric Loader change.
- **Téléchargements parallèles** : les dépendances Minecraft, Fabric et Fabric Loader sont vérifiées en parallèle via `CompletableFuture`.
- **Nettoyage ciblé** : `.fabric/remappedJars/` est supprimé avant chaque lancement pour éviter les crashes Mixin sur fichiers partiels.
- **Injection de mods optimisée** : injection incrémentale, évite les scans inutiles, plus rapide que CurseForge.
- **I/O logs optimisées** : écriture asynchrone en lot (batch) pour limiter l'impact disque pendant le lancement/jeu.

---

## Journalisation (logs)

- Dossier créé automatiquement au premier lancement : `~/.zokkymon/logs`.
- Fichiers générés :
  - `launcher-full.txt` (historique global)
  - `launcher-session-YYYYMMDD_HHMMSS.txt` (session courante)
- Export depuis la fenêtre des logs :
  - `Exporter complet` (tout le journal affiché)
  - `Exporter ciblé` (uniquement la sélection)
- Actions rapides :
  - `Ouvrir dossier logs`
  - `Nettoyer logs` (suppression des anciens fichiers selon la politique de rétention)
- Politique de conservation par défaut :
  - rétention temporelle : 30 jours
  - limitation du nombre de logs de session : 120

---

## Système de mise à jour du launcher

Le process principal est désormais :

1. `beta-auto-release.yml` (sur push `beta`) :
  - build JAR + EXE,
  - crée une pre-release GitHub,
  - met à jour `info.json` directement sur `beta` (version, URL, SHA-256, changelog).
2. `promote-stable.yml` (manuel) :
  - fusion optionnelle `beta` → `main` (en conservant `README.md` de `main`),
  - build JAR + EXE,
  - crée la release stable,
  - met à jour `info.json` directement sur `main`.

Le workflow `update-info.yml` reste disponible comme **fallback** si une release est publiée manuellement hors des workflows ci-dessus.

Au démarrage, le launcher compare `launcherVersion` (config) avec `info.json` distant et propose la mise à jour si nécessaire.

---

## Automatisation des releases (sans éditer de YAML)

### 1) Beta automatique à chaque push sur `beta`

- Workflow : `.github/workflows/beta-auto-release.yml`
- Déclenchement : automatique sur push `beta` (hors commits du bot GitHub Actions)
- Effet : build JAR + build EXE + création d'une pre-release GitHub + update directe de `info.json` sur `beta`

Avant de push sur `beta`, complète si besoin :

- `release-notes/pending-beta.md`

Le contenu de cette note est repris dans la release, puis dans `info.json.changelog`.

### 2) Promotion stable manuelle

- Workflow : `.github/workflows/promote-stable.yml`
- Déclenchement : GitHub → **Actions** → **Promote Stable Release** → **Run workflow**
- Inputs :
  - `stable_version` (ex: `0.4.0`)
  - `merge_beta` (`true` pour fusionner `beta` vers `main` en gardant le `README.md` de `main`)

Avant de lancer la promotion stable, complète si besoin :

- `release-notes/pending-main.md`

Comme pour la beta, la note devient le changelog final dans la release et `info.json`.

### 3) Fallback (release manuelle)

Si tu publies une release manuellement via GitHub (sans passer par les workflows d'automatisation),
`update-info.yml` mettra `info.json` à jour à partir de l'asset `ZokkymonLauncher.exe` et du body de release.

---

## Branches

| Branche | Rôle |
|---|---|
| `main` | Releases stables |
| `beta` | Développement — pre-releases |
