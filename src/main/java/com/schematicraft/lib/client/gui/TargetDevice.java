package com.schematicraft.lib.client.gui;

import java.util.Objects;

/**
 * Resolved download destination for one library journey.
 *
 * Target identifiers are strings registered by editor integrations. The shared
 * library therefore needs no editor enum and no editor class imports when a new
 * integration is added.
 */
public final class TargetDevice {
    public record Type(String id) {
        public Type {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Target id is required");
            }
        }

        public static Type of(String id) {
            return new Type(id);
        }
    }

    public enum Mode {
        SERVER,
        CLIENT_ONLY
    }

    public static final Type NONE = Type.of("schematicraft:none");

    private final Type type;
    private final Mode mode;

    private TargetDevice(Type type, Mode mode) {
        this.type = Objects.requireNonNull(type);
        this.mode = Objects.requireNonNull(mode);
    }

    public static TargetDevice of(Type type, Mode mode) {
        return new TargetDevice(type, mode);
    }

    public static TargetDevice none() {
        return new TargetDevice(NONE, Mode.CLIENT_ONLY);
    }

    public Type getType() { return type; }
    public Mode getMode() { return mode; }
    public boolean isAvailable() {
        return !NONE.equals(type) && TargetCatalog.get(type) != null;
    }

    private TargetCatalog.Entry entry() {
        return TargetCatalog.get(type);
    }

    /**
     * Display name of the destination.
     *
     * <p>Must match the {@code displayName()} of the matching upload source, so
     * "Download to" and "Upload from" name the same device the same way.
     *
     * <p>Falls back to readable text rather than an empty string, because this
     * value is interpolated into status and error messages.
     */
    public String getDisplayName() {
        TargetCatalog.Entry entry = entry();
        return entry != null ? entry.label() : "no target";
    }

    public String getLoadButtonText() {
        TargetCatalog.Entry entry = entry();
        return entry != null ? entry.loadButtonText() : "No target";
    }

    public String getDestinationHint() {
        TargetCatalog.Entry entry = entry();
        return entry != null ? entry.destinationHint() : "";
    }

    public String getLoadedLabel() {
        TargetCatalog.Entry entry = entry();
        return entry != null ? entry.loadedLabel() : "";
    }

    public String getDownloadFormat() {
        TargetCatalog.Entry entry = entry();
        return entry != null ? entry.downloadFormat() : "";
    }

    public String getDownloadEditor() {
        TargetCatalog.Entry entry = entry();
        return entry != null ? entry.downloadEditor() : "";
    }

    public String getModeLabel() {
        return mode == Mode.SERVER ? "Server" : "Client-only";
    }
}
