package com.msfg.rag.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Per-client token-bucket rate limiting on the public ask endpoint.
 *
 * <p>Client identity comes from {@code getRemoteAddr()} by default. The
 * {@code X-Forwarded-For} header is only honoured when
 * {@code msfg.rag.rate-limit.trust-forwarded-for=true}, because a direct caller
 * can set that header to any value and trivially defeat IP-based limiting by
 * rotating it. When enabled, the real client is read from the entry appended by
 * the trusted proxy layer — {@code trusted-proxy-count} entries from the right —
 * not the client-controlled leftmost value.
 *
 * <p>In-memory buckets are fine for a single instance; move to a Redis-backed
 * bucket4j store if we scale horizontally (limits would otherwise be per-pod).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_CLIENTS = 100_000;
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();
    private static final Pattern PUBLIC_ASK_PATH = Pattern.compile("^/api/ai/public/[^/]+/ask$");

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int requestsPerMinute;
    private final Set<String> askPaths;
    private final boolean trustForwardedFor;
    private final int trustedProxyCount;

    public RateLimitFilter(RagProperties properties,
                           @Value("${brain.slug:generic}") String slug,
                           @Value("${msfg.rag.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor,
                           @Value("${msfg.rag.rate-limit.trusted-proxy-count:1}") int trustedProxyCount) {
        this.requestsPerMinute = properties.rateLimit().requestsPerMinute();
        this.askPaths = Set.of(
                "/api/ai/" + slug + "/ask",
                "/api/ai/public/" + slug + "/ask");
        this.trustForwardedFor = trustForwardedFor;
        this.trustedProxyCount = Math.max(1, trustedProxyCount);
    }

    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        // Preflights must not consume rate budget — they carry no application
        // payload and never reach the handler; the real POST still gets limited.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // Decoded path — percent-encoding must not bypass rate limiting.
        String path = PATH_HELPER.getPathWithinApplication(request);
        return !askPaths.contains(path) && !PUBLIC_ASK_PATH.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Basic abuse guard: drop the map if it grows unreasonably.
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.clear();
        }

        Bucket bucket = buckets.computeIfAbsent(clientKey(request), key ->
                Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(requestsPerMinute)
                                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                                .build())
                        .build());

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":\"Too many requests. Please wait a moment and try again.\"}");
        }
    }

    String clientKey(HttpServletRequest request) {
        if (!trustForwardedFor) {
            return request.getRemoteAddr();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] parts = forwarded.split(",");
        // The client controls the leftmost entries; only the rightmost entries are
        // appended by trusted proxies. The real client is the entry the outermost
        // trusted proxy observed: count trustedProxyCount back from the end.
        int idx = parts.length - trustedProxyCount;
        if (idx < 0) {
            // Fewer hops than configured — the header can't be trusted for this
            // request, so fall back to the direct peer rather than a spoofable entry.
            return request.getRemoteAddr();
        }
        String ip = parts[idx].strip();
        return ip.isEmpty() ? request.getRemoteAddr() : ip;
    }
}
