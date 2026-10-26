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

    public static final DeferredHolder<EntityType<?>, EntityType<PillagerEliteEntity>> PILLAGER_ELITE =
            ENTITY_TYPES.register("pillager_elite", () ->
                    EntityType.Builder.of(PillagerEliteEntity::new, MobCategory.MONSTER)
                            .sized(0.85f, 2.6f)
                            .clientTrackingRange(10)
                            .build("pillager_elite"));

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

    public static final DeferredHolder<EntityType<?>, EntityType<SupplyWagonCartEntity>> SUPPLY_WAGON_CART =
            ENTITY_TYPES.register("supply_wagon_cart", () ->
                    EntityType.Builder.of(SupplyWagonCartEntity::new, MobCategory.MISC)
                            .sized(2.4f, 2.8f)
                            .clientTrackingRange(10)
                            .build("supply_wagon_cart"));

    public static final DeferredHolder<EntityType<?>, EntityType<SupplyWagonExtensionEntity>> SUPPLY_WAGON_EXTENSION =
            ENTITY_TYPES.register("supply_wagon_extension", () ->
                    EntityType.Builder.of(SupplyWagonExtensionEntity::new, MobCategory.MISC)
                            .sized(2.4f, 2.8f)
                            .clientTrackingRange(10)
                            .build("supply_wagon_extension"));

    public static final DeferredHolder<EntityType<?>, EntityType<ArtilleryShellEntity>> ARTILLERY_SHELL =
            ENTITY_TYPES.register("artillery_shell", () ->
                    EntityType.Builder.<ArtilleryShellEntity>of(ArtilleryShellEntity::new, MobCategory.MISC)
                            .sized(0.8f, 0.8f)
                            .clientTrackingRange(20)
                            .updateInterval(1)
                            .build("artillery_shell"));

    public static final DeferredHolder<EntityType<?>, EntityType<PillagerCannoneerEntity>> PILLAGER_CANNONEER =
            ENTITY_TYPES.register("pillager_cannoneer", () ->
                    EntityType.Builder.of(PillagerCannoneerEntity::new, MobCategory.MONSTER)
                            .sized(0.65f, 1.95f)
                            .clientTrackingRange(8)
                            .build("pillager_cannoneer"));

    public static final DeferredHolder<EntityType<?>, EntityType<PillagerRapidbowEntity>> PILLAGER_RAPIDBOW =
            ENTITY_TYPES.register("pillager_rapidbow", () ->
                    EntityType.Builder.of(PillagerRapidbowEntity::new, MobCategory.MONSTER)
                            .sized(0.85f, 1.95f)
                            .clientTrackingRange(8)
                            .build("pillager_rapidbow"));

    public static final DeferredHolder<EntityType<?>, EntityType<PillagerIroncladEntity>> PILLAGER_IRONCLAD =
            ENTITY_TYPES.register("pillager_ironclad", () ->
                    EntityType.Builder.of(PillagerIroncladEntity::new, MobCategory.MONSTER)
                            .sized(1.2f, 3.0f)
                            .clientTrackingRange(10)
                            .build("pillager_ironclad"));

    public static final DeferredHolder<EntityType<?>, EntityType<CannonballEntity>> CANNONBALL =
            ENTITY_TYPES.register("cannonball", () ->
                    EntityType.Builder.<CannonballEntity>of(CannonballEntity::new, MobCategory.MISC)
                            .sized(0.5f, 0.5f)
                            .clientTrackingRange(16)
                            .updateInterval(1)
                            .build("cannonball"));

    private ModEntities() {
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(ModEntities::registerAttributes);
    }

    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(PILLAGER_ELITE.get(), PillagerEliteEntity.createAttributes().build());
        event.put(PILLAGER_WARRIOR.get(), PillagerWarriorEntity.createAttributes().build());
        event.put(PILLAGER_SCOUT.get(), PillagerScoutEntity.createAttributes().build());
        event.put(PILLAGER_MARKSMAN.get(), PillagerMarksmanEntity.createAttributes().build());
        event.put(PILLAGER_COMMANDER.get(), PillagerCommanderEntity.createAttributes().build());
        event.put(PILLAGER_CANNONEER.get(), PillagerCannoneerEntity.createAttributes().build());
        event.put(PILLAGER_RAPIDBOW.get(), PillagerRapidbowEntity.createAttributes().build());
        event.put(PILLAGER_IRONCLAD.get(), PillagerIroncladEntity.createAttributes().build());
        event.put(SUPPLY_WAGON_CART.get(), SupplyWagonCartEntity.createAttributes().build());
        event.put(SUPPLY_WAGON_EXTENSION.get(), SupplyWagonExtensionEntity.createAttributes().build());
    }
}
