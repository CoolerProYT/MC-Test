package com.coolerpromc.mctest.platform;

import com.coolerpromc.mctest.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.nio.file.Path;
import java.util.List;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public String getLoaderVersion() {

        return FabricLoader.getInstance().getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public List<ModInfo> getMods() {

        return FabricLoader.getInstance().getAllMods().stream()
                .map(c -> {
                    ModMetadata meta = c.getMetadata();
                    return new ModInfo(meta.getId(), meta.getName(), meta.getVersion().getFriendlyString());
                })
                .toList();
    }

    @Override
    public Path getGameDir() {

        return FabricLoader.getInstance().getGameDir();
    }
}
