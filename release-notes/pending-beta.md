## 🧪 Notes beta 0.4.1

- Nouveautés launcher :
	- Prise en charge de Fabric Loader 0.18.4.
	- Nouvelle fenêtre Catalogue des mods depuis la carte Mods installés.
	- Recherche en direct des mods.
	- Tri des mods par nom, version, taille et date.
	- Filtre de compatibilité Tous / OK / A vérifier.
	- Export du catalogue des mods en CSV et JSON.

- Améliorations offline/local :
	- Lancement local autorisé si le serveur est hors ligne et qu'un modpack local est présent.
	- Le bouton principal bascule automatiquement sur JOUER EN LOCAL dans ce cas.
	- La carte serveur affiche maintenant Mod local disponible ou Mod local indisponible.

- Correctifs techniques :
	- Vérification SHA Fabric assouplie en mode local si le checksum distant est indisponible.
	- Correction du problème de signature de méthode autour de la vérification SHA des artefacts.
	- Résolution plus robuste du dossier modpack local courant.

## ⚠️ À tester

- Vérifier le parcours complet serveur hors ligne + modpack local présent.
- Vérifier le comportement serveur hors ligne + aucun modpack local.
- Vérifier le catalogue mods sur un pack volumineux (recherche, tri, filtre, export).
- Vérifier la compatibilité du pack complet avec Fabric 0.18.4.
