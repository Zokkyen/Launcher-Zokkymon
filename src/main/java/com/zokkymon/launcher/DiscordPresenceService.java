package com.zokkymon.launcher;

import club.minnced.discord.rpc.DiscordEventHandlers;
import club.minnced.discord.rpc.DiscordRPC;
import club.minnced.discord.rpc.DiscordRichPresence;

final class DiscordPresenceService {

    private static final Object LOCK = new Object();
    private static final long CALLBACK_SLEEP_MS = 2_000L;
    private static final DiscordRPC RPC = DiscordRPC.INSTANCE;

    private static volatile boolean initialized = false;
    private static volatile boolean shutdownHookRegistered = false;
    private static volatile Thread callbackThread;
    private static volatile String largeImageKey = "";
    private static volatile String largeImageText = "Zokkymon";
    private static volatile String smallImageKey = "";
    private static volatile String smallImageText = "";
    private static volatile String lastDetails = "";
    private static volatile String lastState = "";
    private static volatile long lastStartTimestamp = Long.MIN_VALUE;

    private DiscordPresenceService() {}

    public static void initialize(ConfigManager config) {
        if (config == null) return;

        String applicationId = config.getDiscordApplicationId();
        if (applicationId == null || applicationId.isBlank()) return;

        synchronized (LOCK) {
            if (initialized) return;

            largeImageKey = safe(config.getDiscordLargeImageKey());
            largeImageText = safe(config.getDiscordLargeImageText());
            smallImageKey = safe(config.getDiscordSmallImageKey());
            smallImageText = safe(config.getDiscordSmallImageText());

            try {
                DiscordEventHandlers handlers = new DiscordEventHandlers();
                RPC.Discord_Initialize(applicationId.strip(), handlers, false, null);
                initialized = true;
                startCallbackThread();
                registerShutdownHook();
            } catch (Throwable error) {
                initialized = false;
                System.err.println("[WARN] Discord Rich Presence indisponible : " + error.getMessage());
            }
        }
    }

    public static void setLauncherPresence(String modpackName, String modpackVersion, String minecraftVersion,
                                           String launcherVersion, String serverState, boolean localAvailable) {
        updatePresence(
            "Dans le launcher",
            buildLauncherState(modpackName, modpackVersion, minecraftVersion, launcherVersion, serverState, localAvailable),
            0L
        );
    }

    public static void setPreparingLaunch(String modpackName, String modpackVersion, String minecraftVersion,
                                          String playerName, String serverState) {
        updatePresence(
            "Lancement de " + buildPackLabel(modpackName, modpackVersion, minecraftVersion),
            buildSessionState(playerName, serverState),
            0L
        );
    }

    public static void setPlaying(String modpackName, String modpackVersion, String minecraftVersion,
                                  String playerName, String serverState) {
        updatePresence(
            "En jeu sur " + buildPackLabel(modpackName, modpackVersion, minecraftVersion),
            buildSessionState(playerName, serverState),
            System.currentTimeMillis() / 1000L
        );
    }

    public static void shutdown() {
        Thread workerToStop;
        synchronized (LOCK) {
            if (!initialized) return;
            initialized = false;
            workerToStop = callbackThread;
            callbackThread = null;

            try {
                RPC.Discord_ClearPresence();
            } catch (Throwable ignored) {}

            try {
                RPC.Discord_Shutdown();
            } catch (Throwable ignored) {}

            lastDetails = "";
            lastState = "";
            lastStartTimestamp = Long.MIN_VALUE;
        }

        if (workerToStop != null) {
            workerToStop.interrupt();
        }
    }

    private static void updatePresence(String details, String state, long startTimestamp) {
        synchronized (LOCK) {
            if (!initialized) return;

            String safeDetails = trimForDiscord(safe(details));
            String safeState = trimForDiscord(safe(state));
            if (safeDetails.equals(lastDetails)
                && safeState.equals(lastState)
                && startTimestamp == lastStartTimestamp) {
                return;
            }

            DiscordRichPresence presence = new DiscordRichPresence();
            presence.details = safeDetails;
            presence.state = safeState;
            if (!largeImageKey.isBlank()) {
                presence.largeImageKey = largeImageKey;
                if (!largeImageText.isBlank()) {
                    presence.largeImageText = trimForDiscord(largeImageText);
                }
            }
            if (!smallImageKey.isBlank()) {
                presence.smallImageKey = smallImageKey;
                if (!smallImageText.isBlank()) {
                    presence.smallImageText = trimForDiscord(smallImageText);
                }
            }
            if (startTimestamp > 0L) {
                presence.startTimestamp = startTimestamp;
            }

            try {
                RPC.Discord_UpdatePresence(presence);
                lastDetails = safeDetails;
                lastState = safeState;
                lastStartTimestamp = startTimestamp;
            } catch (Throwable error) {
                System.err.println("[WARN] Échec Discord Rich Presence : " + error.getMessage());
            }
        }
    }

    private static String buildLauncherState(String modpackName, String modpackVersion, String minecraftVersion,
                                             String launcherVersion, String serverState, boolean localAvailable) {
        String localState = localAvailable
            ? buildPackLabel(modpackName, modpackVersion, minecraftVersion)
            : "Installation requise";
        String launcher = safe(launcherVersion);
        String server = safe(serverState);
        if (!launcher.isBlank()) {
            localState = "Launcher v" + launcher + " • " + localState;
        }
        if (server.isBlank()) return localState;
        return localState + " • " + server;
    }

    private static String buildPackLabel(String modpackName, String modpackVersion, String minecraftVersion) {
        String modpack = safeName(modpackName, "Zokkymon");
        String packVersion = safe(modpackVersion);
        if (!packVersion.isBlank()) return modpack + " v" + packVersion;

        String mcVersion = safe(minecraftVersion);
        if (!mcVersion.isBlank()) return modpack + " • MC " + mcVersion;
        return modpack;
    }

    private static String buildSessionState(String playerName, String serverState) {
        String player = safe(playerName);
        String server = safe(serverState);
        if (player.isBlank()) return server.isBlank() ? "Session locale" : server;
        if (server.isBlank()) return player;
        return player + " • " + server;
    }

    private static String buildVersionState(String modpackName, String minecraftVersion) {
        String modpack = safeName(modpackName, "Zokkymon");
        String mcVersion = safe(minecraftVersion);
        if (mcVersion.isBlank()) return modpack;
        return modpack + " • Minecraft " + mcVersion;
    }

    private static String safeName(String value, String fallback) {
        String safeValue = safe(value);
        return safeValue.isBlank() ? fallback : safeValue;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String trimForDiscord(String value) {
        if (value.length() <= 128) return value;
        return value.substring(0, 125) + "...";
    }

    private static void startCallbackThread() {
        if (callbackThread != null && callbackThread.isAlive()) return;

        Thread worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    synchronized (LOCK) {
                        if (!initialized) return;
                        RPC.Discord_RunCallbacks();
                    }
                    Thread.sleep(CALLBACK_SLEEP_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable ignored) {
                    // Non bloquant : on retentera au cycle suivant tant que le launcher vit.
                }
            }
        }, "zokkymon-discord-rpc");
        worker.setDaemon(true);
        callbackThread = worker;
        worker.start();
    }

    private static void registerShutdownHook() {
        if (shutdownHookRegistered) return;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(DiscordPresenceService::shutdown, "zokkymon-discord-rpc-shutdown"));
            shutdownHookRegistered = true;
        } catch (IllegalStateException ignored) {
            shutdownHookRegistered = true;
        }
    }
}