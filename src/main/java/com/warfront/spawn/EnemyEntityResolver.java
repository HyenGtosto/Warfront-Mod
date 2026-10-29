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
        return resolvePillagerRole(role, level, 0.0F);
    }

    /**
     * Creates the entity corresponding to the given Pillager role with resistance-based spawn interception.
     * For RANGED roles, intercepts Marksman spawns with quadratic probability P(R) = (R / 100)^2,
     * replacing with Pillager Rifleman (25% at 50 resistance, 100% at 100 resistance).
     *
     * @param role       the pillager enemy role
     * @param level      the server level to create the entity in
     * @param resistance regional resistance level (0 to 100)
     * @return a new Entity instance, or null on failure
     */
    public static Entity resolvePillagerRole(PillagerEnemyRole role, ServerLevel level, float resistance) {
        if (role == PillagerEnemyRole.RANGED) {
            if (resistance > 0.0F) {
                float clampedR = Math.clamp(resistance, 0.0F, 100.0F);
                float pRifleman = (clampedR / 100.0F) * (clampedR / 100.0F);
                if (level.getRandom().nextFloat() < pRifleman) {
                    return ModEntities.PILLAGER_RIFLEMAN.get().create(level);
                }
            }
            return ModEntities.PILLAGER_MARKSMAN.get().create(level);
        }

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
