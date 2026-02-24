package com.zokkymon.launcher;

import java.io.File;
import java.nio.file.Files;

/**
 * Service de mise à jour discret - Remplace l'EXE du launcher et le relance.
 * À exécuter après fermeture du launcher principal.
 */
public class UpdaterService {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.exit(1);
        }

        String newExePath = args[0];      // Chemin du nouvel EXE
        String targetExePath = args[1];   // Chemin du launcher à remplacer
        
        try {
            File newFile = new File(newExePath);
            File targetFile = new File(targetExePath);

            // Attendre un peu pour que le launcher soit bien fermé
            Thread.sleep(2000);

            // Attendre que le fichier cible soit libéré
            for (int i = 0; i < 30; i++) {
                if (targetFile.delete()) {
                    break; // Fichier supprimé avec succès
                }
                Thread.sleep(500);
            }

            // Remplacer par le nouveau
            if (newFile.exists()) {
                Files.move(newFile.toPath(), targetFile.toPath());
            }

            // Détecter si on exécute depuis un EXE (Launch4j) ou un JAR
            String launchCmd;
            String classpath = System.getProperty("java.class.path");

            if (classpath.endsWith(".exe")) {
                // Lancé depuis un EXE (Launch4j)
                launchCmd = targetExePath;
            } else {
                // Lancé depuis un JAR
                launchCmd = "java -jar " + targetExePath;
            }

            // Relancer le nouveau launcher
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "start", "", launchCmd);
            pb.start();

            // Fin silencieuse
            System.exit(0);

        } catch (Exception e) {
            // Silencieux en cas d'erreur aussi
            System.exit(1);
        }
    }
}
