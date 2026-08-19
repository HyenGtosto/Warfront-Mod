package com.warfront.spawn;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.server.level.ServerLevel;

/**
 * Centralized resolver that maps enemy roles to their current entity implementation.
 *
 * This is the single place that translates an abstract role (e.g. ZombieEnemyRole.TANK)
 * into a concrete Minecraft entity. When custom entities are implemented, only this
 * class needs updating — encounter generation code remains unchanged.
 *
 * Zombie role → entity mapping (phase 1, all placeholder):
 *   FODDER              → vanilla Zombie
 *   FAST_CHASER         → vanilla Zombie
 *   RANGED              → vanilla Zombie
 *   TANK                → vanilla Zombie
 *   HIVEMIND_CONTROLLER → vanilla Zombie
 *
 * Pillager role → entity mapping (phase 1):
 *   RANGED        → vanilla Pillager
 *   FIGHTER       → vanilla Vindicator
 *   SCOUT         → vanilla Pillager   (placeholder)
 *   ARMORED_ELITE → vanilla Pillager   (placeholder)
 *   COMMANDER     → vanilla Pillager   (placeholder)
 *   CATAPULT      → never resolved here (excluded from roaming)
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
     * CATAPULT is not resolved here and returns null to enforce exclusion.
     *
     * @param role  the pillager enemy role
     * @param level the server level to create the entity in
     * @return a new Entity instance, or null if not resolvable for roaming
     */
    public static Entity resolvePillagerRole(PillagerEnemyRole role, ServerLevel level) {
        return switch (role) {
            case RANGED, SCOUT, ARMORED_ELITE, COMMANDER ->
                    EntityType.PILLAGER.create(level);
            case FIGHTER ->
                    EntityType.VINDICATOR.create(level);
            case CATAPULT ->
                    null; // Catapult is a base-defense role — never spawned as a roaming encounter
        };
    }
}
