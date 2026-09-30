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
        if (!data.getBoolean(RoamingEntityTracker.WARFRONT_TAG)) {
            return; // Not a Warfront-owned mob
        }

        int originRegionX = data.getInt("originRegionX");
        int originRegionZ = data.getInt("originRegionZ");
        int originSubX = data.getInt("originSubX");
        int originSubZ = data.getInt("originSubZ");

        if (data.getBoolean("isAttackRoamer")) {
            int blockX = (int) mob.getX();
            int blockZ = (int) mob.getZ();
            int currentRegionX = Math.floorDiv(blockX, com.warfront.region.RegionData.REGION_SIZE_BLOCKS);
            int currentRegionZ = Math.floorDiv(blockZ, com.warfront.region.RegionData.REGION_SIZE_BLOCKS);
            if (currentRegionX == originRegionX && currentRegionZ == originRegionZ) {
                originSubX = Math.floorMod(Math.floorDiv(blockX, com.warfront.spawn.ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS), 2);
                originSubZ = Math.floorMod(Math.floorDiv(blockZ, com.warfront.spawn.ExplorationSpawnManager.SUBREGION_SIZE_BLOCKS), 2);
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
                roleName
        );
    }
}
