package com.schematicraft.lib.client.preview;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Universal 3D schematic preview renderer. No editor mod dependencies.
 * Uses vanilla Minecraft's BlockRenderDispatcher to bake block models into VBOs.
 *
 * Supports:
 * - Auto-rotation (small preview in LibraryScreen)
 * - Interactive rotation/zoom (expanded overlay)
 * - Palette-applied previews (swap block data, rebuild VBOs)
 *
 * VBO lifecycle: build once per PreviewBlockData instance, reuse across frames.
 * Rebuild when data changes (new schematic selected, palette applied).
 */
public class SchematicPreviewRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SchematicPreviewRenderer INSTANCE = new SchematicPreviewRenderer();

    public static SchematicPreviewRenderer get() { return INSTANCE; }

    // Current prepared data
    @Nullable private PreviewBlockData currentData = null;
    private String currentDataKey = null; // schematicId + paletteHash for cache invalidation
    private Map<RenderType, VertexBuffer> vertexBuffers = null;

    // Rotation state
    private float autoRotationAngle = 0f;
    private float interactiveRotX = 20f;
    private float interactiveRotY = 0f;
    private float interactiveZoom = 1f;

    private SchematicPreviewRenderer() {}

    /**
     * Prepare VBOs for rendering. Call when the data changes.
     * Safe to call every frame (no-ops if data hasn't changed).
     */
    public void prepare(PreviewBlockData data, String cacheKey) {
        if (data == null || data.isEmpty()) {
            currentData = null;
            currentDataKey = null;
            return;
        }

        if (cacheKey.equals(currentDataKey)) return; // Already prepared

        // Build VBOs from block data
        try {
            releaseBuffers();

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
            RandomSource random = RandomSource.create();

            // Create byte buffer builders and buffer builders per render type
            Map<RenderType, com.mojang.blaze3d.vertex.ByteBufferBuilder> byteBuilders = new HashMap<>();
            Map<RenderType, BufferBuilder> builders = new HashMap<>();

            for (PreviewBlockData.BlockEntry entry : data.blocks()) {
                BlockState state = entry.state();
                if (state.isAir()) continue;

                RenderType renderType = ItemBlockRenderTypes.getChunkRenderType(state);
                BufferBuilder builder = builders.computeIfAbsent(renderType, rt -> {
                    var byteBuf = new com.mojang.blaze3d.vertex.ByteBufferBuilder(rt.bufferSize());
                    byteBuilders.put(rt, byteBuf);
                    return new BufferBuilder(byteBuf, rt.mode(), rt.format());
                });

                BakedModel model = dispatcher.getBlockModel(state);
                PoseStack modelPose = new PoseStack();
                modelPose.translate(entry.pos().getX(), entry.pos().getY(), entry.pos().getZ());

                try {
                    dispatcher.getModelRenderer().tesselateBlock(
                            mc.level, model, state, entry.pos(), modelPose,
                            builder, false, random, state.getSeed(entry.pos()),
                            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                            model.getModelData(mc.level, entry.pos(), state,
                            net.neoforged.neoforge.client.model.data.ModelData.EMPTY),
                            renderType);
                } catch (Exception ignored) {
                    // Some blocks may fail to render (missing models, etc.)
                }
            }

            // Upload to VBOs
            Map<RenderType, VertexBuffer> newBuffers = new HashMap<>();
            for (var mapEntry : builders.entrySet()) {
                var meshData = mapEntry.getValue().build();
                if (meshData != null) {
                    VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    vb.bind();
                    vb.upload(meshData);
                    VertexBuffer.unbind();
                    newBuffers.put(mapEntry.getKey(), vb);
                }
            }

            // Close byte buffer builders
            for (var bb : byteBuilders.values()) {
                bb.close();
            }

            this.vertexBuffers = newBuffers;
            this.currentData = data;
            this.currentDataKey = cacheKey;

            LOGGER.debug("Built preview VBOs: {} blocks, {} render types",
                    data.blockCount(), newBuffers.size());

        } catch (Exception e) {
            LOGGER.warn("Failed to build preview VBOs: {}", e.getMessage());
            currentData = null;
            currentDataKey = null;
        }
    }

    /** Check if a preview is ready to render. */
    public boolean isReady() {
        return currentData != null && vertexBuffers != null && !vertexBuffers.isEmpty();
    }

    /**
     * Render the preview in auto-rotation mode (small, non-interactive).
     */
    public void renderAutoRotate(GuiGraphics graphics, int x, int y, int w, int h) {
        if (!isReady()) return;

        autoRotationAngle += 0.4f;
        if (autoRotationAngle >= 360f) autoRotationAngle -= 360f;

        renderInternal(graphics, x, y, w, h, 20f, autoRotationAngle, currentData.maxExtent() * 2.0f);
    }

    /**
     * Render the preview in interactive mode (user-controlled rotation/zoom).
     */
    public void renderInteractive(GuiGraphics graphics, int x, int y, int w, int h) {
        if (!isReady()) return;
        renderInternal(graphics, x, y, w, h, interactiveRotX, interactiveRotY,
                currentData.maxExtent() * 2.0f / interactiveZoom);
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

    /** Release VBO resources. */
    public void releaseBuffers() {
        if (vertexBuffers != null) {
            for (VertexBuffer vb : vertexBuffers.values()) {
                vb.close();
            }
            vertexBuffers = null;
        }
    }

    /** Clear all state (call on screen close). */
    public void clear() {
        releaseBuffers();
        currentData = null;
        currentDataKey = null;
    }

    // --- Internal rendering ---

    private void renderInternal(GuiGraphics graphics, int x, int y, int w, int h,
                                 float rotX, float rotY, float distance) {
        Minecraft mc = Minecraft.getInstance();

        try {
            double scale = mc.getWindow().getGuiScale();
            int vpX = (int) Math.round(x * scale);
            int vpY = (int) Math.round(mc.getWindow().getHeight() - (y + h) * scale);
            int vpW = (int) Math.round(w * scale);
            int vpH = (int) Math.round(h * scale);

            RenderSystem.viewport(vpX, vpY, vpW, vpH);
            RenderSystem.backupProjectionMatrix();

            Matrix4f proj = new Matrix4f();
            proj.setPerspective((float) Math.toRadians(60), (float) w / h, 0.1f, 1000f);
            RenderSystem.setProjectionMatrix(proj, VertexSorting.ORTHOGRAPHIC_Z);

            PoseStack ps = graphics.pose();
            ps.pushPose();
            ps.setIdentity();

            ps.translate(0, 0, -distance);
            ps.mulPose(new Quaternionf()
                    .rotateX((float) Math.toRadians(rotX))
                    .rotateY((float) Math.toRadians(rotY)));
            ps.translate(-currentData.midX(), -currentData.midY(), -currentData.midZ());

            RenderSystem.applyModelViewMatrix();
            org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT);

            // Draw each render type
            for (var entry : vertexBuffers.entrySet()) {
                RenderType rt = entry.getKey();
                VertexBuffer vb = entry.getValue();
                if (vb.getFormat() == null) continue;

                rt.setupRenderState();
                vb.bind();
                vb.drawWithShader(ps.last().pose(), RenderSystem.getProjectionMatrix(),
                        RenderSystem.getShader());
                VertexBuffer.unbind();
                rt.clearRenderState();
            }

            ps.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
            RenderSystem.restoreProjectionMatrix();

        } catch (Exception e) {
            // Restore viewport on any error
            RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
            RenderSystem.restoreProjectionMatrix();
        }
    }
}
