package com.schematicraft.lib.client.preview;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * Universal 3D schematic preview renderer. No editor mod dependencies.
 *
 * <p><b>NeoForge 26.1 port status.</b> On 1.21.1 this class baked block models
 * into VBOs with {@code BlockRenderDispatcher.getModelRenderer().tesselateBlock},
 * uploaded them to {@code com.mojang.blaze3d.vertex.VertexBuffer}, and drew them
 * inside the GUI with the immediate-mode pipeline
 * ({@code RenderSystem.getShader} / {@code applyModelViewMatrix} /
 * {@code VertexBuffer.drawWithShader}, a 3D {@code PoseStack} from
 * {@code GuiGraphics.pose()}, {@code RenderSystem.viewport}, {@code GL11.glClear}).
 *
 * <p>26.1 removed every piece of that path: {@code VertexBuffer} is gone, the
 * {@code RenderSystem} shader/model-view/draw-with-shader methods are gone, and
 * {@code GuiGraphicsExtractor.pose()} is a 2D {@code Matrix3x2fStack} with no Z
 * axis. Drawing an arbitrary block mesh inside a GUI in 26.1 goes through the new
 * render-pipeline / picture-in-picture render-state system, which is a design
 * rewrite that cannot be validated without a running client, and the fleet host
 * is headless (no display, no GL). So this port keeps the whole public API and
 * all the non-GPU state (prepare/isReady/drag/zoom/resetView/clear and the data
 * key bookkeeping) behaving as before, and turns the GPU build and draw into a
 * guarded no-op: {@link #isReady()} returns false, so callers skip the 3D draw
 * and fall back to the static thumbnail-image preview, exactly as they already do
 * when no preview data is prepared. The interactive 3D preview is deferred to a
 * follow-up that rebuilds it on the 26.1 pipeline and is checked in a dev client.
 * This is called out in the piece note; no other 3a behaviour changes.
 */
public class SchematicPreviewRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SchematicPreviewRenderer INSTANCE = new SchematicPreviewRenderer();

    public static SchematicPreviewRenderer get() { return INSTANCE; }

    // Current prepared data
    @Nullable private PreviewBlockData currentData = null;
    private String currentDataKey = null; // schematicId + paletteHash for cache invalidation

    // Rotation state (preserved so the follow-up 3D path needs no caller change)
    private float autoRotationAngle = 0f;
    private float interactiveRotX = 20f;
    private float interactiveRotY = 0f;
    private float interactiveZoom = 1f;

    private SchematicPreviewRenderer() {}

    /**
     * Prepare the preview for rendering. Call when the data changes.
     * Safe to call every frame (no-ops if data hasn't changed).
     *
     * <p>The 26.1 GPU mesh build is deferred (see the class note), so this records
     * the current data and key without building VBOs. The bookkeeping is kept so
     * that when the 26.1 draw path is implemented it slots in here unchanged.
     */
    public void prepare(PreviewBlockData data, String cacheKey) {
        if (data == null || data.isEmpty()) {
            currentData = null;
            currentDataKey = null;
            return;
        }

        if (cacheKey.equals(currentDataKey)) return; // Already prepared

        this.currentData = data;
        this.currentDataKey = cacheKey;
    }

    /**
     * Whether an interactive 3D preview is ready to render.
     *
     * <p>Returns false on 26.1 until the GPU draw path is reimplemented, so
     * callers fall back to the static thumbnail preview instead of drawing a
     * blank or broken frame.
     */
    public boolean isReady() {
        return false;
    }

    /**
     * Render the preview in auto-rotation mode (small, non-interactive).
     * No-op on 26.1 until the GPU draw path is reimplemented.
     */
    public void renderAutoRotate(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        if (!isReady()) return;

        autoRotationAngle += 0.4f;
        if (autoRotationAngle >= 360f) autoRotationAngle -= 360f;
        // 26.1 3D draw deferred; see class note.
    }

    /**
     * Render the preview in interactive mode (user-controlled rotation/zoom).
     * No-op on 26.1 until the GPU draw path is reimplemented.
     */
    public void renderInteractive(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        if (!isReady()) return;
        // 26.1 3D draw deferred; see class note.
    }

    /** Apply mouse drag to interactive rotation. */
    public void drag(float dx, float dy) {
        interactiveRotY += dx * 0.5f;
        interactiveRotX += dy * 0.5f;
        interactiveRotX = Math.max(-90f, Math.min(90f, interactiveRotX));
    }

    /** Apply scroll to interactive zoom. */
    public void zoom(float delta) {
        interactiveZoom *= (1f + delta * 0.1f);
        interactiveZoom = Math.max(0.2f, Math.min(5f, interactiveZoom));
    }

    /** Reset interactive view to defaults. */
    public void resetView() {
        interactiveRotX = 20f;
        interactiveRotY = 0f;
        interactiveZoom = 1f;
    }

    /** Release GPU resources. No-op on 26.1 (nothing is allocated yet). */
    public void releaseBuffers() {
        // No GPU resources are held on 26.1 until the draw path is reimplemented.
    }

    /** Clear all state (call on screen close). */
    public void clear() {
        releaseBuffers();
        currentData = null;
        currentDataKey = null;
    }
}
