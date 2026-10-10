package com.schematicraft.lib.core;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * The in-game download's request and confirm step, checked without the game:
 * {@code gradlew selfCheck}, which {@code check} and {@code build} run.
 * No test framework, so the library takes no new dependency.
 */
public final class IngameDownloadCheck {
    private static int checks;

    public static void main(String[] args) {
        // The release, only when the game's name has the release shape.
        eq("1.21.1", IngameDownload.release("1.21.1"));
        eq("26.3", IngameDownload.release(" 26.3 "));
        eq("1.12.2", IngameDownload.release("1.12.2"));
        eq(null, IngameDownload.release("24w14a"));
        eq(null, IngameDownload.release("1.21.1-pre1"));
        eq(null, IngameDownload.release("1.21.1 Forge"));
        eq(null, IngameDownload.release(null));

        // The query: the target as today, plus the release and the question.
        eq("format=json&targetEditor=BuildingGadgets&targetVersion=1.21.1&confirm=true",
                IngameDownload.query("json", "BuildingGadgets", "1.21.1", null, true));
        eq("format=nbt&targetEditor=Create",
                IngameDownload.query("nbt", "Create", null, null, false));
        eq("format=json&targetEditor=BuildingGadgets&targetVersion=1.21.1&paletteId=p%26q",
                IngameDownload.query("json", "BuildingGadgets", "1.21.1", "p&q", false));

        // The server's question, as it sends it.
        String body = """
                {"statusCode":409,"error":"CONFIRM_LOSS",
                 "message":"Some of this build does not carry over.",
                 "lost":["block_entities","entities"],
                 "report":{"facets":{"block_entities":"lost"},
                           "works_in":{"from":"1.20.4","to":"26.1.2"},
                           "messages":["Building Gadgets 2 cannot store \\u00a7cblock\\u00a7r data.\\nSuch as chests.",
                                       "", "b", "c", "d", "e"]},
                 "retryable":false}""";
        IngameDownload.Loss loss = IngameDownload.parseConfirmLoss(409, body);
        eq(List.of("block_entities", "entities"), Objects.requireNonNull(loss).lost());
        eq(List.of("Block data not kept", "Entities not kept"), loss.lostLines());
        // Formatting codes and line breaks gone, empty ones skipped, four at most.
        eq(List.of("Building Gadgets 2 cannot store block data. Such as chests.", "b", "c", "d"),
                loss.messages());

        // Anything else is not a question.
        String pending = "{\"statusCode\":409,\"error\":\"ANALYSIS_PENDING\",\"retryable\":true}";
        eq(null, IngameDownload.parseConfirmLoss(409, pending));
        eq(null, IngameDownload.parseConfirmLoss(402, body));
        eq(null, IngameDownload.parseConfirmLoss(409, "<html>Bad gateway</html>"));
        eq(null, IngameDownload.parseConfirmLoss(409, "{\"error\":"));
        eq("Schematic is still being analyzed. Try again in a moment.",
                IngameDownload.failure(409, pending));
        eq("Download quota exceeded.", IngameDownload.failure(402, ""));
        eq("HTTP 500: Internal error", IngameDownload.failure(500, "Internal\n  error"));

        // Found through the futures that wrap it.
        IngameDownload.Loss wrapped = IngameDownload.lossOf(new CompletionException(
                new RuntimeException(new IngameDownload.ConfirmLoss(loss))));
        eq(loss, wrapped);
        eq(null, IngameDownload.lossOf(new CompletionException(new RuntimeException("x"))));

        // Capped, and marked as cut.
        eq("abcdefg...", IngameDownload.plain("abcdefghijklmnop", 10));

        System.out.println("IngameDownloadCheck: " + checks + " checks passed");
    }

    private static void eq(Object expected, Object actual) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("check " + checks + ": expected <" + expected
                    + "> but was <" + actual + ">");
        }
    }
}
