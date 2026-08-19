package com.warfront.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class WarfrontConfig {
        public static final ModConfigSpec SPEC;

        // Procedural Generation Settings
        public static final ModConfigSpec.IntValue PILLAGER_SEPARATION;
        public static final ModConfigSpec.IntValue ZOMBIE_SEPARATION;
        public static final ModConfigSpec.IntValue FACTION_BUFFER_DISTANCE;
        public static final ModConfigSpec.DoubleValue MEGA_BASE_CHANCE;

        // Sub-Region & Siege Settings
        public static final ModConfigSpec.IntValue SUB_REGION_DOMINO_THRESHOLD;
        public static final ModConfigSpec.IntValue CHUNK_TRIGGER_COOLDOWN_SECONDS;
        public static final ModConfigSpec.IntValue SIEGE_RESOLUTION_DURATION_SECONDS;

        // AI Warfare Algorithm Settings (Extensible for upcoming AI Attack System)
        public static final ModConfigSpec.IntValue AI_ATTACK_INTERVAL_TICKS;
        public static final ModConfigSpec.DoubleValue AI_EXPANSION_CHANCE;
        public static final ModConfigSpec.IntValue AI_MAX_SIMULTANEOUS_SIEGES;

        // Roaming Enemy AI Activation Settings
        public static final ModConfigSpec.IntValue ROAMING_AI_ACTIVATION_RADIUS;
        public static final ModConfigSpec.IntValue ROAMING_AI_DEACTIVATION_RADIUS;
        public static final ModConfigSpec.IntValue SUBREGION_SPAWN_COOLDOWN_SECONDS;

        static {
                ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

                builder.comment("Procedural Region Generation Settings").push("procedural_generation");
                PILLAGER_SEPARATION = builder
                                .comment("Manhattan cell separation between Pillager conqueror base centers (higher = rarer bases)")
                                .defineInRange("pillager_separation", 16, 8, 32);

                ZOMBIE_SEPARATION = builder
                                .comment("Manhattan cell separation between Zombie horde base centers (higher = rarer bases)")
                                .defineInRange("zombie_separation", 16, 8, 32);

                FACTION_BUFFER_DISTANCE = builder
                                .comment("Minimum region buffer clearance distance enforced between different rival factions during procedural generation")
                                .defineInRange("faction_buffer_distance", 3, 1, 8);

                MEGA_BASE_CHANCE = builder
                                .comment("Probability (~15%) for an enemy cluster center to generate as a Mega Variant (Mega Command Center / Heart of Infection)")
                                .defineInRange("mega_base_chance", 0.15D, 0.0D, 1.0D);
                builder.pop();

                builder.comment("Sub-Region Sector Warfare & Siege Settings").push("sub_region_warfare");
                SUB_REGION_DOMINO_THRESHOLD = builder
                                .comment("Number of sub-region sectors (out of 4) a faction must capture to trigger full region domino collapse")
                                .defineInRange("sub_region_domino_threshold", 3, 1, 4);

                CHUNK_TRIGGER_COOLDOWN_SECONDS = builder
                                .comment("Cooldown (in seconds) before entering the same chunk in hostile territory triggers another enemy spawn event")
                                .defineInRange("chunk_trigger_cooldown_seconds", 60, 5, 3600);

                SIEGE_RESOLUTION_DURATION_SECONDS = builder
                                .comment("Duration (in seconds) of active siege resolution timers before campaigns expire")
                                .defineInRange("siege_resolution_duration_seconds", 5, 1, 3600);
                builder.pop();

                builder.comment("AI Warfare & Attack Algorithm Settings").push("ai_warfare");
                AI_ATTACK_INTERVAL_TICKS = builder
                                .comment("Interval in game ticks (20 ticks = 1 second) between AI faction attack evaluation cycles")
                                .defineInRange("ai_attack_interval_ticks", 200, 20, 72000);

                AI_EXPANSION_CHANCE = builder
                                .comment("Probability during an evaluation cycle for an AI faction to launch an attack on a neighboring sub-region")
                                .defineInRange("ai_expansion_chance", 0.35D, 0.0D, 1.0D);

                AI_MAX_SIMULTANEOUS_SIEGES = builder
                                .comment("Maximum number of active simultaneous siege campaigns a single AI faction can manage")
                                .defineInRange("ai_max_simultaneous_sieges", 3, 1, 20);
                builder.pop();

                builder.comment("Roaming Enemy AI Activation Settings").push("roaming_enemies");
                ROAMING_AI_ACTIVATION_RADIUS = builder
                                .comment("Block distance at which a Warfront roaming enemy's AI activates when a player approaches")
                                .defineInRange("roaming_ai_activation_radius", 48, 8, 128);

                ROAMING_AI_DEACTIVATION_RADIUS = builder
                                .comment("Block distance beyond which a Warfront roaming enemy's AI deactivates when all players move away. Must be > activation radius to prevent hysteresis oscillation.")
                                .defineInRange("roaming_ai_deactivation_radius", 64, 8, 256);

                SUBREGION_SPAWN_COOLDOWN_SECONDS = builder
                                .comment("Cooldown (in seconds) before the same subregion can spawn another exploration enemy encounter")
                                .defineInRange("subregion_spawn_cooldown_seconds", 8, 1, 300);
                builder.pop();

                SPEC = builder.build();
        }

        private WarfrontConfig() {
        }
}
