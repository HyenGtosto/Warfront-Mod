package com.warfront.faction;

public enum FactionMessageType {
    CHAT(0),
    SYSTEM(1),
    JOIN_REQUEST(2);

    private final int id;

    FactionMessageType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static FactionMessageType byId(int id) {
        for (FactionMessageType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return CHAT;
    }
}
