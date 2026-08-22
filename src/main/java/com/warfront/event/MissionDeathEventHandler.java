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
