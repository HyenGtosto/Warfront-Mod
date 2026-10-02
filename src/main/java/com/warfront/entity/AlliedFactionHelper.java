package com.warfront.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.raid.Raider;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

/**
 * Utility to prevent infighting and friendly fire retaliation among allied hostile mobs.
 */
public final class AlliedFactionHelper {

    private AlliedFactionHelper() {}

    /**
     * Determines whether two entities are allies and should never fight each other.
     */
    public static boolean isAllied(Entity a, Entity b) {
        if (a == null || b == null || a == b) return true;

        if (a instanceof LivingEntity livingA && b instanceof LivingEntity livingB) {
            // 1. Both are Raiders / Illagers (Pillager Conquerors faction)
            if (livingA instanceof Raider && livingB instanceof Raider) {
                return true;
            }
            if (livingA instanceof AbstractIllager && livingB instanceof AbstractIllager) {
                return true;
            }

            // 2. Both are Zombies (Zombie Horde faction)
            if (livingA instanceof Zombie && livingB instanceof Zombie) {
                return true;
            }

            // 3. Vanilla teams / alliance
            if (livingA.isAlliedTo(livingB) || livingB.isAlliedTo(livingA)) {
                return true;
            }

            // 4. Same squad UUID
            if (livingA.getPersistentData().contains("squadId") && livingB.getPersistentData().contains("squadId")) {
                if (livingA.getPersistentData().getUUID("squadId").equals(livingB.getPersistentData().getUUID("squadId"))) {
                    return true;
                }
            }

            // 5. Same Warfront faction ID
            if (livingA.getPersistentData().contains("faction") && livingB.getPersistentData().contains("faction")) {
                if (livingA.getPersistentData().getInt("faction") == livingB.getPersistentData().getInt("faction")) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * NeoForge event listener: intercepts all target changes across all living entities.
     * If an entity attempts to target an ally, cancels the target change completely.
     */
    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity entity = event.getEntity();
        LivingEntity newTarget = event.getNewAboutToBeSetTarget();
        if (entity != null && newTarget != null) {
            if (isAllied(entity, newTarget)) {
                event.setNewAboutToBeSetTarget(null);
                event.setCanceled(true);
            }
        }
    }
}
