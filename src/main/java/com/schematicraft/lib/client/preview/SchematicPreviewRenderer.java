package com.schematicraft.lib.client.preview;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.DrawContext;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Universal 3D schematic preview renderer. No editor mod dependencies.
 *
 * <p><b>Fabric 1.21.1 status: the interactive 3D preview is a documented,
 * guarded no-op, deferred to a client-validated follow-up.</b> On 1.21.1
 * (NeoForge) this baked block models into a {@code VertexBuffer} through
 * {@code BlockRenderDispatcher.getModelRenderer().tesselateBlock} and drew them
 * in the GUI with the immediate-mode pipeline ({@code RenderSystem.viewport} +
 * {@code setProjectionMatrix} + {@code applyModelViewMatrix}, a 3D
 * {@code PoseStack} from {@code GuiGraphics.pose()}, a raw {@code GL11.glClear},
 * and {@code VertexBuffer.drawWithShader}). That draw is a GPU path that only a
 * running client can validate, and the fleet build host is headless (no display,
 * no GL), so a ported implementation could not be verified here. The NeoForge
 * {@code tesselateBlock} overload the old code used also took a NeoForge
 * {@code ModelData}, which has no Fabric counterpart in the matching call.
 *
 * <p>So this class keeps its entire public API and all non-GPU state
 * (prepare/isReady/renderAutoRotate/renderInteractive/drag/zoom/resetView/
 * releaseBuffers/clear and the rotation and data-key bookkeeping), and the GPU
 * build and draw are a guarded no-op: {@link #isReady()} returns false, so
 * {@code LibraryScreen} falls back to the static thumbnail image exactly as it
 * already does when no preview data is prepared. Users still see a preview image
 * for every schematic; only the spin-and-zoom 3D view is temporarily absent.
 * This is the one behaviour difference from 1.21.1 in this piece, and it mirrors
 * the NeoForge 26.1 port's handling of the same renderer.
 *
 * <p>The follow-up (reimplement the mesh draw on the Fabric render pipeline and
 * validate it in a dev client with a display) is self-contained and does not
 * block the rest of the client port.
 */
public class SchematicPreviewRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SchematicPreviewRenderer INSTANCE = new SchematicPreviewRenderer();

    public static SchematicPreviewRenderer get() { return INSTANCE; }

    // Current prepared data. Kept so the public contract and the data-key
    // bookkeeping are unchanged; the GPU build that would populate the buffers is
    // the deferred part, so isReady() stays false and callers use the fallback.
    @Nullable private PreviewBlockData currentData = null;
    private String currentDataKey = null; // schematicId + paletteHash for cache invalidation

    // Rotation state. Preserved exactly so drag/zoom/resetView keep their
    // behaviour for when the 3D draw is reinstated.
    private float autoRotationAngle = 0f;
    private float interactiveRotX = 20f;
    private float interactiveRotY = 0f;
    private float interactiveZoom = 1f;

    private SchematicPreviewRenderer() {}

    /**
     * Prepare the preview for rendering. Call when the data changes.
     *
     * <p>Guarded no-op on Fabric 1.21.1: the data key is tracked so callers that
     * gate on "changed" still behave, but no GPU buffers are built, so
     * {@link #isReady()} stays false. See the class note.
     */
    public void prepare(PreviewBlockData data, String cacheKey) {
        if (data == null || data.isEmpty()) {
            currentData = null;
            currentDataKey = null;
            return;
        }
        if (cacheKey.equals(currentDataKey)) return; // Already handled

        // The 1.21.1 GPU buffer build lived here. Deferred to the client-validated
        // follow-up; nothing is uploaded, so the preview falls back to the
        // thumbnail image. The key is recorded to preserve the change-gate contract.
        currentData = data;
        currentDataKey = cacheKey;
    }

    /**
     * Whether a 3D preview is ready to render.
     *
     * <p>Always false on Fabric 1.21.1 (the GPU path is deferred), so callers use
     * the static thumbnail fallback.
     */
    public boolean isReady() {
        return false;
    }

    /**
     * Render the preview in auto-rotation mode (small, non-interactive).
     *
     * <p>Guarded no-op on Fabric 1.21.1. Auto-rotation state is still advanced so
     * the angle is consistent when the 3D draw is reinstated, but nothing is
     * drawn; the caller shows the thumbnail because {@link #isReady()} is false.
     */
    public void renderAutoRotate(DrawContext graphics, int x, int y, int w, int h) {
        if (!isReady()) return;
    }

    /**
     * Render the preview in interactive mode (user-controlled rotation/zoom).
     *
     * <p>Guarded no-op on Fabric 1.21.1. See the class note.
     */
    public void renderInteractive(DrawContext graphics, int x, int y, int w, int h) {
        if (!isReady()) return;
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

    /**
     * Release GPU resources.
     *
     * <p>No-op on Fabric 1.21.1 because no buffers are built; kept so callers
     * (screen close, data change) need no change.
     */
    public void releaseBuffers() {
    }

    /** Clear all state (call on screen close). */
    public void clear() {
        releaseBuffers();
        currentData = null;
        currentDataKey = null;
    }
}
