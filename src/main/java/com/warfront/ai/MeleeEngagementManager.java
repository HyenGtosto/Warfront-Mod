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

    public static final int BASE_ACTIVE_SLOTS = 2;
    public static final int RALLIED_ACTIVE_SLOTS = 4;
    public static final int MAX_ACTIVE_SLOTS = RALLIED_ACTIVE_SLOTS;
    private static final Map<UUID, EngagementGroup> GROUPS = new ConcurrentHashMap<>();

    private static class EngagementGroup {
        final UUID targetId;
        final UUID[] activeSlots = new UUID[MAX_ACTIVE_SLOTS];
        // Key: mob UUID, Value: boolean isElite
        final Map<UUID, Boolean> reserveMobs = new ConcurrentHashMap<>();
        long rallyExpiryGameTime = 0L;

        EngagementGroup(UUID targetId) {
            this.targetId = targetId;
        }

        int getMaxSlots(Level level) {
            if (level != null && level.getGameTime() < rallyExpiryGameTime) {
                return RALLIED_ACTIVE_SLOTS;
            }
            return BASE_ACTIVE_SLOTS;
        }

        synchronized boolean requestSlot(Mob mob, boolean isElite) {
            UUID mobId = mob.getUUID();
            int maxSlots = getMaxSlots(mob.level());

            // Already holding an active slot
            for (int i = 0; i < MAX_ACTIVE_SLOTS; i++) {
                if (mobId.equals(activeSlots[i])) {
                    if (i < maxSlots) {
                        reserveMobs.remove(mobId);
                        return true;
                    } else {
                        // Slot is beyond current max capacity (rally expired): demote
                        activeSlots[i] = null;
                        reserveMobs.put(mobId, isElite);
                        return false;
                    }
                }
            }

            // Check for empty slot within allowed capacity
            for (int i = 0; i < maxSlots; i++) {
                if (activeSlots[i] == null) {
                    activeSlots[i] = mobId;
                    reserveMobs.remove(mobId);
                    return true;
                }
            }

            // All available slots are occupied: mob must hold ground in reserve
            reserveMobs.put(mobId, isElite);
            return false;
        }

        synchronized boolean isEngaged(Mob mob) {
            UUID mobId = mob.getUUID();
            int maxSlots = getMaxSlots(mob.level());
            for (int i = 0; i < maxSlots; i++) {
                if (mobId.equals(activeSlots[i])) {
                    return true;
                }
            }
            return false;
        }

        synchronized int getSlotIndex(Mob mob) {
            UUID mobId = mob.getUUID();
            int maxSlots = getMaxSlots(mob.level());
            for (int i = 0; i < maxSlots; i++) {
                if (mobId.equals(activeSlots[i])) {
                    return i;
                }
            }
            return -1;
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
            int maxSlots = getMaxSlots(level);
            int vacantIdx = -1;
            for (int i = 0; i < maxSlots; i++) {
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

            int maxSlots = getMaxSlots(level);

            // 1. Verify active slots are still alive and targeting this player
            for (int i = 0; i < MAX_ACTIVE_SLOTS; i++) {
                if (activeSlots[i] != null) {
                    Entity ent = sl.getEntity(activeSlots[i]);
                    if (!(ent instanceof Mob m) || !m.isAlive() || m.getTarget() == null || !targetId.equals(m.getTarget().getUUID())) {
                        activeSlots[i] = null;
                    }
                }
            }

            // 2. Post-buff expiry demotion: if active slots exceed current maxSlots, demote excess
            List<UUID> currentActive = new ArrayList<>();
            for (int i = 0; i < MAX_ACTIVE_SLOTS; i++) {
                if (activeSlots[i] != null) {
                    currentActive.add(activeSlots[i]);
                    activeSlots[i] = null; // Cleared for repopulation
                }
            }

            if (currentActive.size() > maxSlots) {
                // Sort active mobs: Elites first, then closest distance to player
                currentActive.sort((id1, id2) -> {
                    Entity e1 = sl.getEntity(id1);
                    Entity e2 = sl.getEntity(id2);
                    boolean isElite1 = e1 instanceof com.warfront.entity.PillagerEliteEntity;
                    boolean isElite2 = e2 instanceof com.warfront.entity.PillagerEliteEntity;
                    if (isElite1 != isElite2) {
                        return isElite1 ? -1 : 1; // Elites prioritized
                    }
                    if (e1 != null && e2 != null) {
                        return Double.compare(e1.distanceToSqr(player), e2.distanceToSqr(player));
                    }
                    return 0;
                });

                // Keep up to maxSlots, demote remainder back to reserveMobs
                for (int i = 0; i < currentActive.size(); i++) {
                    UUID mobId = currentActive.get(i);
                    Entity ent = sl.getEntity(mobId);
                    boolean isElite = ent instanceof com.warfront.entity.PillagerEliteEntity;
                    if (i < maxSlots) {
                        activeSlots[i] = mobId;
                    } else {
                        // Demoted back to reserve!
                        reserveMobs.put(mobId, isElite);
                    }
                }
            } else {
                for (int i = 0; i < currentActive.size(); i++) {
                    activeSlots[i] = currentActive.get(i);
                }
            }

            // 3. Promote if any slot became vacant below maxSlots
            promoteReserve(level);
        }

        synchronized void applyRallyBoost(Level level, int durationTicks) {
            long newExpiry = level.getGameTime() + durationTicks;
            if (newExpiry > this.rallyExpiryGameTime) {
                this.rallyExpiryGameTime = newExpiry;
            }
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
     * Returns the active slot index (0..3) if engaged, or -1 if in reserve / not engaged.
     */
    public static int getEngagedSlotIndex(LivingEntity target, Mob mob) {
        if (!(target instanceof Player)) return 0;
        EngagementGroup group = GROUPS.get(target.getUUID());
        if (group == null) return -1;
        return group.getSlotIndex(mob);
    }

    /**
     * Applies a flag rally boost to the target player, expanding melee engagement slots to 4.
     */
    public static void applyRallyBoost(UUID targetId, Level level, int durationTicks) {
        if (targetId == null || level == null) return;
        EngagementGroup group = GROUPS.computeIfAbsent(targetId, EngagementGroup::new);
        group.applyRallyBoost(level, durationTicks);
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
