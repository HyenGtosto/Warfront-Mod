package com.warfront.client.notification;

import com.warfront.client.events.StrategicEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public final class StrategicNotificationManager {
    private final Deque<StrategicEvent> eventQueue = new ArrayDeque<>();
    private final List<StrategicEvent> activeNotifications = new ArrayList<>();

    public void postEvent(StrategicEvent event) {
        if (event != null) {
            eventQueue.offer(event);
        }
    }

    public void tick() {
        while (!eventQueue.isEmpty()) {
            StrategicEvent event = eventQueue.poll();
            activeNotifications.add(event);
        }
    }

    public List<StrategicEvent> getActiveNotifications() {
        return Collections.unmodifiableList(activeNotifications);
    }

    public void clear() {
        eventQueue.clear();
        activeNotifications.clear();
    }
}
