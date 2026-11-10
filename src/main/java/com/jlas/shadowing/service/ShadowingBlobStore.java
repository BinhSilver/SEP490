package com.jlas.shadowing.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Shadowing media kept in a private Azure Blob container. A video or thumbnail path of the form
 * {@code blob:<name>} lives there; every other path is a file on this server. Browsers get a link signed for a few
 * hours, so the files never have to be public and never pass through this server.
 */
@Service
public class ShadowingBlobStore {
    public static final String PREFIX = "blob:";

    private static final String SAS_VERSION = "2022-11-02";
    private static final Duration LINK_LIFETIME = Duration.ofHours(3);
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");

    private final String account;
    private final String endpointSuffix;
    private final String container;
    private final String audioContainer;
    private final byte[] accountKey;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public ShadowingBlobStore(@Value("${azure.storage.connection-string:}") String connectionString,
                              @Value("${azure.storage.container:jlas-media}") String container,
                              @Value("${azure.storage.audio-container:audio}") String audioContainer) {
        Map<String, String> parts = parse(connectionString);
        this.account = parts.get("AccountName");
        this.endpointSuffix = parts.getOrDefault("EndpointSuffix", "core.windows.net");
        this.container = container;
        this.audioContainer = audioContainer;
        this.accountKey = decode(parts.get("AccountKey"));
    }

    public static boolean isBlob(String path) {
        return path != null && path.startsWith(PREFIX);
    }

    /** Signed read link for a {@code blob:} path; empty when storage is not configured here or the name is invalid. */
    public Optional<URI> signedUrl(String path) {
        return signedUrl(path, Instant.now().plus(LINK_LIFETIME));
    }

    Optional<URI> signedUrl(String path, Instant expiry) {
        if (!isBlob(path) || account == null || accountKey == null) return Optional.empty();
        String name = path.substring(PREFIX.length());
        return signedUrl(container, name, expiry);
    }

    public static boolean isSafeAudioName(String name) {
        return name != null && !name.contains("..") && SAFE_NAME.matcher(name).matches();
    }

    /** Stable for one clock hour so repeated audio requests share the same signed URL. */
    public Optional<URI> signedAudioUrl(String name) {
        return signedAudioUrl(name, Instant.now());
    }

    Optional<URI> signedAudioUrl(String name, Instant now) {
        Instant expiry = now.truncatedTo(ChronoUnit.HOURS).plus(2, ChronoUnit.HOURS);
        return signedUrl(audioContainer, name, expiry);
    }

    public boolean audioExists(String name) {
        Optional<URI> url = signedAudioUrl(name);
        if (url.isEmpty()) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(url.get()).timeout(Duration.ofSeconds(5))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private Optional<URI> signedUrl(String targetContainer, String name, Instant expiry) {
        if (account == null || accountKey == null || !isSafeAudioName(name)) return Optional.empty();
        String expires = expiry.truncatedTo(ChronoUnit.SECONDS).toString();
        String resource = "/blob/" + account + "/" + targetContainer + "/" + name;
        // Field order is fixed by the Azure service SAS format; the empty lines are fields this link does not use.
        String toSign = String.join("\n", "r", "", expires, resource, "", "", "https", SAS_VERSION, "b",
                "", "", "", "", "", "", "");
        return Optional.of(URI.create("https://" + account + ".blob." + endpointSuffix + "/" + targetContainer + "/" + name
                + "?sv=" + SAS_VERSION + "&sr=b&sp=r&spr=https&se=" + encode(expires) + "&sig=" + encode(sign(toSign))));
    }

    /** Copies a blob to a temporary file for tools that need a local file, such as ffmpeg. The caller deletes it. */
    public Path download(String path) throws IOException {
        URI url = signedUrl(path).orElseThrow(() -> new IOException("Chưa cấu hình Azure Storage để đọc tệp video."));
        String extension = path.contains(".") ? path.substring(path.lastIndexOf('.')) : ".mp4";
        Path target = Files.createTempFile("shadowing-blob-", extension);
        try (InputStream in = url.toURL().openStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(accountKey, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không ký được đường dẫn tệp video.", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parse(String connectionString) {
        Map<String, String> parts = new HashMap<>();
        if (connectionString == null) return parts;
        for (String part : connectionString.split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0) parts.put(part.substring(0, separator).trim(), part.substring(separator + 1).trim());
        }
        return parts;
    }

    private static byte[] decode(String key) {
        if (key == null || key.isBlank()) return null;
        try {
            return Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
