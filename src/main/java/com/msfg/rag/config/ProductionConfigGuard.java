package com.msfg.rag.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fails startup (or warns loudly) when the app boots under the {@code prod}
 * profile while still carrying development defaults. The known-default admin key
 * is treated as a hard error because that surface mutates the corpus and config;
 * the dev DB password and localhost-only CORS are warnings so an operator can
 * make a deliberate choice without being blocked.
 *
 * Only active under the {@code prod} profile so local/test boots are unaffected.
 */
@Component
@Profile("prod")
public class ProductionConfigGuard {

    static final String DEFAULT_ADMIN_KEY = "change-me-local-admin-key";
    static final String DEFAULT_DB_PASSWORD = "local_dev_only";

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigGuard.class);

    private final String adminKey;
    private final String dbPassword;
    private final String corsOrigins;

    public ProductionConfigGuard(
            @Value("${msfg.rag.admin.api-key:}") String adminKey,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${msfg.rag.cors.allowed-origins:}") String corsOrigins) {
        this.adminKey = adminKey;
        this.dbPassword = dbPassword;
        this.corsOrigins = corsOrigins;
    }

    @PostConstruct
    void verify() {
        warnings().forEach(w -> log.warn("[prod-config] {}", w));
        List<String> errors = errors();
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start under the 'prod' profile with insecure configuration: "
                            + String.join("; ", errors));
        }
        log.info("[prod-config] production configuration checks passed");
    }

    /** Hard failures: startup must abort. */
    List<String> errors() {
        List<String> errors = new ArrayList<>();
        if (isBlank(adminKey) || DEFAULT_ADMIN_KEY.equals(adminKey.strip())) {
            errors.add("ADMIN_API_KEY is blank or still the development default — set a strong, unique key");
        }
        return errors;
    }

    /** Soft issues: log but allow boot so an operator can decide. */
    List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        if (isBlank(dbPassword) || DEFAULT_DB_PASSWORD.equals(dbPassword.strip())) {
            warnings.add("DB_PASSWORD is blank or the development default — use a managed secret in production");
        }
        if (isLocalhostOnly(corsOrigins)) {
            warnings.add("CORS allowed-origins is empty or localhost-only — set CORS_ALLOWED_ORIGINS to your real site origins");
        }
        return warnings;
    }

    private static boolean isLocalhostOnly(String origins) {
        if (isBlank(origins)) {
            return true;
        }
        return Arrays.stream(origins.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .allMatch(s -> s.contains("localhost") || s.contains("127.0.0.1"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
