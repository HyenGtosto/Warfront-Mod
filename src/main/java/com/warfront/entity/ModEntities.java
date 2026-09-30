package com.warfront.entity;

import com.warfront.Warfront;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Warfront.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<PillagerArmoredEliteEntity>> PILLAGER_ARMORED_ELITE =
            ENTITY_TYPES.register("pillager_armored_elite", () ->
                    EntityType.Builder.of(PillagerArmoredEliteEntity::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(8)
                            .build("pillager_armored_elite"));

    public static final DeferredHolder<EntityType<?>, EntityType<PillagerWarriorEntity>> PILLAGER_WARRIOR =
            ENTITY_TYPES.register("pillager_warrior", () ->
                    EntityType.Builder.of(PillagerWarriorEntity::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(8)
                            .build("pillager_warrior"));

    public static final DeferredHolder<EntityType<?>, EntityType<PillagerScoutEntity>> PILLAGER_SCOUT =
            ENTITY_TYPES.register("pillager_scout", () ->
                    EntityType.Builder.of(PillagerScoutEntity::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(8)
                            .build("pillager_scout"));

    public static final DeferredHolder<EntityType<?>, EntityType<PillagerMarksmanEntity>> PILLAGER_MARKSMAN =
            ENTITY_TYPES.register("pillager_marksman", () ->
                    EntityType.Builder.of(PillagerMarksmanEntity::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(8)
                            .build("pillager_marksman"));

    public static final DeferredHolder<EntityType<?>, EntityType<PillagerCommanderEntity>> PILLAGER_COMMANDER =
            ENTITY_TYPES.register("pillager_commander", () ->
                    EntityType.Builder.of(PillagerCommanderEntity::new, MobCategory.MONSTER)
                            .sized(0.6f, 1.95f)
                            .clientTrackingRange(8)
                            .build("pillager_commander"));

    private ModEntities() {
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(ModEntities::registerAttributes);
    }

    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(PILLAGER_ARMORED_ELITE.get(), PillagerArmoredEliteEntity.createAttributes().build());
        event.put(PILLAGER_WARRIOR.get(), PillagerWarriorEntity.createAttributes().build());
        event.put(PILLAGER_SCOUT.get(), PillagerScoutEntity.createAttributes().build());
        event.put(PILLAGER_MARKSMAN.get(), PillagerMarksmanEntity.createAttributes().build());
        event.put(PILLAGER_COMMANDER.get(), PillagerCommanderEntity.createAttributes().build());
    }
}
