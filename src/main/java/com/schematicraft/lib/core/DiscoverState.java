package com.schematicraft.lib.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Public schematics fetched from the server, kept deliberately separate from
 * {@link LibraryState}.
 *
 * The two are different in kind, not just in contents. The personal library is
 * fully loaded and filtered locally, so typing is instant. Discover is a paged
 * server query where each keystroke could cost a request, so it owns its own
 * paging, in-flight tracking, and error state. Mixing them into one holder made
 * it too easy to filter the wrong set or refetch the wrong thing.
 */
public class DiscoverState {
    private static final DiscoverState INSTANCE = new DiscoverState();

    public static DiscoverState get() { return INSTANCE; }

    private DiscoverState() {}

    /** Results accumulated across pages for the current query. */
    private final List<SchematicEntry> results = new ArrayList<>();

    /** The query these results belong to. Empty means the browse feed. */
    private String query = "";

    private int page = 0;
    private boolean hasMore = false;
    private int total = 0;

    private boolean loading = false;
    private String error = null;

    /** True once a fetch has completed for the current query. */
    private boolean loaded = false;

    public List<SchematicEntry> getResults() {
        return Collections.unmodifiableList(results);
    }

    public String getQuery() { return query; }
    public int getPage() { return page; }
    public boolean hasMore() { return hasMore; }
    public int getTotal() { return total; }
    public boolean isLoading() { return loading; }
    public String getError() { return error; }
    public boolean isLoaded() { return loaded; }
    public boolean isEmpty() { return results.isEmpty(); }

    /**
     * Begin a fetch. Clearing on page 1 means a new query replaces the feed,
     * while later pages append.
     */
    public void beginFetch(String query, int page) {
        this.loading = true;
        this.error = null;
        if (page <= 1) {
            this.results.clear();
            this.query = query;
            this.loaded = false;
        }
    }

    public void addPage(List<SchematicEntry> pageResults, int page,
                       boolean hasMore, int total) {
        this.results.addAll(pageResults);
        this.page = page;
        this.hasMore = hasMore;
        this.total = total;
        this.loading = false;
        this.loaded = true;
        this.error = null;
    }

    public void setError(String message) {
        this.error = message;
        this.loading = false;
        this.loaded = true;
    }

    /** Forget everything, e.g. on logout. */
    public void reset() {
        results.clear();
        query = "";
        page = 0;
        hasMore = false;
        total = 0;
        loading = false;
        loaded = false;
        error = null;
    }
}
