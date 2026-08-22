package com.warfront.mission;

/**
 * Defines the category of player objective a mission represents.
 * Adding a new mission type later requires only a new constant here
 * and a corresponding case in MissionProfile.
 */
public enum MissionType {
    /** Eliminate a required number of enemy units within the subregion. */
    KILL_COUNT
}
