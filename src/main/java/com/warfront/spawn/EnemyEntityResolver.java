package com.warfront.spawn;

import com.warfront.entity.ModEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Centralized resolver that maps enemy roles to their concrete entity implementation.
 */
public final class EnemyEntityResolver {

    private EnemyEntityResolver() {
    }

    /**
     * Creates the entity corresponding to the given Zombie role.
     * Returns null if the entity could not be created.
     *
     * @param role  the zombie enemy role
     * @param level the server level to create the entity in
     * @return a new Entity instance, or null on failure
     */
    public static Entity resolveZombieRole(ZombieEnemyRole role, ServerLevel level) {
        return switch (role) {
            case FODDER, FAST_CHASER, RANGED, TANK, HIVEMIND_CONTROLLER ->
                    EntityType.ZOMBIE.create(level);
        };
    }

    /**
     * Creates the entity corresponding to the given Pillager role.
     *
     * @param role  the pillager enemy role
     * @param level the server level to create the entity in
     * @return a new Entity instance, or null on failure
     */
    public static Entity resolvePillagerRole(PillagerEnemyRole role, ServerLevel level) {
        return switch (role) {
            case ARMORED_ELITE ->
                    ModEntities.PILLAGER_ELITE.get().create(level);
            case FIGHTER ->
                    ModEntities.PILLAGER_WARRIOR.get().create(level);
            case SCOUT ->
                    ModEntities.PILLAGER_SCOUT.get().create(level);
            case RANGED ->
                    ModEntities.PILLAGER_MARKSMAN.get().create(level);
            case COMMANDER ->
                    ModEntities.PILLAGER_COMMANDER.get().create(level);
            case CANNONEER ->
                    ModEntities.PILLAGER_CANNONEER.get().create(level);
            case RAPIDBOW ->
                    ModEntities.PILLAGER_RAPIDBOW.get().create(level);
            case IRONCLAD ->
                    ModEntities.PILLAGER_IRONCLAD.get().create(level);
        };
    }
}
