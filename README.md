# Zokkymon Launcher

Launcher Java pour Minecraft avec Cobblemon Academy 2 (MC 1.21.1, Fabric 0.17.3).

## Fonctionnalités

✓ Interface graphique moderne avec progress bar
✓ Vérification de version des mods
✓ Téléchargement automatique des mises à jour
✓ Validation d'intégrité (SHA-256)
✓ Lancement direct de Minecraft avec Fabric
✓ Logs en direct des opérations
✓ Conversion en EXE Windows

## Structure du Projet

```
ZokkymonLauncher/
├── src/main/java/com/zokkymon/launcher/
│   ├── Main.java                 # Point d'entrée
│   ├── LauncherGUI.java          # Interface graphique
│   ├── Launcher.java             # Lancement Minecraft
│   ├── Updater.java              # Gestion des mises à jour
│   └── ConfigManager.java        # Gestion de la configuration
├── config/
│   ├── launcher_config.json      # Configuration du launcher
│   └── version.json.example      # Format de la version serveur
├── mods/                         # Dossier des mods (créé à l'exécution)
├── minecraft/                    # Dossier Minecraft (créé à l'exécution)
├── pom.xml                       # Configuration Maven
├── SETUP.md                      # Guide de configuration
├── generate_hashes.bat           # Script Windows pour hashes SHA-256
└── generate_hashes.sh            # Script Linux pour hashes SHA-256
```

## Compilation

### Prérequis
- Java 11+
- Maven 3.8+

### Compilation
```bash
mvn clean package
```

Cela génère: `target/ZokkymonLauncher.jar`

## Configuration

1. Éditez `config/launcher_config.json`:
```json
{
  "serverUrl": "http://192.168.1.100:80",
  "minecraftVersion": "1.21.1",
  "fabricVersion": "0.17.3",
  "modpackName": "Zokkymon",
  "ram": "4G"
}
```

2. Sur le serveur Ubuntu, organisez vos fichiers:
```
/var/www/html/modpack/
├── version.json
├── mods/
│   ├── cobblemon.jar
│   ├── fabric-api.jar
│   └── ...
```

3. Générez les hashes:
```bash
./generate_hashes.sh  # Linux
# ou
generate_hashes.bat   # Windows
```

## Conversion en EXE

Voir le fichier [SETUP.md](SETUP.md) pour les détails sur la création d'un executé Windows avec Launch4j ou Inno Setup.

## Lancement

**JAR directement:**
```bash
java -jar target/ZokkymonLauncher.jar
```

**EXE (après conversion):**
Double-click sur `ZokkymonLauncher.exe`

## Format du fichier version.json serveur

```json
{
  "version": "1.0.0",
  "minecraftVersion": "1.21.1",
  "fabricVersion": "0.17.3",
  "modpackName": "Zokkymon",
  "files": [
    {
      "name": "cobblemon.jar",
      "path": "mods",
      "hash": "sha256_hash_here"
    }
  ]
}
```

## Troubleshooting

### Le launcher ne trouve pas les mises à jour
- Vérifiez que l'URL serveur est correcte
- Assurez-vous que le serveur est accessible
- Vérifiez que le fichier `version.json` existe sur le serveur

### Les mods ne se téléchargent pas
- Vérifiez les logs pour les détails d'erreur
- Assurez-vous que les fichiers existent sur le serveur
- Vérifiez les permissions du serveur web

### Erreur Java lors du lancement
- Vérifiez que Java 11+ est installé
- Vérifiez que Minecraft et Fabric sont bien placés

## Architecture du Launcher

1. **Main.java** - Point d'entrée, crée les dossiers et lance la GUI
2. **LauncherGUI.java** - Interface Swing, gère l'affichage et les interactions
3. **ConfigManager.java** - Charge/sauvegarde la configuration JSON
4. **Updater.java** - Vérifie les versions et télécharge les fichiers
5. **Launcher.java** - Lance Minecraft avec les bons paramètres JVM

## Notes Importantes

- Les fichiers JAR des mods doivent être dans le dossier `mods/` sur le serveur
- Les hashes SHA-256 sont vérifiés pour l'intégrité dos téléchargement
- Le launcher crée automatiquement les dossiers nécessaires

## Support

Pour plus d'aide, consultez la section "SETUP.md".
