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
     * Checks if an entity belongs to the Pillager faction.
     */
    public static boolean isPillager(Entity entity) {
        if (entity == null) return false;
        if (entity instanceof Raider || entity instanceof AbstractIllager) return true;
        if (entity.getType().is(net.minecraft.tags.EntityTypeTags.RAIDERS) ||
            entity.getType().is(net.minecraft.tags.EntityTypeTags.ILLAGER) ||
            entity.getType().is(net.minecraft.tags.EntityTypeTags.ILLAGER_FRIENDS)) {
            return true;
        }
        if (entity.getPersistentData().contains("faction")) {
            return entity.getPersistentData().getInt("faction") == com.warfront.region.Faction.PILLAGER_CONQUERORS.id();
        }
        return false;
    }

    /**
     * Determines whether two entities are allies and should never fight each other.
     */
    public static boolean isAllied(Entity a, Entity b) {
        if (a == null || b == null || a == b) return true;

        if (a instanceof LivingEntity livingA && b instanceof LivingEntity livingB) {
            // 1. Both are Pillagers / Raiders / Illagers (Pillager Conquerors faction)
            if (isPillager(livingA) && isPillager(livingB)) {
                return true;
            }

            // 2. Both are Zombies (Zombie Horde faction)
            if (livingA instanceof Zombie && livingB instanceof Zombie) {
                return true;
            }

            // 3. Vanilla scoreboard teams
            if (livingA.getTeam() != null && livingB.getTeam() != null && livingA.getTeam().isAlliedTo(livingB.getTeam())) {
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
