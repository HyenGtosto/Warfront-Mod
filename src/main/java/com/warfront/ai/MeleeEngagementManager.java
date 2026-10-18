package com.warfront.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates melee engagements against players to prevent mob dogpiling / lynching.
 * Enforces a strict limit of 2 active melee attackers (Warriors & Elites) per player.
 * Excess melee combatants hold ground 5-6 blocks away in reserve.
 * When an active combatant dies or disengages, a reserve mob takes its slot at random,
 * with Elites having higher priority over Warriors.
 */
public class MeleeEngagementManager {

    public static final int MAX_ACTIVE_SLOTS = 2;
    private static final Map<UUID, EngagementGroup> GROUPS = new ConcurrentHashMap<>();

    private static class EngagementGroup {
        final UUID targetId;
        final UUID[] activeSlots = new UUID[MAX_ACTIVE_SLOTS];
        // Key: mob UUID, Value: boolean isElite
        final Map<UUID, Boolean> reserveMobs = new ConcurrentHashMap<>();

        EngagementGroup(UUID targetId) {
            this.targetId = targetId;
        }

        synchronized boolean requestSlot(Mob mob, boolean isElite) {
            UUID mobId = mob.getUUID();

            // Already holding an active slot
            for (int i = 0; i < MAX_ACTIVE_SLOTS; i++) {
                if (mobId.equals(activeSlots[i])) {
                    reserveMobs.remove(mobId);
                    return true;
                }
            }

            // Check for empty slot
            for (int i = 0; i < MAX_ACTIVE_SLOTS; i++) {
                if (activeSlots[i] == null) {
                    activeSlots[i] = mobId;
                    reserveMobs.remove(mobId);
                    return true;
                }
            }

            // Both slots are occupied: mob must hold ground in reserve
            reserveMobs.put(mobId, isElite);
            return false;
        }

        synchronized boolean isEngaged(Mob mob) {
            UUID mobId = mob.getUUID();
            for (int i = 0; i < MAX_ACTIVE_SLOTS; i++) {
                if (mobId.equals(activeSlots[i])) {
                    return true;
                }
            }
            return false;
        }

        synchronized void releaseSlot(Mob mob, Level level) {
            UUID mobId = mob.getUUID();
            reserveMobs.remove(mobId);

            boolean released = false;
            for (int i = 0; i < MAX_ACTIVE_SLOTS; i++) {
                if (mobId.equals(activeSlots[i])) {
                    activeSlots[i] = null;
                    released = true;
                    break;
                }
            }

            if (released) {
                promoteReserve(level);
            }
        }

        synchronized void promoteReserve(Level level) {
            int vacantIdx = -1;
            for (int i = 0; i < MAX_ACTIVE_SLOTS; i++) {
                if (activeSlots[i] == null) {
                    vacantIdx = i;
                    break;
                }
            }
            if (vacantIdx == -1 || reserveMobs.isEmpty()) return;

            // Collect valid alive reserve candidates
            List<UUID> elites = new ArrayList<>();
            List<UUID> warriors = new ArrayList<>();

            Iterator<Map.Entry<UUID, Boolean>> it = reserveMobs.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Boolean> entry = it.next();
                UUID candidateId = entry.getKey();
                Entity candidateEntity = (level instanceof ServerLevel sl) ? sl.getEntity(candidateId) : null;
                if (candidateEntity instanceof Mob m && m.isAlive()) {
                    if (entry.getValue()) {
                        elites.add(candidateId);
                    } else {
                        warriors.add(candidateId);
                    }
                } else {
                    it.remove(); // Prune dead/unloaded mobs
                }
            }

            RandomSource random = (level != null) ? level.getRandom() : RandomSource.create();
            UUID promotedId = null;

            // Elites have priority over Warriors
            if (!elites.isEmpty()) {
                promotedId = elites.get(random.nextInt(elites.size()));
            } else if (!warriors.isEmpty()) {
                promotedId = warriors.get(random.nextInt(warriors.size()));
            }

            if (promotedId != null) {
                activeSlots[vacantIdx] = promotedId;
                reserveMobs.remove(promotedId);
            }
        }

        synchronized void prune(Level level) {
            if (!(level instanceof ServerLevel sl)) return;

            Player player = sl.getPlayerByUUID(targetId);
            if (player == null || !player.isAlive()) {
                GROUPS.remove(targetId);
                return;
            }

            // Verify active slots are still alive and targeting this player
            for (int i = 0; i < MAX_ACTIVE_SLOTS; i++) {
                if (activeSlots[i] != null) {
                    Entity ent = sl.getEntity(activeSlots[i]);
                    if (!(ent instanceof Mob m) || !m.isAlive() || m.getTarget() == null || !targetId.equals(m.getTarget().getUUID())) {
                        activeSlots[i] = null;
                    }
                }
            }

            // Promote if any slot became vacant
            promoteReserve(level);
        }
    }

    /**
     * Queries if the mob can engage in active melee combat with the target.
     * If 2 melee entities are already engaged with the target player, registers the mob in reserve
     * and returns false (mob must hold ground 5-6 blocks away).
     */
    public static boolean requestSlot(LivingEntity target, Mob mob, boolean isElite) {
        if (!(target instanceof Player)) {
            return true; // Non-player targets allow unrestricted engagement
        }
        if (!mob.isAlive()) return false;

        EngagementGroup group = GROUPS.computeIfAbsent(target.getUUID(), EngagementGroup::new);
        if (mob.tickCount % 20 == 0) {
            group.prune(mob.level());
        }
        return group.requestSlot(mob, isElite);
    }

    /**
     * Checks if the mob currently holds an active melee combat slot for the target.
     */
    public static boolean isEngaged(LivingEntity target, Mob mob) {
        if (!(target instanceof Player)) return true;
        EngagementGroup group = GROUPS.get(target.getUUID());
        if (group == null) return false;
        return group.isEngaged(mob);
    }

    /**
     * Releases any slot held by this mob for the given target, promoting a reserve mob if available.
     */
    public static void releaseSlot(LivingEntity target, Mob mob) {
        if (target != null) {
            EngagementGroup group = GROUPS.get(target.getUUID());
            if (group != null) {
                group.releaseSlot(mob, mob.level());
            }
        } else {
            releaseAll(mob);
        }
    }

    /**
     * Releases any slot held by this mob across all targets (e.g. on mob death / unload).
     */
    public static void releaseAll(Mob mob) {
        UUID mobId = mob.getUUID();
        for (EngagementGroup group : GROUPS.values()) {
            group.releaseSlot(mob, mob.level());
        }
    }

    /**
     * Called when an engaged mob dies, immediately freeing its slot and promoting an Elite or Warrior.
     */
    public static void onMobDied(LivingEntity target, Mob mob) {
        releaseSlot(target, mob);
    }
}
