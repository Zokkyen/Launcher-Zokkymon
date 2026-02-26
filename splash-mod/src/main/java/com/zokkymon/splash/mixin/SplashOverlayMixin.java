package com.zokkymon.splash.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SplashOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.stream.Collectors;

@Mixin(SplashOverlay.class)
public class SplashOverlayMixin {

    // Le champ progress est lerp-é par le rendu vanilla avant que notre inject s'exécute.
    @Shadow
    private float progress;

    // Liste de mods chargée une seule fois (triée, sans mods internes)
    private static List<String> modNames = null;

    @Inject(method = "render", at = @At("RETURN"))
    private void zokkymonSplashRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return;

        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        int cx = sw / 2;
        int cy = sh / 2;

        // ── Fond plein qui écrase le rendu vanilla ───────────────────────────
        context.fill(0, 0, sw, sh, 0xFF0A0A10);
        // Léger dégradé vertical pour donner de la profondeur
        context.fillGradient(0, 0,  sw, cy, 0xFF12112A, 0xFF0A0A10);
        context.fillGradient(0, cy, sw, sh, 0xFF0A0A10, 0xFF050508);

        // ── Ligne décorative haute (accent violet) ───────────────────────────
        context.fill(cx - 120, 4, cx + 120, 6, 0xFF7B2FBE);

        // ── Titre ZOKKYMON (x3) ──────────────────────────────────────────────
        String title = "ZOKKYMON";
        int titleScale = 3;
        context.getMatrices().push();
        context.getMatrices().translate(cx, cy - 70, 0);
        context.getMatrices().scale(titleScale, titleScale, 1f);
        int titleW = mc.textRenderer.getWidth(title);
        context.drawText(mc.textRenderer, title, -titleW / 2, 0, 0xFFE8D0FF, true);
        context.getMatrices().pop();

        // ── Barre de progression ─────────────────────────────────────────────
        int barW  = Math.min(480, sw - 80);
        int barH  = 8;
        int barX  = cx - barW / 2;
        int barY  = cy + 10;

        // Cadre de la barre
        context.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF2A1A40);
        // Fond de la barre
        context.fill(barX, barY, barX + barW, barY + barH, 0xFF100D1A);
        // Remplissage dégradé (violet → lilas)
        int fill = (int)(barW * Math.min(1f, this.progress));
        if (fill > 0) {
            context.fillGradient(barX, barY, barX + fill, barY + barH,
                0xFF6A1EB5, 0xFFAA60EE);
        }
        // Reflet blanc léger sur le dessus de la barre
        context.fill(barX, barY, barX + fill, barY + 2, 0x30FFFFFF);

        // ── Pourcentage ──────────────────────────────────────────────────────
        int pct = (int)(this.progress * 100);
        String pctText = pct + "%";
        int pctX = cx - mc.textRenderer.getWidth(pctText) / 2;
        context.drawText(mc.textRenderer, pctText, pctX, barY + barH + 8, 0xFFCCBBFF, false);

        // ── Nom du mod "en cours" (basé sur la progression) ─────────────────
        if (modNames == null) {
            modNames = FabricLoader.getInstance().getAllMods().stream()
                .map(m -> m.getMetadata().getId())
                .filter(id -> !id.equals("fabricloader")
                           && !id.equals("minecraft")
                           && !id.equals("java")
                           && !id.equals("fabric"))
                .sorted()
                .collect(Collectors.toList());
        }

        int totalMods = modNames.size();
        if (totalMods > 0) {
            // Afficher le mod correspondant à la progression actuelle
            int idx = Math.min((int)(this.progress * totalMods), totalMods - 1);
            String currentMod = modNames.get(idx);

            String loadText = "Chargement : " + currentMod + "  (" + (idx + 1) + "/" + totalMods + ")";
            int ltX = cx - mc.textRenderer.getWidth(loadText) / 2;
            context.drawText(mc.textRenderer, loadText, ltX, barY - 18, 0xFF997ACC, false);
        }

        // ── Nombre total de mods ─────────────────────────────────────────────
        String modCountText = totalMods + " mods actifs";
        int mcX = cx - mc.textRenderer.getWidth(modCountText) / 2;
        context.drawText(mc.textRenderer, modCountText, mcX, barY + barH + 24, 0xFF554477, false);

        // ── Ligne décorative basse ───────────────────────────────────────────
        context.fill(cx - 80, sh - 6, cx + 80, sh - 4, 0xFF3A1A5E);
    }
}
