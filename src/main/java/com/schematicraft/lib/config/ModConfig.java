package com.schematicraft.lib.config;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * File-based config for the API key and server endpoint.
 * Stored in .minecraft/config/schematicraft.properties
 *
 * <p>The endpoint is a trust boundary: the stored API key is sent to whatever
 * host it names. A config file can be edited by any local process or shipped
 * inside a modpack, so the loaded value is validated rather than trusted.
 *
 * <p>Rules:
 * <ul>
 *   <li>HTTPS endpoints are accepted.</li>
 *   <li>Plain HTTP is accepted only for a loopback host and only when
 *       {@code allow_insecure_localhost=true} is set explicitly.</li>
 *   <li>Anything else falls back to the default endpoint.</li>
 * </ul>
 */
public class ModConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CONFIG_FILE = "config/schematicraft.properties";
    private static final String DEFAULT_SERVER_URL = "https://schematicraft.com";

    private static final Set<String> LOOPBACK_HOSTS =
            Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private static String apiKey = "";
    private static String serverUrl = DEFAULT_SERVER_URL;
    private static boolean allowInsecureLocalhost = false;

    /**
     * Optional extra hosts permitted to serve thumbnail images. Empty by
     * default, which permits any public HTTPS host. Set
     * {@code media_hosts=cdn.example.com,images.example.com} to restrict it.
     */
    private static Set<String> mediaHosts = Collections.emptySet();

    public static void init() {
        load();
    }

    public static String getApiKey() {
        return apiKey;
    }

    public static void setApiKey(String key) {
        apiKey = key != null ? key.trim() : "";
        save();
    }

    public static String getServerUrl() {
        return serverUrl;
    }

    public static boolean hasApiKey() {
        return !apiKey.isEmpty() && apiKey.startsWith("sk_");
    }

    /**
     * Short, non-reversible label for a stored key, for display only.
     * Returns the environment prefix and nothing that could be replayed.
     */
    public static String getApiKeyDisplayLabel() {
        if (!hasApiKey()) {
            return "";
        }
        int cut = Math.min(apiKey.length(), "sk_live_".length());
        return apiKey.substring(0, cut) + "\u2026";
    }

    /** Whether plain HTTP to a loopback host is explicitly permitted. */
    public static boolean isInsecureLocalhostAllowed() {
        return allowInsecureLocalhost;
    }

    /** Host of the configured endpoint, or an empty string if unavailable. */
    public static String getServerHost() {
        try {
            String host = new URI(serverUrl).getHost();
            return host != null ? host.toLowerCase(Locale.ROOT) : "";
        } catch (URISyntaxException e) {
            return "";
        }
    }

    /**
     * Whether a media URL supplied by the API may be fetched.
     *
     * <p>Requires HTTPS, unless insecure loopback is explicitly enabled. When
     * {@code media_hosts} is configured, the host must also be the endpoint host
     * or one of the listed hosts. This is a scheme and host check only. Callers
     * must still reject private resolved addresses and bound the response.
     */
    public static boolean isMediaUrlAllowed(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return false;
        }

        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);

        boolean schemeOk = "https".equals(scheme)
                || ("http".equals(scheme) && allowInsecureLocalhost && LOOPBACK_HOSTS.contains(host));
        if (!schemeOk) {
            return false;
        }

        if (mediaHosts.isEmpty()) {
            return true;
        }

        return host.equals(getServerHost()) || mediaHosts.contains(host);
    }

    private static void load() {
        Path path = Path.of(CONFIG_FILE);
        if (!Files.exists(path)) return;

        try (var reader = Files.newBufferedReader(path)) {
            Properties props = new Properties();
            props.load(reader);
            apiKey = props.getProperty("api_key", "").trim();
            allowInsecureLocalhost =
                    Boolean.parseBoolean(props.getProperty("allow_insecure_localhost", "false"));
            serverUrl = sanitizeServerUrl(props.getProperty("server_url", DEFAULT_SERVER_URL));
            mediaHosts = parseHosts(props.getProperty("media_hosts", ""));
        } catch (IOException e) {
            LOGGER.warn("Failed to load config: {}", e.getMessage());
        }
    }

    /**
     * Accept only endpoints safe to send a bearer credential to.
     * Falls back to the default endpoint instead of failing closed to an
     * unusable state, and logs the host so the user can see the rejection.
     */
    private static String sanitizeServerUrl(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return DEFAULT_SERVER_URL;
        }

        String trimmed = candidate.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        final URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            LOGGER.warn("Ignoring malformed server_url, using {}", DEFAULT_SERVER_URL);
            return DEFAULT_SERVER_URL;
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            LOGGER.warn("Ignoring server_url without scheme or host, using {}", DEFAULT_SERVER_URL);
            return DEFAULT_SERVER_URL;
        }

        scheme = scheme.toLowerCase(Locale.ROOT);
        String lowerHost = host.toLowerCase(Locale.ROOT);

        if ("https".equals(scheme)) {
            return trimmed;
        }

        if ("http".equals(scheme) && LOOPBACK_HOSTS.contains(lowerHost)) {
            if (allowInsecureLocalhost) {
                LOGGER.warn("Using insecure local endpoint {} because allow_insecure_localhost=true", trimmed);
                return trimmed;
            }
            LOGGER.warn("Refusing local HTTP endpoint. Set allow_insecure_localhost=true to permit it.");
            return DEFAULT_SERVER_URL;
        }

        LOGGER.warn("Refusing non-HTTPS endpoint for host {}, using {}", lowerHost, DEFAULT_SERVER_URL);
        return DEFAULT_SERVER_URL;
    }

    private static Set<String> parseHosts(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String host = part.trim().toLowerCase(Locale.ROOT);
            if (!host.isEmpty()) {
                hosts.add(host);
            }
        }
        return Collections.unmodifiableSet(hosts);
    }

    private static void save() {
        Path path = Path.of(CONFIG_FILE);
        try {
            Files.createDirectories(path.getParent());
            Properties props = new Properties();
            props.setProperty("api_key", apiKey);
            props.setProperty("server_url", serverUrl);
            props.setProperty("allow_insecure_localhost", Boolean.toString(allowInsecureLocalhost));
            props.setProperty("media_hosts", String.join(",", mediaHosts));
            props.store(Files.newBufferedWriter(path), "Schematicraft Config");
        } catch (IOException e) {
            LOGGER.warn("Failed to save config: {}", e.getMessage());
        }
    }
}
