package com.coolerpromc.mctest.methods;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * MCTest's own GameTest fixture, registered only with {@code -Dmctest.selftest=true} (set in this project's dev runs):
 * {@code mctest_selftest:passes} and {@code mctest_selftest:fails}, on an empty 1x1x1 structure made in memory.
 */
public final class SelfTests {

    public static final ResourceLocation STRUCTURE = new ResourceLocation("mctest", "selftest_empty");
    private static boolean registered;

    public SelfTests() {
        // GameTestRegistry instantiates the class to call the generator.
    }

    public static boolean enabled() {
        return Boolean.getBoolean("mctest.selftest");
    }

    public static synchronized void register() {
        if (registered || !enabled()) return;
        registered = true;
        try {
            GameTestRegistry.register(SelfTests.class.getDeclaredMethod("tests"));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @GameTestGenerator
    public Collection<TestFunction> tests() {
        return List.of(test("passes", GameTestHelper::succeed), test("fails", h -> h.fail("MCTest self-test failure (expected)")));
    }

    private static TestFunction test(String name, Consumer<GameTestHelper> body) {
        return new TestFunction("mctest_selftest", "mctest_selftest:" + name, STRUCTURE.toString(), 20, 0, true, body);
    }

    /** Creates the empty structure in the server's template cache (air captured from the top of the world). */
    public static void ensureStructure(MinecraftServer server) {
        if (!registered || server.getStructureManager().get(STRUCTURE).isPresent()) return;
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos top = new BlockPos(spawn.getX(), level.getMaxBuildHeight() - 1, spawn.getZ());
        server.getStructureManager().getOrCreate(STRUCTURE).fillFromWorld(level, top, new Vec3i(1, 1, 1), false, null);
    }
}
