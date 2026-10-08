package com.coolerpromc.mctest.nav;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Player-like steering: turns the camera gradually (a capped number of degrees per tick, like a mouse) and drives the
 * movement key mappings, so movement goes through vanilla input handling exactly as a person's would.
 */
public final class PlayerControl {

    /** Max turn per tick; a quick but human flick. */
    public static final float TURN_SPEED = 40f;

    private PlayerControl() {
    }

    /** Turns toward the point and returns the remaining yaw error in degrees. */
    public static float turnToward(LocalPlayer p, Vec3 target, boolean pitchToo) {
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x;
        double dz = target.z - eye.z;
        float wantYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        float yaw = Mth.approachDegrees(p.getYRot(), wantYaw, TURN_SPEED);
        p.setYRot(yaw);
        if (pitchToo) {
            double dy = target.y - eye.y;
            float wantPitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
            p.setXRot(Mth.approachDegrees(p.getXRot(), Mth.clamp(wantPitch, -90f, 90f), TURN_SPEED));
        } else {
            // Look roughly where you're walking, slightly down, as players do.
            p.setXRot(Mth.approachDegrees(p.getXRot(), 10f, TURN_SPEED / 2));
        }
        return Math.abs(Mth.degreesDifference(yaw, wantYaw));
    }

    public static void move(Minecraft mc, boolean forward, boolean jump, boolean sprint) {
        mc.options.keyUp.setDown(forward);
        mc.options.keyJump.setDown(jump);
        mc.options.keySprint.setDown(sprint);
    }

    public static void release(Minecraft mc) {
        move(mc, false, false, false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
    }
}
