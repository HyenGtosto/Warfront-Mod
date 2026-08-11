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

        // AI Warfare Algorithm Settings (Extensible for upcoming AI Attack System)
        public static final ModConfigSpec.IntValue AI_ATTACK_INTERVAL_TICKS;
        public static final ModConfigSpec.DoubleValue AI_EXPANSION_CHANCE;
        public static final ModConfigSpec.IntValue AI_MAX_SIMULTANEOUS_SIEGES;

        static {
                ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

                builder.comment("Procedural Region Generation Settings").push("procedural_generation");
                PILLAGER_SEPARATION = builder
                                .comment("Manhattan cell separation between Pillager conqueror base centers (higher = rarer bases)")
                                .defineInRange("pillager_separation", 12, 2, 32);

                ZOMBIE_SEPARATION = builder
                                .comment("Manhattan cell separation between Zombie horde base centers (higher = rarer bases)")
                                .defineInRange("zombie_separation", 12, 2, 32);

                FACTION_BUFFER_DISTANCE = builder
                                .comment("Minimum region buffer clearance distance enforced between different rival factions during procedural generation")
                                .defineInRange("faction_buffer_distance", 2, 0, 8);

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
                builder.pop();

                builder.comment("AI Warfare & Attack Algorithm Settings").push("ai_warfare");
                AI_ATTACK_INTERVAL_TICKS = builder
                                .comment("Interval in game ticks (20 ticks = 1 second) between AI faction attack evaluation cycles")
                                .defineInRange("ai_attack_interval_ticks", 6000, 600, 72000);

                AI_EXPANSION_CHANCE = builder
                                .comment("Probability during an evaluation cycle for an AI faction to launch an attack on a neighboring sub-region")
                                .defineInRange("ai_expansion_chance", 0.35D, 0.0D, 1.0D);

                AI_MAX_SIMULTANEOUS_SIEGES = builder
                                .comment("Maximum number of active simultaneous siege campaigns a single AI faction can manage")
                                .defineInRange("ai_max_simultaneous_sieges", 3, 1, 20);
                builder.pop();

                SPEC = builder.build();
        }

        private WarfrontConfig() {
        }
}
