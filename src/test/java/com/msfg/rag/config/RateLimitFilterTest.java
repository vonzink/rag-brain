package com.msfg.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    private final RagProperties props = new RagProperties(
            new RagProperties.Routing("anthropic", "openai"),
            new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24),
            new RagProperties.Chunking(1000, 1200, 150),
            new RagProperties.Storage("./data/documents"),
            new RagProperties.Admin("k"),
            new RagProperties.RateLimit(10));

    @Test
    void limitsTheConfiguredAskPathAndAnyPublicAskSlug() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", false, 1);

        assertFalse(filter.shouldNotFilter(get("/api/ai/mortgage/ask")),
                "the ask path must be rate limited");
        assertFalse(filter.shouldNotFilter(get("/api/ai/public/generic/ask")),
                "generic public ask must be rate limited");
        assertFalse(filter.shouldNotFilter(get("/api/ai/public/other-brain/ask")),
                "other-brain public ask must be rate limited");
        assertTrue(filter.shouldNotFilter(get("/api/ai/documents")),
                "admin endpoints are not rate limited by this filter");
    }

    @Test
    void percentEncodedAskPathIsStillRateLimited() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", false, 1);
        assertFalse(filter.shouldNotFilter(get("/api/ai/mortgage/%61sk")),
                "encoded ask path must not bypass rate limiting");
    }

    @Test
    void followsTheConfiguredSlug() {
        RateLimitFilter filter = new RateLimitFilter(props, "roofing", false, 1);

        assertFalse(filter.shouldNotFilter(get("/api/ai/roofing/ask")));
        assertTrue(filter.shouldNotFilter(get("/api/ai/mortgage/ask")),
                "the old path is not limited when the slug changes");
    }

    @Test
    void preflightOptionsAreNotRateLimited() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", false, 1);
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/ai/mortgage/ask");
        preflight.setRequestURI("/api/ai/mortgage/ask");
        assertTrue(filter.shouldNotFilter(preflight),
                "preflights must not consume rate budget");
    }

    @Test
    void ignoresSpoofableForwardedForByDefault() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", false, 1);
        MockHttpServletRequest request = get("/api/ai/public/generic/ask");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertEquals("203.0.113.7", filter.clientKey(request),
                "the spoofable forwarded header must be ignored unless explicitly trusted");
    }

    @Test
    void usesProxyAppendedClientIpWhenForwardedForIsTrusted() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", true, 1);
        MockHttpServletRequest request = get("/api/ai/public/generic/ask");
        request.setRemoteAddr("10.0.0.1");
        // A malicious client prepends a fake entry; the trusted proxy appends the
        // real peer IP on the right. With one trusted proxy that real IP wins.
        request.addHeader("X-Forwarded-For", "1.2.3.4, 198.51.100.23");

        assertEquals("198.51.100.23", filter.clientKey(request),
                "the trusted-proxy entry must be used, not the client-controlled leftmost one");
    }

    @Test
    void fallsBackToRemoteAddrWhenForwardedForHasTooFewHops() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage", true, 2);
        MockHttpServletRequest request = get("/api/ai/public/generic/ask");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertEquals("10.0.0.1", filter.clientKey(request),
                "with fewer hops than trusted proxies the header cannot be trusted");
    }

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }
}
