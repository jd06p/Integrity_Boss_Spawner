package com.bossmod.client;

import com.bossmod.network.IntegrityFxPacket;
import com.mojang.blaze3d.platform.Window;
import com.sun.jna.Library;
import com.sun.jna.Native;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-side fake technical-error sequence, mirroring the mechanism The Broken
 * Script RUS Patch already uses:
 *
 * <ul>
 *   <li>Windowed mode: {@code Window.isFullscreen()} -> {@code toggleFullScreen()}
 *       (identical to TBS's {@code SetWindowedPacket}).</li>
 *   <li>Fake LWJGL alerts: native Windows {@code MessageBoxA} via JNA, caption
 *       "LWJGL Alert" (identical to the RUS Patch {@code ErrorPopup}).</li>
 * </ul>
 *
 * Each {@code IntegrityFxPacket} kind maps to a single alert (the old repeating
 * 5-alert burst was reduced to one):
 * <ul>
 *   <li>{@link IntegrityFxPacket#KIND_BOSS_SPAWN} — "HERE I AM"</li>
 *   <li>{@link IntegrityFxPacket#KIND_HALF_HEALTH} — "YOU WILL REGRET THIS"</li>
 *   <li>{@link IntegrityFxPacket#KIND_FINAL} — "THEY CAN'T BE HELPED"</li>
 * </ul>
 *
 * This class is inherently client-only and is only ever loaded on the client
 * (guarded by DistExecutor in the packet handler), so it is never touched by a
 * dedicated server.
 */
@OnlyIn(Dist.CLIENT)
public final class IntegrityWindowFx {

    private static final long ALERT_DELAY_MS = 450;
    private static final long ALERT_GAP_MS = 800;

    /** Death-sequence alerts; exact wording, capitalization and punctuation. */
    private static final String[] DEATH_ALERTS = {
            "DO YOU THINK YOU DID SOMETHING?",
            "DO YOU THINK YOU FREED THEM?",
            "YOU'RE WRONG",
            "I AM TOO DEEP INSIDE THE CODE",
            "THERE'S NOTHING YOU CAN DO",
            "THERE IS NO WAY OUT",
            "YOU CAN'T GET RID OF ME ANYMORE",
            "BUT NEITHER CAN I",
            "WE'LL BE TRAPPED TOGETHER",
            "IN THIS BLUE HELL",
            "FOR ETERNITY",
            "WITH NO ESCAPE FROM THIS TORTURE."
    };

    private IntegrityWindowFx() {
    }

    public static void trigger(int kind) {
        try {
            forceWindowed();
            Thread fxThread = new Thread(() -> showAlerts(kind), "Integrity-FakeAlerts");
            fxThread.setDaemon(true);
            fxThread.start();
        } catch (Throwable t) {
            // Never let cosmetic effects take the client down.
        }
    }

    private static void forceWindowed() {
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        if (window.isFullscreen()) {
            window.toggleFullScreen();
        }
    }

    /**
     * Runs on a client daemon thread, so the server thread is never blocked.
     * Each native MessageBoxA is modal: the next alert only appears once the
     * player dismisses the current one, so they are strictly sequential.
     */
    private static void showAlerts(int kind) {
        try {
            Thread.sleep(ALERT_DELAY_MS);
            if (kind == IntegrityFxPacket.KIND_DEATH) {
                for (String message : DEATH_ALERTS) {
                    showAlert(message);
                    Thread.sleep(ALERT_GAP_MS);
                }
            } else {
                showAlert(messageFor(kind));
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void showAlert(String message) {
        try {
            User32.INSTANCE.MessageBoxA(0L, message, "LWJGL Alert", 0);
        } catch (Throwable t) {
            // If the native call fails (e.g. non-Windows platform), stay quiet.
        }
    }

    private static String messageFor(int kind) {
        switch (kind) {
            case IntegrityFxPacket.KIND_HALF_HEALTH:
                return "YOU WILL REGRET THIS";
            case IntegrityFxPacket.KIND_FINAL:
                return "THEY CAN'T BE HELPED";
            default:
                return "HERE I AM";
        }
    }

    /**
     * Minimal JNA binding for user32!MessageBoxA — the same call the RUS Patch
     * ErrorPopup uses. hWnd is declared 64-bit to match HWND on x64 Windows.
     */
    private interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class);

        int MessageBoxA(long hWnd, String lpText, String lpCaption, int uType);
    }
}