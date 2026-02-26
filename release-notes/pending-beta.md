## 🧪 Notes beta

- Nouveautés launcher :
	- Profils de performances en français (Performance, Qualité, PC modeste, Personnalisé)
	- Canal d'update affiché en français (Stable / Bêta)
	- Sauvegarde/restauration joueur clarifiées (messages de succès/erreur explicites)
	- Connexion Microsoft renforcée (feedback visuel immédiat en cas d'erreur, vérification `msaClientId`)

- Améliorations techniques :
	- Canal par défaut forcé sur `stable` pour les nouvelles configs
	- Script local EXE (`build-local-exe.ps1` + `.cmd`) fiabilisé (Launch4j auto-détection, logs détaillés, gestion verrouillage EXE)
	- Résolution des problèmes d'encodage console/log du build local

- Correctifs de lancement :
	- Autorisation du host `piston-data.mojang.com` pour le téléchargement client Minecraft
	- Nettoyage des artefacts client invalides (`*-extra.jar`) côté `libraries/net/minecraft/client`
	- Classpath Fabric corrigé : game JAR explicitement ajouté + cache classpath invalidé de façon fiable
	- Gestion des conflits bibliothèques (`gson`) améliorée pour éviter certains crashes d'init mods
	- Purge préventive de `slf4j-simple*.jar` injecté localement (évite les boucles de logs fatales)

## ⚠️ À tester

- Démarrage complet offline + online (Device Code Microsoft)
- Compatibilité update Stable/Bêta et changement de canal en runtime
- Premier lancement après purge cache/classpath sur une install existante
- Build local EXE depuis script Windows (`build-local-exe.cmd`)
