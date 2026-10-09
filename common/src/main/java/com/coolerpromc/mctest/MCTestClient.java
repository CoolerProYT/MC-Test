package com.coolerpromc.mctest;

import com.coolerpromc.mctest.capture.LogCapture;
import com.coolerpromc.mctest.methods.ActionMethods;
import com.coolerpromc.mctest.methods.GameTestMethods;
import com.coolerpromc.mctest.methods.InputMethods;
import com.coolerpromc.mctest.methods.LogMethods;
import com.coolerpromc.mctest.methods.ScreenshotMethods;
import com.coolerpromc.mctest.methods.SkillMethods;
import com.coolerpromc.mctest.methods.StatusMethods;
import com.coolerpromc.mctest.methods.WaitMethods;
import com.coolerpromc.mctest.methods.WorldLifecycleMethods;
import com.coolerpromc.mctest.methods.WorldMethods;
import com.coolerpromc.mctest.platform.Services;
import com.coolerpromc.mctest.rpc.ClientThread;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.server.ControlServer;
import com.coolerpromc.mctest.tick.TickScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.tutorial.TutorialSteps;

/**
 * Common client entry point. Loader modules call {@link #init()} from their client-only entrypoints;
 * nothing here is ever reached on a dedicated server.
 */
public final class MCTestClient {

    private static boolean firstTickDone;

    private MCTestClient() {
    }

    public static void init() {
        // Install first so the rest of startup (including our own logs) is captured.
        LogCapture.ensureInstalled();
        Constants.LOG.info("MCTest client init on {} ({})", Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());

        StatusMethods.register();
        ScreenshotMethods.register();
        InputMethods.register();
        WorldMethods.register();
        WaitMethods.register();
        LogMethods.register();
        GameTestMethods.register(() -> {
            var server = ClientThread.mc().getSingleplayerServer();
            if (server == null) throw new RpcException("GameTests run in a singleplayer world here; on a dedicated server use mc_server_gametest_run");
            return server;
        });
        WorldLifecycleMethods.register();
        ActionMethods.register();
        SkillMethods.register();

        ControlServer.start(ControlServer.configuredPort());
    }

    /** Called by the loader at the end of every client tick, on the render thread. */
    public static void onClientTick(Minecraft mc) {
        if (!firstTickDone) {
            firstTickDone = true;
            // The game window is usually unfocused while a tool drives it; vanilla would then pop the pause menu
            // every time a screen closes. Opt back in with -Dmctest.pauseOnLostFocus=true.
            if (!Boolean.getBoolean("mctest.pauseOnLostFocus")) {
                mc.options.pauseOnLostFocus = false;
            }
            // Tutorial hint toasts ("Look around", "Punch a tree"...) cover screenshots. Opt in with -Dmctest.tutorial=true.
            if (!Boolean.getBoolean("mctest.tutorial")) {
                mc.getTutorial().setStep(TutorialSteps.NONE);
            }
        }
        // Loaders may reconfigure log4j after we attached; re-check occasionally (cheap map lookup).
        if (TickScheduler.tickCount() % 100 == 0) {
            LogCapture.ensureInstalled();
        }
        TickScheduler.tick();
        SkillMethods.updateLockstep(mc);
    }
}
