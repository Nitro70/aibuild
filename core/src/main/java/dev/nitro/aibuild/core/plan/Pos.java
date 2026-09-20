package dev.nitro.aibuild.core.plan;

/** An integer block position. Used for both local (plan) and world coordinates. */
public record Pos(int x, int y, int z) {

    public Pos plus(Pos other) {
        return new Pos(x + other.x, y + other.y, z + other.z);
    }

    public Pos plus(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
