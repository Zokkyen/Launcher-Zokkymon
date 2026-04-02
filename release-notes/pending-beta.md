## 🧪 Notes beta 0.4.2

- Nouveautés launcher :
	- Prise en charge de Fabric Loader 0.18.4.
	- Nouvelle fenêtre Catalogue des mods depuis la carte Mods installés.
	- Recherche en direct des mods.
	- Tri des mods par nom, version, taille et date.
	- Filtre de compatibilité Tous / OK / A vérifier.
	- Nouvelle colonne Environnement dans le catalogue (Client / Serveur / Client/Serveur).
	- Filtre Environnement: Tous / Client / Serveur / Client/Serveur.
	- Export du catalogue des mods en CSV et JSON (avec le champ environment).

- Améliorations offline/local :
	- Lancement local autorisé si le serveur est hors ligne et qu'un modpack local est présent.
	- Le bouton principal bascule automatiquement sur JOUER EN LOCAL dans ce cas.
	- La carte serveur affiche maintenant Mod local disponible ou Mod local indisponible.

- Correctifs techniques :
	- Correction du preset RAM/JVM: les réglages manuels basculent automatiquement vers le profil custom pour éviter l'écrasement des valeurs choisies.
	- Vérification SHA Fabric assouplie en mode local si le checksum distant est indisponible.
	- Correction du problème de signature de méthode autour de la vérification SHA des artefacts.
	- Résolution plus robuste du dossier modpack local courant.
	- Correction d'un argument JVM FabricMcEmu mal formé.
	- Fiabilisation de l'injection des mods launcher: réinjection automatique si un mod injecté manque malgré le cache.

- Sécurité :
	- Les tokens Microsoft ne sont plus écrits en clair en cas d'échec du chiffrement local.
	- Durcissement des connexions réseau de l'updater: HTTPS requis (sauf loopback local).

## ⚠️ À tester

- Vérifier le parcours complet serveur hors ligne + modpack local présent.
- Vérifier le comportement serveur hors ligne + aucun modpack local.
- Vérifier le catalogue mods sur un pack volumineux (recherche, tri, filtres compatibilité/environnement, export CSV/JSON).
- Vérifier les mods sans champ environment dans fabric.mod.json (attendu: Client/Serveur).
- Vérifier qu'une suppression manuelle d'un mod injecté est bien réparée au lancement suivant.
- Vérifier la compatibilité du pack complet avec Fabric 0.18.4.
