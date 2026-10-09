package com.coolerpromc.mctest.util;

import com.coolerpromc.mctest.platform.Services;
import com.coolerpromc.mctest.platform.services.IPlatformHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;

/**
 * JSON views of game objects shared by the client and the dedicated server. Must not touch client-only classes: it is
 * loaded on dedicated servers.
 */
public final class Describe {

    private static final int COMPONENT_VALUE_LIMIT = 200;

    private Describe() {
    }

    /** Every loaded mod, sorted by id. */
    public static JsonArray mods() {
        JsonArray mods = new JsonArray();
        Services.PLATFORM.getMods().stream()
                .sorted(Comparator.comparing(IPlatformHelper.ModInfo::id))
                .forEach(m -> {
                    JsonObject mod = new JsonObject();
                    mod.addProperty("id", m.id());
                    mod.addProperty("name", m.name());
                    mod.addProperty("version", m.version());
                    mods.add(mod);
                });
        return mods;
    }

    public static JsonObject vec(Vec3 v) {
        JsonObject o = new JsonObject();
        o.addProperty("x", v.x);
        o.addProperty("y", v.y);
        o.addProperty("z", v.z);
        return o;
    }

    public static JsonObject pos(BlockPos p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        return o;
    }

    /** Item id, count, display name, damage and the components that differ from the item's defaults. */
    public static @Nullable JsonObject stack(ItemStack item) {
        if (item.isEmpty()) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("item", BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
        o.addProperty("count", item.getCount());
        o.addProperty("name", item.getHoverName().getString());
        if (item.isDamageableItem()) {
            o.addProperty("damage", item.getDamageValue());
            o.addProperty("maxDamage", item.getMaxDamage());
        }
        DataComponentPatch patch = item.getComponentsPatch();
        if (!patch.isEmpty()) {
            DataComponentPatch.SplitResult split = patch.split();
            JsonObject components = new JsonObject();
            for (TypedDataComponent<?> c : split.added()) {
                String value = String.valueOf(c.value());
                if (value.length() > COMPONENT_VALUE_LIMIT) {
                    value = value.substring(0, COMPONENT_VALUE_LIMIT) + "...";
                }
                components.addProperty(String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(c.type())), value);
            }
            o.add("components", components);
            if (!split.removed().isEmpty()) {
                JsonArray removed = new JsonArray();
                split.removed().forEach(t -> removed.add(String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(t))));
                o.add("removedComponents", removed);
            }
        }
        return o;
    }

    /** Block id, state properties and block-entity NBT at {@code pos}. */
    public static JsonObject block(Level level, BlockPos pos, String source) {
        JsonObject out = new JsonObject();
        out.add("pos", pos(pos));
        out.addProperty("source", source);
        out.addProperty("dimension", level.dimension().identifier().toString());
        out.addProperty("loaded", level.isLoaded(pos));
        BlockState state = level.getBlockState(pos);
        out.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        JsonObject properties = new JsonObject();
        state.getValues().forEach(v -> properties.addProperty(v.property().getName(), v.valueName()));
        out.add("properties", properties);
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            JsonObject b = new JsonObject();
            b.addProperty("type", String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())));
            b.addProperty("nbt", be.saveWithFullMetadata(level.registryAccess()).toString());
            out.add("blockEntity", b);
        } else {
            out.add("blockEntity", null);
        }
        return out;
    }

    /** Id, type, name, position, rotation, health and item of an entity; {@code distance} from {@code origin} if given. */
    public static JsonObject entity(Entity e, @Nullable Vec3 origin) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.getId());
        o.addProperty("uuid", e.getUUID().toString());
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        o.addProperty("name", e.getName().getString());
        o.addProperty("customName", e.getCustomName() == null ? null : e.getCustomName().getString());
        o.add("pos", vec(e.position()));
        if (origin != null) {
            o.addProperty("distance", e.position().distanceTo(origin));
        }
        o.addProperty("yaw", e.getYRot());
        o.addProperty("pitch", e.getXRot());
        if (e instanceof LivingEntity living) {
            o.addProperty("health", living.getHealth());
            o.addProperty("maxHealth", living.getMaxHealth());
        }
        if (e instanceof ItemEntity item) {
            o.add("item", stack(item.getItem()));
        }
        return o;
    }
}
