## 🚀 Notes de version stable 0.4.0

Cette version consolide les évolutions livrées en beta depuis la 0.3.7.

- Nouveautés :
  - Connexion Microsoft renforcée avec normalisation du code Device Flow (affichage/copie plus fiable selon les polices système).
  - Fiabilisation de la récupération du `msaClientId` via fallback (config embarquée, variable d'environnement, config externe).
  - Intégration CI de la signature automatique des EXE (beta + stable) avec vérification adaptée aux certificats auto-signés.

- Améliorations :
  - Vérification VirusTotal intégrée dans les pipelines avec génération de rapports archivés dans `security-reports/`.
  - Pipeline beta ajusté pour éviter les publications EXE automatiques à chaque push (déclenchement manuel).
  - Durcissement des workflows de release et meilleure traçabilité sécurité des artefacts.

- Correctifs :
  - Fallback sans token ajouté sur les endpoints modpack en cas de 401/403 pour éviter les blocages d'accès.
  - Parsing des changelogs amélioré (formats multiples pris en charge).
  - Mise à jour modpack : préservation automatique des paramètres joueur (backup/restore) lors des changements de version.
  - Correction d'un affichage de caractère non supporté dans le bouton de copie du code Microsoft.

## ✅ Recommandé

- Recommandé à tous les joueurs actuellement en 0.3.7.
- Vérifier que le compte Microsoft est bien lié à un profil Xbox + licence Minecraft Java.
