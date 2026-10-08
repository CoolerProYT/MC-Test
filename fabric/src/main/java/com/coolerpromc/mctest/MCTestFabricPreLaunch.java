package com.coolerpromc.mctest;

import com.coolerpromc.mctest.capture.LogCapture;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Attaches log capture before any mod initializes, so the tested mod's startup logs and errors are visible. */
public class MCTestFabricPreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        LogCapture.ensureInstalled();
    }
}
