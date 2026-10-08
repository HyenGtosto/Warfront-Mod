package com.warfront.event;

import com.warfront.mission.ActiveCampaignMissionManager;
import com.warfront.region.Faction;
import com.warfront.spawn.RoamingEntityTracker;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Handles mob death events for Warfront enemies, attributing kills to active subregion missions.
 */
public final class MissionDeathEventHandler {

    private MissionDeathEventHandler() {
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }

        CompoundTag data = mob.getPersistentData();
        boolean isWarfront = data.getBoolean(RoamingEntityTracker.WARFRONT_TAG)
                || data.getBoolean("isMissionMob")
                || data.getBoolean("isPatrolMob")
                || mob.getTags().contains("warfront_mission")
                || mob.getTags().contains("warfront_patrol");
        if (!isWarfront) {
            return; // Not a Warfront-owned mob
        }

        int originRegionX = data.getInt("originRegionX");
        int originRegionZ = data.getInt("originRegionZ");
        int originSubX = data.getInt("originSubX");
        int originSubZ = data.getInt("originSubZ");

        int blockX = (int) mob.getX();
        int blockZ = (int) mob.getZ();
        int currentRegionX = Math.floorDiv(blockX, com.warfront.region.RegionData.REGION_SIZE_BLOCKS);
        int currentRegionZ = Math.floorDiv(blockZ, com.warfront.region.RegionData.REGION_SIZE_BLOCKS);
        int currentSubX = Math.floorMod(Math.floorDiv(blockX, com.warfront.spawn.ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS), 2);
        int currentSubZ = Math.floorMod(Math.floorDiv(blockZ, com.warfront.spawn.ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS), 2);

        if (data.getBoolean("isAttackRoamer")) {
            if (currentRegionX == originRegionX && currentRegionZ == originRegionZ) {
                originSubX = currentSubX;
                originSubZ = currentSubZ;
            }
        }

        // If the origin subregion doesn't have an active mission, but the entity died within an active mission subregion
        if (!ActiveCampaignMissionManager.hasActiveMission(originRegionX, originRegionZ, originSubX, originSubZ)) {
            if (ActiveCampaignMissionManager.hasActiveMission(currentRegionX, currentRegionZ, currentSubX, currentSubZ)) {
                originRegionX = currentRegionX;
                originRegionZ = currentRegionZ;
                originSubX = currentSubX;
                originSubZ = currentSubZ;
            }
        }

        int factionId = data.getInt("faction");
        String roleName = data.getString("targetRoleName");

        Faction faction = Faction.byId(factionId);

        ActiveCampaignMissionManager.onEntityKilled(
                level,
                originRegionX, originRegionZ,
                originSubX, originSubZ,
                faction,
                roleName,
                mob
        );
    }
}
