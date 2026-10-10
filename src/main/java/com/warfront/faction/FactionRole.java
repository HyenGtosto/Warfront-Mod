package com.warfront.faction;

public enum FactionRole {
    LEADER,
    MEMBER;

    public boolean isLeader() {
        return this == LEADER;
    }
}
