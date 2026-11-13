package com.warfront.stim;

import com.warfront.Warfront;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Registry of NeoForge player data attachments for Warfront stim tracking.
 */
public final class WarfrontAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Warfront.MOD_ID);

    public static final Supplier<AttachmentType<StimState>> STIM_STATE = ATTACHMENT_TYPES.register(
            "stim_state",
            () -> AttachmentType.builder(StimState::new)
                    .serialize(StimState.CODEC)
                    .build()
    );

    private WarfrontAttachments() {
    }
}
