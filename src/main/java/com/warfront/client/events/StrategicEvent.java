package com.warfront.client.events;

import net.minecraft.network.chat.Component;

public record StrategicEvent(
        String id,
        String type,
        Component title,
        Component message,
        long timestampMs
) {
}
