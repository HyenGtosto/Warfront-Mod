package com.warfront.mission;

import com.warfront.mission.biome.DesertMissionsHandler;
import com.warfront.mission.biome.ForestMissionsHandler;
import com.warfront.mission.biome.MountainMissionsHandler;
import com.warfront.mission.biome.PlainsMissionsHandler;
import com.warfront.mission.biome.SwampMissionsHandler;
import com.warfront.mission.biome.TundraMissionsHandler;
import com.warfront.mission.easy.ForwardOutpostMissionHandler;
import com.warfront.mission.easy.ForwardPatrolMissionHandler;
import com.warfront.mission.easy.ScoutNetworkMissionHandler;
import com.warfront.mission.easy.SupplyConvoyMissionHandler;

import java.util.EnumMap;
import java.util.Map;

/**
 * Central registry mapping {@link MissionType} values to their dedicated {@link MissionObjectiveHandler}.
 * Falls back to {@link KillCountMissionHandler} for any unmigrated mission types.
 */
public final class MissionHandlerRegistry {

    private static final Map<MissionType, MissionObjectiveHandler> HANDLERS = new EnumMap<>(MissionType.class);
    private static final MissionObjectiveHandler DEFAULT_HANDLER = KillCountMissionHandler.getInstance();

    static {
        // Easy Tier Mission Handlers (Iteration 2)
        HANDLERS.put(MissionType.KILL_COUNT, KillCountMissionHandler.getInstance());
        HANDLERS.put(MissionType.FORWARD_PATROL, ForwardPatrolMissionHandler.getInstance());
        HANDLERS.put(MissionType.SUPPLY_CONVOY, SupplyConvoyMissionHandler.getInstance());
        HANDLERS.put(MissionType.FORWARD_OUTPOST, ForwardOutpostMissionHandler.getInstance());
        HANDLERS.put(MissionType.SCOUT_NETWORK, ScoutNetworkMissionHandler.getInstance());

        // Base Outpost Mission Handler
        HANDLERS.put(MissionType.OUTPOST_DESTROY_BUILDING, com.warfront.mission.base.OutpostDestructionMissionHandler.getInstance());

        // Mobile Field Army Battle Handler
        HANDLERS.put(MissionType.FIELD_BATTLE, ArmyFieldBattleMissionHandler.getInstance());

        // Hard Tier Mission Handlers (Iteration 3)
        HANDLERS.put(MissionType.STRONGPOINT_ASSAULT, com.warfront.mission.hard.StrongpointAssaultMissionHandler.getInstance());
        HANDLERS.put(MissionType.OFFICER_HUNT, com.warfront.mission.hard.OfficerHuntMissionHandler.getInstance());
        HANDLERS.put(MissionType.ARTILLERY_BATTERY, com.warfront.mission.hard.ArtilleryBatteryMissionHandler.getInstance());
        HANDLERS.put(MissionType.WAR_HORN_RELAY, com.warfront.mission.hard.WarHornRelayMissionHandler.getInstance());
        HANDLERS.put(MissionType.CUT_THE_SUPPLY_LINE, com.warfront.mission.hard.WarHornRelayMissionHandler.getInstance());

        // Defense Mission Handlers (Zero-Structure Defend Operations)
        HANDLERS.put(MissionType.DEFEAT_INVADING_FORCES, com.warfront.mission.defense.DefeatInvadingForcesMissionHandler.getInstance());
        HANDLERS.put(MissionType.HOLD_THE_CHOKEPOINT, com.warfront.mission.defense.HoldTheChokepointMissionHandler.getInstance());

        // Biome-Specific Mission Handlers (Highlands, Woodlands, Wetlands, Arid, Plains, Arctic)
        HANDLERS.put(MissionType.MOUNTAIN_LOOKOUT_POST, MountainMissionsHandler.getInstance());
        HANDLERS.put(MissionType.MOUNTAIN_PASS_BARRICADE, MountainMissionsHandler.getInstance());
        HANDLERS.put(MissionType.MOUNTAIN_BEACON_BASTION, MountainMissionsHandler.getInstance());
        HANDLERS.put(MissionType.MOUNTAIN_RIDGE_BATTERY, MountainMissionsHandler.getInstance());

        HANDLERS.put(MissionType.FOREST_LOGGING_DEPOT, ForestMissionsHandler.getInstance());
        HANDLERS.put(MissionType.FOREST_HUNTING_STASH, ForestMissionsHandler.getInstance());
        HANDLERS.put(MissionType.FOREST_CANOPY_BLINDS, ForestMissionsHandler.getInstance());
        HANDLERS.put(MissionType.FOREST_COMMAND_CAMP, ForestMissionsHandler.getInstance());

        HANDLERS.put(MissionType.SWAMP_STRANDED_BARGE, SwampMissionsHandler.getInstance());
        HANDLERS.put(MissionType.SWAMP_FUEL_STILL, SwampMissionsHandler.getInstance());
        HANDLERS.put(MissionType.SWAMP_STILT_REDOUBT, SwampMissionsHandler.getInstance());
        HANDLERS.put(MissionType.SWAMP_IRONCLAD_HOLD, SwampMissionsHandler.getInstance());

        HANDLERS.put(MissionType.DESERT_ROAD_CHECKPOINT, DesertMissionsHandler.getInstance());
        HANDLERS.put(MissionType.DESERT_CISTERN_DEPOT, DesertMissionsHandler.getInstance());
        HANDLERS.put(MissionType.BADLANDS_MESA_BATTERY, DesertMissionsHandler.getInstance());
        HANDLERS.put(MissionType.DESERT_CANYON_STRONGPOINT, DesertMissionsHandler.getInstance());

        HANDLERS.put(MissionType.PLAINS_SCOUT_PADDOCK, PlainsMissionsHandler.getInstance());
        HANDLERS.put(MissionType.PLAINS_SIGNAL_MAST, PlainsMissionsHandler.getInstance());
        HANDLERS.put(MissionType.PLAINS_TRENCH_REDOUBT, PlainsMissionsHandler.getInstance());
        HANDLERS.put(MissionType.PLAINS_FIELD_COMMAND, PlainsMissionsHandler.getInstance());

        HANDLERS.put(MissionType.TUNDRA_SUPPLY_CACHE, TundraMissionsHandler.getInstance());
        HANDLERS.put(MissionType.TUNDRA_FROZEN_WATCHPOST, TundraMissionsHandler.getInstance());
        HANDLERS.put(MissionType.TUNDRA_MORTAR_PIT, TundraMissionsHandler.getInstance());
        HANDLERS.put(MissionType.TUNDRA_GARRISON_REDOUBT, TundraMissionsHandler.getInstance());
    }

    private MissionHandlerRegistry() {
    }

    /**
     * Resolves the authoritative {@link MissionObjectiveHandler} for the given mission type.
     */
    public static MissionObjectiveHandler getHandler(MissionType type) {
        if (type == null) {
            return DEFAULT_HANDLER;
        }
        return HANDLERS.getOrDefault(type, DEFAULT_HANDLER);
    }
}
