package com.warfront.client.animation;

public final class MapAnimationManager {
    private boolean animating = false;

    public boolean isAnimating() {
        return animating;
    }

    public void tick(float partialTick) {
    }

    public void stopAnimations() {
        this.animating = false;
    }
}
