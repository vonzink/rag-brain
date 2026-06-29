package com.msfg.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsConfigTest {

    @Test
    void mapsTheDefaultSlugAskPath() {
        Map<String, CorsConfiguration> mappings = register("mortgage");

        assertTrue(mappings.containsKey("/api/ai/mortgage/**"),
                "the default slug keeps the existing mapping");
        assertTrue(mappings.get("/api/ai/mortgage/**").getAllowedHeaders()
                .contains("X-Admin-Api-Key"), "legacy ask route must allow the admin key header");
    }

    @Test
    void publicAndConversationPathsAreHandledByTheDynamicFilterNotMvcMappings() {
        Map<String, CorsConfiguration> mappings = register("mortgage");

        // These moved to PublicCorsConfigurationSource so customer embed origins
        // can be authorized at runtime; they must NOT be static MVC mappings.
        assertFalse(mappings.containsKey("/api/ai/public/**"),
                "public assistant CORS is dynamic, not a static MVC mapping");
        assertFalse(mappings.containsKey("/api/ai/conversations/**"),
                "conversation CORS is dynamic, not a static MVC mapping");
    }

    @Test
    void exposesAdminSurfacesForTheDashboard() {
        Map<String, CorsConfiguration> mappings = register("mortgage");

        assertTrue(mappings.containsKey("/api/ai/admin/**"),
                "dashboard origins must reach the admin API");
        assertTrue(mappings.containsKey("/api/ai/documents/**"),
                "dashboard origins must reach the documents API");
        assertTrue(mappings.get("/api/ai/admin/**").getAllowedHeaders()
                .contains("X-Admin-Api-Key"), "the admin key header must be allowed");

        CorsConfiguration admin = mappings.get("/api/ai/admin/**");
        assertTrue(admin.getAllowedMethods().contains("PATCH"),
                "admin source-link/page-guide edits use PATCH");
        assertTrue(admin.getAllowedMethods().contains("DELETE"),
                "admin deletes (brains, links, guides) use DELETE");

        CorsConfiguration documents = mappings.get("/api/ai/documents/**");
        assertTrue(documents.getAllowedMethods().contains("PATCH"),
                "document metadata edits use PATCH");
        assertTrue(documents.getAllowedMethods().contains("DELETE"),
                "document removal uses DELETE");
    }

    @Test
    void followsTheConfiguredSlug() {
        Map<String, CorsConfiguration> mappings = register("roofing");

        assertTrue(mappings.containsKey("/api/ai/roofing/**"),
                "browser preflight must succeed on the new slug path");
        assertFalse(mappings.containsKey("/api/ai/mortgage/**"),
                "the old path is not exposed when the slug changes");
    }

    private Map<String, CorsConfiguration> register(String slug) {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();
        new CorsConfig("https://msfg.us,https://www.msfg.us", slug).addCorsMappings(registry);
        return registry.mappings();
    }

    /** getCorsConfigurations() is protected, so a subclass is the only way to inspect the registry. */
    private static class InspectableCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> mappings() {
            return getCorsConfigurations();
        }
    }
}
