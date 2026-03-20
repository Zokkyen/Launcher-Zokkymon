# Zokkymon Launcher - Branche beta

Launcher Java/Swing pour Minecraft Fabric avec mise à jour automatique du modpack,
authentification Microsoft et publication continue des pre-releases.

Cette branche beta contient le développement actif.
Les releases stables sont publiées sur main.

---

## Fonctionnalités

- Authentification Microsoft (Device Code Flow -> Xbox -> XSTS -> Minecraft)
- Vérification + téléchargement automatique du modpack avec contrôle SHA-256
- Mise à jour automatique du launcher via info.json distant
- Installation Java/Fabric et lancement Minecraft via process dédié
- UI Swing personnalisée + thèmes clair/sombre
- Stockage chiffré des tokens MSA (AES-256-GCM)

---

## Stack technique

| Composant | Version cible |
|---|---|
| Java | 21 |
| Maven | 3.8+ |
| FlatLaf | 3.5.4 |
| Plateforme cible | Windows (EXE via Launch4j) |

---

## Structure principale

```text
ZokkymonLauncher/
|- src/main/java/com/zokkymon/launcher/
|  |- Main.java
|  |- LauncherGUI.java
|  |- Launcher.java
|  |- Updater.java
|  |- ConfigManager.java
|  |- MicrosoftAuth.java
|  |- SecureStorage.java
|- src/main/resources/
|  |- launcher_config.json
|- config/
|  |- launcher_config.json
|- release-notes/
|  |- pending-beta.md
|  |- pending-main.md
|- security-reports/
|  |- virustotal-beta-latest.md
|- .github/workflows/
|  |- beta-auto-release.yml
|  |- promote-stable.yml
|  |- update-info.yml
|- info.json
|- pom.xml
```

---

## Build local

```bash
mvn clean package
```

Produit principal : target/ZokkymonLauncher.jar.

Pour l'EXE local : scripts build-local-exe.ps1 / build-local-exe.cmd.

---

## Configuration locale

Le fichier config/launcher_config.json contient les paramètres machine et les
valeurs sensibles (client id MSA, token modpack, urls), et ne doit pas être
commit.

---

## Sécurité et vérification binaire

- Les tokens MSA sont stockés chiffrés sur disque via SecureStorage.
- Les artefacts modpack sont vérifiés par SHA-256.
- En beta, l'EXE généré est scanné automatiquement via VirusTotal pendant le workflow.
- Les rapports sont publiés dans security-reports/
  (notamment virustotal-beta-latest.md).

Avant diffusion d'une build, vérifier le rapport VirusTotal et confirmer
que les indicateurs malveillants/suspects ne remontent pas d'anomalie.

---

## CI/CD (beta)

Workflow principal : .github/workflows/beta-auto-release.yml

Pipeline :
1. calcule la prochaine version beta
2. compile le jar et génère l'EXE
3. publie la pre-release GitHub
4. met à jour info.json
5. génère/publie le rapport VirusTotal

---

## Branches

| Branche | Role |
|---|---|
| main | Releases stables |
| beta | Développement + pre-releases |
