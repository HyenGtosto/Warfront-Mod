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

        // Handle persistent base garrison mob death (e.g. outside mission passive respawn timers)
        com.warfront.region.base.garrison.BaseGarrisonManager.handleMobDeath(mob, level);

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

        boolean isMissionMob = data.getBoolean("isMissionMob") || mob.getTags().contains("warfront_mission");

        // For ambient and patrol mobs, require player involvement in the death so natural causes (daylight, fall, despawn)
        // do not automatically advance kill count missions in the background
        if (!isMissionMob) {
            boolean playerInvolved = false;
            if (event.getSource() != null && event.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player) {
                playerInvolved = true;
            } else if (mob.getKillCredit() instanceof net.minecraft.world.entity.player.Player) {
                playerInvolved = true;
            } else if (mob.getLastHurtByMob() instanceof net.minecraft.world.entity.player.Player) {
                playerInvolved = true;
            } else if (event.getSource() != null && event.getSource().getEntity() instanceof net.minecraft.world.entity.TamableAnimal tamed && tamed.getOwner() instanceof net.minecraft.world.entity.player.Player) {
                playerInvolved = true;
            } else if (event.getSource() != null && event.getSource().getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile proj && proj.getOwner() instanceof net.minecraft.world.entity.player.Player) {
                playerInvolved = true;
            }
            if (!playerInvolved) {
                return;
            }
        }

        int targetRegionX;
        int targetRegionZ;
        int targetSubX;
        int targetSubZ;

        // If explicitly spawned as a mission entity and that specific mission is active, route to origin.
        // Otherwise (patrol mobs, roamers), route strictly to where the killed target physically is.
        if (isMissionMob && ActiveCampaignMissionManager.hasActiveMission(originRegionX, originRegionZ, originSubX, originSubZ)) {
            targetRegionX = originRegionX;
            targetRegionZ = originRegionZ;
            targetSubX = originSubX;
            targetSubZ = originSubZ;
        } else {
            targetRegionX = currentRegionX;
            targetRegionZ = currentRegionZ;
            targetSubX = currentSubX;
            targetSubZ = currentSubZ;
        }

        int factionId = data.getInt("faction");
        String roleName = data.getString("targetRoleName");

        Faction faction = Faction.byId(factionId);

        ActiveCampaignMissionManager.onEntityKilled(
                level,
                targetRegionX, targetRegionZ,
                targetSubX, targetSubZ,
                faction,
                roleName,
                mob
        );
    }
}
