Placez votre fichier d'icône `zokkymon.ico` ici :

src/main/resources/zokkymon.ico

Le launcher chargera automatiquement cette icône pour l'affichage à gauche du titre et comme icône de la fenêtre.
Format recommandé : .ico multi-tailles (256,128,64,48,32,16) ou PNG 48x48.

Si votre fichier source est un PNG, utilisez les scripts fournis pour générer l'ICO :

Linux/macOS:
```bash
./tools/convert_icon.sh logo.png zokkymon.ico
```

Windows (PowerShell/CMD):
```powershell
tools\convert_icon.bat logo.png zokkymon.ico
```

Placez ensuite `zokkymon.ico` (ou `zokkymon.png`) dans `src/main/resources/` puis rebuild.
