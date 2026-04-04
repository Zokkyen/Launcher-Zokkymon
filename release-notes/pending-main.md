## 🚀 Notes de version stable 0.4.2

Cette version améliore la robustesse du lancement local, enrichit le catalogue de mods et renforce la sécurité du launcher.

- Nouveautés :
  - Prise en charge de Fabric Loader 0.18.4.
  - Nouvelle fenêtre Catalogue des mods depuis la sidebar.
  - Recherche, tri, filtre de compatibilité et filtre d'environnement dans le catalogue.
  - Ajout d'une colonne Environnement (Client / Serveur / Client/Serveur).
  - Export du catalogue des mods en CSV et JSON (avec environment).

- Améliorations :
  - RAM minimale fixée à 4 Go (2 Go retiré des options) et valeur par défaut à 4 Go au premier lancement.
  - Liste des options RAM adaptée dynamiquement à la mémoire système détectée (max recommandé avec marge de sécurité).
  - Lancement local simplifié quand le serveur est indisponible et qu'un modpack local existe.
  - Indication claire de l'état local du modpack dans la carte serveur.
  - Mise à jour dynamique du bouton de jeu vers JOUER EN LOCAL selon l'état réseau.
  - Gestion plus fiable des réglages manuels RAM/JVM (bascule automatique vers profil custom).
  - Réinjection automatique des mods launcher manquants même si le cache est inchangé.
  - Ouverture des fenêtres Paramètres et Console limitée à une seule instance.
  - Console UI plus fluide après les longues sessions grâce à l'agrégation des logs et au nettoyage automatique des anciennes lignes affichées.
  - Canal Bêta temporairement désactivé dans l'interface pour éviter les bascules utilisateur non souhaitées.

- Correctifs et sécurité :
  - Correction d'un conflit de méthode lié à la vérification SHA des artefacts.
  - Gestion plus tolérante de la vérification SHA Fabric en contexte local/offline.
  - Correction d'un argument JVM FabricMcEmu mal formé.
  - Les tokens Microsoft ne sont plus persistés en clair en cas d'échec du chiffrement.
  - Connexions de l'updater durcies (HTTPS requis, sauf loopback local).

## ✅ Recommandé

- Recommandé pour les joueurs qui utilisent déjà un modpack local et veulent un mode hors ligne plus fiable.
- Recommandé pour les administrateurs qui souhaitent auditer rapidement les mods installés (recherche, tri, filtres, export).
