package dev.nitro.aibuild.core.plan;

/** A single resolved block write: one position, one state. */
public record Placement(Pos pos, BlockSpec block) {}
