package com.ragbrain.rag.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackTemplateServiceTest {

    /** The committed neutral template the service clones. */
    private static final Path REAL_TEMPLATE = Path.of("packs/_template");

    private static final List<String> TEMPLATE_FILES = List.of(
            "pack.yaml", "prompt.yaml", "guardrails.yaml", "classifier.yaml", "retrieval.yaml");

    @TempDir
    Path packsRoot;

    @TempDir
    Path templateRoot;

    /** Copy the committed _template into an isolated dir so tests never touch the repo's packs/. */
    private Path copyTemplate() throws IOException {
        Path dir = templateRoot.resolve("_template");
        Files.createDirectories(dir);
        for (String f : TEMPLATE_FILES) {
            Files.copy(REAL_TEMPLATE.resolve(f), dir.resolve(f));
        }
        return dir;
    }

    @Test
    void generatesValidSlugMatchedPack() throws IOException {
        var svc = new PackTemplateService(packsRoot, copyTemplate());

        String ref = svc.generate("acme", "Acme Co", "Educational only.");

        assertEquals("packs/acme", ref);
        Path packDir = packsRoot.resolve("acme");
        for (String f : TEMPLATE_FILES) {
            assertTrue(Files.isRegularFile(packDir.resolve(f)), "missing " + f);
        }
        DomainPack pack = new DomainPackLoader().load(packDir.toAbsolutePath().normalize());
        assertEquals("acme", pack.slug());
        assertEquals("Acme Co", pack.companyName());
        assertEquals("Educational only.", pack.disclaimer());
        assertTrue(pack.acronymExpansions().isEmpty());
        assertTrue(pack.programRules().isEmpty());
    }

    @Test
    void companyNameWithColonAndQuotesStaysValidYaml() throws IOException {
        var svc = new PackTemplateService(packsRoot, copyTemplate());

        String ref = svc.generate("acme2", "Acme: \"The Best\" Co", "Edu");

        assertEquals("packs/acme2", ref);
        DomainPack pack = new DomainPackLoader().load(
                packsRoot.resolve("acme2").toAbsolutePath().normalize());
        assertEquals("Acme: \"The Best\" Co", pack.companyName(),
                "company name with ':' and quotes must round-trip exactly (no naive replace)");
    }

    @Test
    void rejectsWhenTargetExists() throws IOException {
        var svc = new PackTemplateService(packsRoot, copyTemplate());
        Files.createDirectories(packsRoot.resolve("acme"));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> svc.generate("acme", "Acme Co", "Edu"));
        assertTrue(ex.getMessage().contains("already exists"), ex.getMessage());
    }

    @Test
    void deletesPartialDirOnValidationFailure() throws IOException {
        // A template whose prompt.yaml has only 1 %s — the loader rejects it.
        Path broken = templateRoot.resolve("_broken");
        Files.createDirectories(broken);
        for (String f : TEMPLATE_FILES) {
            Files.copy(REAL_TEMPLATE.resolve(f), broken.resolve(f));
        }
        Files.writeString(broken.resolve("prompt.yaml"),
                "template: only %s here\nhard-rules: |-\n  H1\nguidance: |-\n  G1\n");

        var svc = new PackTemplateService(packsRoot, broken);

        var ex = assertThrows(IllegalArgumentException.class,
                () -> svc.generate("acme", "Acme Co", "Edu"));
        assertTrue(ex.getMessage().contains("invalid"), ex.getMessage());
        assertFalse(Files.exists(packsRoot.resolve("acme")),
                "partial dir must be deleted on validation failure");
    }
}
