package com.schematicraft.lib.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import javax.annotation.Nullable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The in-game download's request and its confirm step. No Minecraft class in
 * it, so its check runs without the game.
 *
 * <p>The client sends the release it runs, so the server converts for that
 * game, and asks with {@code confirm=true} to be told before a conversion that
 * loses content a player cares about. The server then answers 409
 * {@code CONFIRM_LOSS} with what would be lost instead of the file, and the
 * player decides; "Load anyway" repeats the request without {@code confirm}.
 */
public final class IngameDownload {
    private IngameDownload() {}

    /** A release as the server reads one: 1.x[.y], or a year release such as 26.3. */
    private static final Pattern RELEASE =
            Pattern.compile("^(1\\.\\d{1,2}(\\.\\d{1,2})?|[2-9]\\d\\.\\d{1,2}(\\.\\d{1,2})?)$");

    /** The website's words for each facet the confirm step names. */
    private static final Map<String, String> FACETS = Map.of(
            "blocks", "Blocks",
            "blocks.modded", "Modded blocks",
            "block_entities", "Block data",
            "items", "Items",
            "entities", "Entities");

    /** How many of the report's messages the screen shows, and how long each may be. */
    public static final int MAX_MESSAGES = 4;
    public static final int MAX_MESSAGE_CHARS = 200;

    /** The largest error body read back; a CONFIRM_LOSS body is far smaller. */
    public static final int MAX_ERROR_BYTES = 256 * 1024;

    /**
     * The game's version name when it is a release, else null, so a snapshot
     * or a renamed build sends no version and gets what it got before.
     */
    @Nullable
    public static String release(@Nullable String versionName) {
        if (versionName == null) return null;
        String v = versionName.trim();
        return RELEASE.matcher(v).matches() ? v : null;
    }

    /** The download's query string. */
    public static String query(String format, String targetEditor, @Nullable String release,
                               @Nullable String paletteId, boolean confirm) {
        StringBuilder q = new StringBuilder("format=").append(enc(format))
                .append("&targetEditor=").append(enc(targetEditor));
        if (release != null) q.append("&targetVersion=").append(enc(release));
        if (paletteId != null) q.append("&paletteId=").append(enc(paletteId));
        if (confirm) q.append("&confirm=true");
        return q.toString();
    }

    /** What a download would lose, as the server's 409 CONFIRM_LOSS says it. */
    public record Loss(List<String> lost, List<String> messages) {
        /** One line per lost facet, in the website's words: "Block data not kept". */
        public List<String> lostLines() {
            List<String> out = new ArrayList<>();
            for (String facet : lost) {
                out.add(FACETS.getOrDefault(facet, facet.replace('_', ' ')) + " not kept");
            }
            return out;
        }
    }

    /** A download the server held back for the player to confirm. */
    public static final class ConfirmLoss extends RuntimeException {
        public final Loss loss;

        public ConfirmLoss(Loss loss) {
            super("Some of this build does not carry over");
            this.loss = loss;
        }
    }

    /** The loss in an error answer, or null for any other answer. */
    @Nullable
    public static Loss parseConfirmLoss(int status, @Nullable String body) {
        if (status != 409 || body == null) return null;
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return null;
            JsonObject o = root.getAsJsonObject();
            if (!"CONFIRM_LOSS".equals(string(o.get("error")))) return null;
            List<String> messages = new ArrayList<>();
            JsonElement report = o.get("report");
            if (report != null && report.isJsonObject()) {
                for (String m : strings(report.getAsJsonObject().get("messages"))) {
                    if (messages.size() == MAX_MESSAGES) break;
                    String plain = plain(m, MAX_MESSAGE_CHARS);
                    if (!plain.isEmpty()) messages.add(plain);
                }
            }
            return new Loss(strings(o.get("lost")), messages);
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    /** The loss a failed download carries, wherever it sits among the causes. */
    @Nullable
    public static Loss lossOf(@Nullable Throwable ex) {
        for (Throwable c = ex; c != null; c = c.getCause()) {
            if (c instanceof ConfirmLoss confirm) return confirm.loss;
        }
        return null;
    }

    /** What to tell the player about any other error answer. */
    public static String failure(int status, @Nullable String body) {
        return switch (status) {
            case 409 -> "Schematic is still being analyzed. Try again in a moment.";
            case 402 -> "Download quota exceeded.";
            case 429 -> "Too many requests. Wait a moment.";
            default -> "HTTP " + status + ": " + plain(body == null ? "" : body, 500);
        };
    }

    /**
     * Server text as plain text: no formatting codes, which the game would
     * apply, and no control characters, on one line and capped. The report's
     * messages name blocks from an uploaded file.
     */
    public static String plain(String s, int max) {
        String t = s.replaceAll("\u00a7.?", "")
                .replaceAll("[\\p{Cntrl}\\s]+", " ")
                .trim();
        return t.length() <= max ? t : t.substring(0, max - 3).trim() + "...";
    }

    private static List<String> strings(@Nullable JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e == null || !e.isJsonArray()) return out;
        JsonArray arr = e.getAsJsonArray();
        for (JsonElement item : arr) {
            String s = string(item);
            if (s != null) out.add(s);
        }
        return out;
    }

    @Nullable
    private static String string(@Nullable JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()
                ? e.getAsString() : null;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
