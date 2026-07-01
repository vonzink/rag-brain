package com.ragbrain.rag.pack;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Generates a slug-matched neutral starter pack by cloning {@code packs/_template}
 * to {@code packs/<slug>}, writing a YAML-safe {@code pack.yaml}, and validating
 * the result with {@link DomainPackLoader}. Used when a brain is created with no
 * explicit packRef, so "connect a folder" needs no hand-authored pack.
 *
 * <p>Deployment caveat: this writes to the working-dir {@code packs/} tree. That
 * is correct for the local/personal deployment this repo targets; a read-only
 * bundled deploy (jar with a read-only classpath {@code packs/}) would need a
 * configurable writable packs dir — out of scope here (see plan Deferred).
 */
@Service
public class PackTemplateService {

    private static final List<String> COPY_FILES =
            List.of("prompt.yaml", "guardrails.yaml", "classifier.yaml", "retrieval.yaml");

    private final Path packsRoot;
    private final Path templateDir;
    private final YAMLMapper yaml = new YAMLMapper();

    /** Production: packs root = ./packs, template = ./packs/_template. */
    public PackTemplateService() {
        this(Path.of("packs"), Path.of("packs", "_template"));
    }

    /** Test/seam ctor: explicit roots so unit tests never touch the real packs/. */
    public PackTemplateService(Path packsRoot, Path templateDir) {
        this.packsRoot = packsRoot;
        this.templateDir = templateDir;
    }

    /** @return the packRef string {@code packs/<slug>} for the generated pack. */
    public String generate(String slug, String companyName, String disclaimer) {
        Path target = packsRoot.resolve(slug);
        if (Files.exists(target)) {
            throw new IllegalArgumentException("pack already exists at packs/" + slug);
        }
        try {
            Files.createDirectories(target);
            for (String f : COPY_FILES) {
                Files.copy(templateDir.resolve(f), target.resolve(f));
            }
            writePackYaml(target.resolve("pack.yaml"), slug, companyName, disclaimer);
            // Fail-fast: a generated pack that the loader rejects must not survive.
            new DomainPackLoader().load(target.toAbsolutePath().normalize());
        } catch (IOException e) {
            deleteQuietly(target);
            throw new IllegalArgumentException("could not generate pack for slug '" + slug + "': " + e.getMessage(), e);
        } catch (DomainPackLoader.PackValidationException e) {
            deleteQuietly(target);
            throw new IllegalArgumentException("generated pack for slug '" + slug + "' is invalid: " + e.getMessage(), e);
        }
        return "packs/" + slug;
    }

    private void writePackYaml(Path file, String slug, String companyName, String disclaimer) throws IOException {
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put("slug", slug);
        identity.put("company-name", companyName);   // YAMLMapper quotes/escapes ':' and quotes safely
        identity.put("disclaimer", disclaimer);
        yaml.writeValue(file.toFile(), identity);
    }

    private void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {
            // best-effort cleanup of the partial dir
        }
    }
}
