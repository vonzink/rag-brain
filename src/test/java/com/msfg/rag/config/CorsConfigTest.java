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
        assertTrue(mappings.containsKey("/api/ai/conversations/**"),
                "the conversation endpoints stay exposed");
        assertTrue(mappings.get("/api/ai/mortgage/**").getAllowedHeaders()
                .contains("X-Admin-Api-Key"), "legacy ask route must allow the admin key header");
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
    }

    @Test
    void exposesPublicAssistantPathWithPublicTokenHeader() {
        Map<String, CorsConfiguration> mappings = register("mortgage");

        assertTrue(mappings.containsKey("/api/ai/public/**"),
                "public assistant endpoint must handle browser preflight");
        CorsConfiguration config = mappings.get("/api/ai/public/**");
        assertTrue(config.getAllowedMethods().contains("POST"));
        assertTrue(config.getAllowedMethods().contains("OPTIONS"));
        assertTrue(config.getAllowedHeaders().contains("Content-Type"));
        assertTrue(config.getAllowedHeaders().contains("X-Public-Brain-Token"));
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
