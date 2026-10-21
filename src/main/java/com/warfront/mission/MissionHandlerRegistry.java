package com.warfront.mission;

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

        // Hard Tier Mission Handlers (Iteration 3)
        HANDLERS.put(MissionType.STRONGPOINT_ASSAULT, com.warfront.mission.hard.StrongpointAssaultMissionHandler.getInstance());
        HANDLERS.put(MissionType.OFFICER_HUNT, com.warfront.mission.hard.OfficerHuntMissionHandler.getInstance());
        HANDLERS.put(MissionType.ARTILLERY_BATTERY, com.warfront.mission.hard.ArtilleryBatteryMissionHandler.getInstance());
        HANDLERS.put(MissionType.WAR_HORN_RELAY, com.warfront.mission.hard.WarHornRelayMissionHandler.getInstance());
        HANDLERS.put(MissionType.CUT_THE_SUPPLY_LINE, com.warfront.mission.hard.WarHornRelayMissionHandler.getInstance());

        // Defense Mission Handlers (Zero-Structure Defend Operations)
        HANDLERS.put(MissionType.DEFEAT_INVADING_FORCES, com.warfront.mission.defense.DefeatInvadingForcesMissionHandler.getInstance());
        HANDLERS.put(MissionType.HOLD_THE_CHOKEPOINT, com.warfront.mission.defense.HoldTheChokepointMissionHandler.getInstance());
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
