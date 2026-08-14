package com.warfront.ai.strategy;

import com.warfront.region.Faction;

import java.util.List;
import java.util.Optional;

/**
 * Strategy interface for faction-specific strategic target selection on the world region map.
 */
public interface FactionAttackStrategy {
    /**
     * Chooses a strategic attack target for the faction given valid candidate options.
     *
     * @param faction         The attacking faction
     * @param context         The evaluation context (regions data, level, random, etc.)
     * @param validCandidates Pre-filtered candidate region links that satisfy shared preconditions
     * @return The chosen attack target, or empty if no attack should be launched
     */
    Optional<AttackTarget> chooseAttackTarget(Faction faction, AttackContext context, List<AttackCandidate> validCandidates);
}
