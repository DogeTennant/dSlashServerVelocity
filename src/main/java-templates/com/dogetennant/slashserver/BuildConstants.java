package com.dogetennant.slashserver;

/**
 * Build-time constants, generated from src/main/java-templates by the
 * templating-maven-plugin. Never edit the copy under target/.
 *
 * Velocity's @Plugin annotation needs a compile-time constant for the version,
 * which is why this exists instead of a filtered resource.
 */
public final class BuildConstants {

    /** The Maven project version, e.g. "1.0.0". */
    public static final String VERSION = "${project.version}";

    private BuildConstants() {
    }
}
