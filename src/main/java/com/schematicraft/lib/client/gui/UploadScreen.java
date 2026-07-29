package com.schematicraft.lib.client.gui;

import com.mojang.logging.LogUtils;
import com.schematicraft.lib.client.CameraMode;
import com.schematicraft.lib.core.LibraryState;
import com.schematicraft.lib.network.SchematiCraftAPIWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Upload form for a build captured in the active editor.
 *
 * Editor-agnostic: the list of uploadable builds and the upload itself come
 * from the registered {@link UploadSource}. This screen owns only the metadata
 * (title, description, bundle) and screenshot capture.
 */
public class UploadScreen extends Screen implements SchematicraftScreen {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int FIELD_W = 240;
    private static final int ROW_H = 22;

    private final UploadSource source;
    private final EditorJourney journey;
    private final List<UploadSource.Candidate> candidates;

    private int candidateIndex = 0;
    private int bundleIndex = 0;
    private final List<Path> images = new ArrayList<>();

    private EditBox titleField;
    private EditBox descField;
    private Button candidateButton;
    private Button bundleButton;
    private Button saveButton;

    private String statusText = "";
    private int statusColor = GuiColors.TEXT_DIM;
    private boolean uploading = false;

    // Bundle dropdown. Minecraft has no combo box, so this is a plain popup list
    // drawn over the form and dismissed by clicking anywhere else.
    private static final int DROP_ROW_H = 14;
    private static final int DROP_MAX_ROWS = 8;
    private boolean dropdownOpen = false;
    private int dropdownX, dropdownY;
    private int dropdownScroll = 0;

    // Camera round-trip state, static so it survives the screen close/reopen.
    private static boolean pendingReopen = false;
    private static List<Path> pendingImages = null;
    private static String pendingTitle = null;
    private static String pendingDesc = null;
    private static int pendingCandidateIndex = 0;
    private static int pendingBundleIndex = 0;

    public UploadScreen(UploadSource source, EditorJourney journey) {
        super(Component.literal("Upload Build"));
        this.source = source;
        this.journey = journey;
        this.candidates = new ArrayList<>(source.listCandidates());
    }

    @Override
    protected void init() {
        super.init();

        // Restore state after returning from camera mode
        if (pendingReopen) {
            pendingReopen = false;
            if (pendingImages != null) images.addAll(pendingImages);
            candidateIndex = Math.min(pendingCandidateIndex, Math.max(0, candidates.size() - 1));
            bundleIndex = pendingBundleIndex;
            pendingImages = null;
        }

        int centerX = this.width / 2;
        int x = centerX - FIELD_W / 2;
        int y = 50;

        titleField = new EditBox(this.font, x, y, FIELD_W, 18, Component.literal(""));
        titleField.setMaxLength(200);
        titleField.setHint(Component.literal("Title (required)"));
        if (pendingTitle != null) {
            titleField.setValue(pendingTitle);
            pendingTitle = null;
        } else if (!candidates.isEmpty()) {
            titleField.setValue("");
        }
        this.addRenderableWidget(titleField);
        this.setInitialFocus(titleField);
        y += ROW_H;

        descField = new EditBox(this.font, x, y, FIELD_W, 18, Component.literal(""));
        descField.setMaxLength(500);
        descField.setHint(Component.literal("Description (optional)"));
        if (pendingDesc != null) {
            descField.setValue(pendingDesc);
            pendingDesc = null;
        }
        this.addRenderableWidget(descField);
        y += ROW_H + 4;

        // Which build to upload
        candidateButton = Button.builder(Component.literal(candidateLabel()),
                b -> {
                    if (candidates.size() > 1) {
                        candidateIndex = (candidateIndex + 1) % candidates.size();
                        b.setMessage(Component.literal(candidateLabel()));
                    }
                })
                .bounds(x, y, FIELD_W, 18).build();
        candidateButton.active = candidates.size() > 1;
        this.addRenderableWidget(candidateButton);
        y += ROW_H;

        // Bundle selector, opens a plain dropdown list
        bundleButton = Button.builder(Component.literal(bundleLabel() + " \u25be"),
                b -> setDropdownOpen(!dropdownOpen))
                .bounds(x, y, FIELD_W, 18).build();
        this.addRenderableWidget(bundleButton);
        dropdownX = x;
        dropdownY = y + 18;
        y += ROW_H;

        // Screenshots
        String camLabel = images.isEmpty()
                ? "Take screenshots"
                : images.size() + (images.size() == 1 ? " screenshot" : " screenshots");
        this.addRenderableWidget(Button.builder(Component.literal(camLabel),
                b -> enterCameraMode())
                .bounds(x, y, FIELD_W, 18).build());
        y += ROW_H + 6;

        saveButton = Button.builder(Component.literal("Upload"), b -> doUpload())
                .bounds(x, y, FIELD_W / 2 - 2, 20).build();
        this.addRenderableWidget(saveButton);

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"),
                b -> Minecraft.getInstance().setScreen(new LibraryScreen(journey)))
                .bounds(x + FIELD_W / 2 + 2, y, FIELD_W / 2 - 2, 20).build());

        if (candidates.isEmpty()) {
            saveButton.active = false;
            statusText = source.emptyHint();
            statusColor = GuiColors.WARNING;
        }
    }

    private String candidateLabel() {
        if (candidates.isEmpty()) return "Nothing to upload";
        UploadSource.Candidate c = candidates.get(candidateIndex);
        String label = c.label();
        if (c.detail() != null) label += "  " + c.detail();
        if (candidates.size() > 1) {
            label += "  (" + (candidateIndex + 1) + "/" + candidates.size() + ")";
        }
        return label;
    }

    private String bundleLabel() {
        List<LibraryState.BundleOption> opts = LibraryState.get().getBundleOptions();
        if (opts.isEmpty() || bundleIndex >= opts.size()) return "Bundle: Unbundled";
        return "Bundle: " + opts.get(bundleIndex).name();
    }

    private void enterCameraMode() {
        pendingReopen = true;
        pendingImages = new ArrayList<>(images);
        pendingTitle = titleField.getValue();
        pendingDesc = descField.getValue();
        pendingCandidateIndex = candidateIndex;
        pendingBundleIndex = bundleIndex;

        this.minecraft.setScreen(null);
        CameraMode.start(pendingImages, () ->
                Minecraft.getInstance().execute(() ->
                        Minecraft.getInstance().setScreen(new UploadScreen(source, journey))));
    }

    private void doUpload() {
        // Belt and braces: never upload as a side effect of a dropdown click,
        // whichever way the event reached us.
        if (dropdownOpen) return;
        if (uploading || candidates.isEmpty()) return;

        String title = titleField.getValue().trim();
        if (title.isEmpty()) {
            statusText = "Title required";
            statusColor = GuiColors.ERROR;
            return;
        }

        List<LibraryState.BundleOption> opts = LibraryState.get().getBundleOptions();
        String bundleId = bundleIndex < opts.size() ? opts.get(bundleIndex).id() : null;

        uploading = true;
        saveButton.active = false;
        statusText = "Uploading...";
        statusColor = GuiColors.INFO;

        source.upload(candidates.get(candidateIndex).id(), title,
                        descField.getValue().trim(), bundleId, new ArrayList<>(images))
                .thenAccept(isDuplicate -> Minecraft.getInstance().execute(() -> {
                    uploading = false;

                    // Done, so leave the form and report the outcome on the library
                    // screen. Nothing useful remains here once the upload lands.
                    LibraryScreen.queueStatus(
                            isDuplicate
                                    ? "Already in your library, skipped: " + title
                                    : "Uploaded: " + title,
                            isDuplicate ? GuiColors.WARNING : GuiColors.SUCCESS,
                            4000);

                    // Clear the round-trip state so reopening the form starts fresh
                    // rather than restoring this upload's title and screenshots.
                    clearPendingState();

                    SchematiCraftAPIWrapper.get().refreshLibrary();
                    Minecraft.getInstance().setScreen(new LibraryScreen(journey));
                }))
                .exceptionally(ex -> {
                    Minecraft.getInstance().execute(() -> {
                        uploading = false;
                        saveButton.active = true;
                        statusText = "Upload failed: " + SchematiCraftAPIWrapper.rootMessage(ex);
                        statusColor = GuiColors.ERROR;
                        LOGGER.warn("Upload failed", ex);
                    });
                    return null;
                });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, GuiColors.SCREEN_BG);

        graphics.fill(0, 0, this.width, 30, GuiColors.PANEL_BG);
        graphics.fill(0, 29, this.width, 30, GuiColors.BORDER_SEPARATOR);
        graphics.drawString(this.font, "Upload from " + source.displayName(), 8, 6,
                GuiColors.SELECTED, false);
        graphics.drawString(this.font, "Shared publicly only if you choose to on the website",
                8, 18, GuiColors.TEXT_DIM, false);

        super.render(graphics, mouseX, mouseY, partialTick);

        if (!statusText.isEmpty()) {
            graphics.drawCenteredString(this.font, statusText,
                    this.width / 2, this.height - 30, statusColor);
        }

        // Drawn last so it sits above the form.
        renderDropdown(graphics, mouseX, mouseY);
    }

    /**
     * Opens or closes the dropdown, and makes the form buttons inert while it is
     * open.
     *
     * The dropdown overlaps the buttons below it, and relying on click ordering
     * alone proved unreliable: a click on a row could still reach the Upload
     * button underneath and start an upload. Deactivating the buttons removes the
     * ambiguity at the source, so no click can reach a control the user cannot
     * see. The bundle button stays active so it can toggle the list closed.
     */
    private void setDropdownOpen(boolean open) {
        dropdownOpen = open;
        if (!open) dropdownScroll = 0;
        for (var child : this.children()) {
            if (child instanceof Button b && b != bundleButton) {
                b.active = !open;
            }
        }
        // Upload stays disabled while uploading regardless of the dropdown.
        if (!open && uploading && saveButton != null) {
            saveButton.active = false;
        }
        // The candidate cycler is only meaningful with more than one copy.
        if (!open && candidateButton != null) {
            candidateButton.active = candidates.size() > 1;
        }
    }

    /** Rows shown in the dropdown: every bundle, then a create row. */
    private List<String> dropdownRows() {
        List<String> rows = new ArrayList<>();
        for (LibraryState.BundleOption opt : LibraryState.get().getBundleOptions()) {
            rows.add(opt.name());
        }
        rows.add("+ New bundle");
        return rows;
    }

    private void renderDropdown(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!dropdownOpen) return;

        List<String> rows = dropdownRows();
        int visible = Math.min(rows.size(), DROP_MAX_ROWS);
        int h = visible * DROP_ROW_H + 2;

        // Text is batched and flushed at the end of the frame, so button labels
        // drawn earlier would otherwise appear on top of this panel even though it
        // is drawn later. Flush the pending text, then raise the Z layer so both
        // the panel and its own text sit above the form.
        graphics.flush();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 300);

        // Opaque so the form behind it does not bleed through the list.
        graphics.fill(dropdownX, dropdownY, dropdownX + FIELD_W, dropdownY + h,
                GuiColors.BOTTOM_BAR_BG);
        drawBorder(graphics, dropdownX, dropdownY, FIELD_W, h, GuiColors.BORDER_SEPARATOR);

        for (int i = 0; i < visible; i++) {
            int rowIdx = i + dropdownScroll;
            if (rowIdx >= rows.size()) break;

            int rowY = dropdownY + 1 + i * DROP_ROW_H;
            boolean hovered = isOver(mouseX, mouseY, dropdownX, rowY, FIELD_W, DROP_ROW_H);
            boolean isCreateRow = rowIdx == rows.size() - 1;
            boolean isSelected = !isCreateRow && rowIdx == bundleIndex;

            if (hovered) {
                graphics.fill(dropdownX + 1, rowY, dropdownX + FIELD_W - 1,
                        rowY + DROP_ROW_H, GuiColors.TILE_HOVER_BG);
            }

            int color = isCreateRow ? GuiColors.INFO
                    : (isSelected ? GuiColors.SELECTED : GuiColors.TEXT_PRIMARY);
            graphics.drawString(this.font, rows.get(rowIdx),
                    dropdownX + 4, rowY + 3, color, false);
        }

        if (rows.size() > DROP_MAX_ROWS) {
            graphics.drawString(this.font, "\u00a78scroll",
                    dropdownX + FIELD_W - 30, dropdownY + h - 10, GuiColors.TEXT_DIM, false);
        }

        // Flush while still translated so this panel's text lands on the raised
        // layer rather than being deferred back down to the default one.
        graphics.flush();
        graphics.pose().popPose();
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static void drawBorder(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y, x + 1, y + h, color);
        graphics.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (dropdownOpen) {
            List<String> rows = dropdownRows();
            int visible = Math.min(rows.size(), DROP_MAX_ROWS);

            for (int i = 0; i < visible; i++) {
                int rowIdx = i + dropdownScroll;
                if (rowIdx >= rows.size()) break;
                int rowY = dropdownY + 1 + i * DROP_ROW_H;
                if (isOver((int) mouseX, (int) mouseY, dropdownX, rowY, FIELD_W, DROP_ROW_H)) {
                    setDropdownOpen(false);
                    if (rowIdx == rows.size() - 1) {
                        // Create row: keep the form state by coming back to a fresh
                        // screen with the new bundle preselected.
                        openNewBundle();
                    } else {
                        bundleIndex = rowIdx;
                        bundleButton.setMessage(Component.literal(bundleLabel() + " \u25be"));
                    }
                    return true;
                }
            }
            // Clicking anywhere else dismisses, and does nothing else.
            setDropdownOpen(false);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (dropdownOpen) {
            List<String> rows = dropdownRows();
            int maxScroll = Math.max(0, rows.size() - DROP_MAX_ROWS);
            dropdownScroll = Math.max(0,
                    Math.min(maxScroll, dropdownScroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * Opens the bundle creator, preserving what has been typed so far and
     * selecting the new bundle on return.
     */
    private void openNewBundle() {
        pendingReopen = true;
        pendingImages = new ArrayList<>(images);
        pendingTitle = titleField.getValue();
        pendingDesc = descField.getValue();
        pendingCandidateIndex = candidateIndex;
        pendingBundleIndex = bundleIndex;

        Minecraft.getInstance().setScreen(new NewBundleScreen(new UploadScreen(source, journey), id -> {
            // Select the freshly created bundle by id once the library refreshes.
            List<LibraryState.BundleOption> opts = LibraryState.get().getBundleOptions();
            for (int i = 0; i < opts.size(); i++) {
                if (id.equals(opts.get(i).id())) {
                    pendingBundleIndex = i;
                    break;
                }
            }
        }));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Skip default
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            Minecraft.getInstance().setScreen(new LibraryScreen(journey));
            return true;
        }
        // Enter in the title field submits
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && titleField != null && titleField.isFocused()) {
            doUpload();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /** Clear camera round-trip state, e.g. on world exit. */
    public static void clearPendingState() {
        pendingReopen = false;
        pendingImages = null;
        pendingTitle = null;
        pendingDesc = null;
        pendingCandidateIndex = 0;
        pendingBundleIndex = 0;
    }
}
