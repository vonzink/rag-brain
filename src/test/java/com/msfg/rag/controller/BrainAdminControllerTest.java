package com.msfg.rag.controller;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.pack.DomainPackRegistry;
import com.msfg.rag.repository.BrainRepository;
import com.msfg.rag.service.ai.ModelRouterService;
import com.msfg.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrainAdminControllerTest {

    private final BrainRepository brains = mock(BrainRepository.class);
    private final SyncService syncService = mock(SyncService.class);
    private final DomainPackRegistry packRegistry = mock(DomainPackRegistry.class);
    private final ModelRouterService router = mock(ModelRouterService.class);
    private final BrainAdminController controller =
            new BrainAdminController(brains, syncService, packRegistry, router);

    private Brain brain(UUID id, String slug, boolean isDefault, boolean active) {
        Brain b = new Brain(id, slug, slug + " brain");
        b.setPackRef("packs/" + slug);
        b.setSourceType("local");
        b.setLocalPath("/corpora/" + slug);
        b.setAnswerProvider("anthropic");
        b.setAnswerModel("claude-haiku-4-5");
        b.setUtilityProvider("openai");
        b.setUtilityModel("gpt-4.1-nano");
        b.setLocalApiKeyRef("secret-ref");   // must never surface in the DTO
        b.setDefault(isDefault);
        b.setActive(active);
        return b;
    }

    @Test
    void listReturnsEverBrainAsDtoWithoutSecrets() {
        UUID id = UUID.randomUUID();
        when(brains.findAll()).thenReturn(List.of(brain(id, "mortgage", true, true)));

        List<BrainAdminController.BrainDto> dtos = controller.list();

        assertEquals(1, dtos.size());
        BrainAdminController.BrainDto dto = dtos.get(0);
        assertEquals(id, dto.id());
        assertEquals("mortgage", dto.slug());
        assertEquals("packs/mortgage", dto.packRef());
        assertEquals("local", dto.sourceType());
        assertEquals("/corpora/mortgage", dto.localPath());
        assertEquals("anthropic", dto.answerProvider());
        assertEquals("claude-haiku-4-5", dto.answerModel());
        assertEquals(true, dto.isDefault());
        assertEquals(true, dto.isActive());
        // Secret-exposure guard: the DTO has no accessor that returns the key ref.
        assertFalse(dto.toString().contains("secret-ref"),
                "BrainDto must never carry local_api_key_ref / local_base_url");
    }

    @Test
    void getByIdReturnsTheBrain() {
        UUID id = UUID.randomUUID();
        when(brains.findById(id)).thenReturn(Optional.of(brain(id, "lending", false, true)));
        assertEquals("lending", controller.get(id).slug());
    }

    // ---- Task 2: create ----

    /** Controller whose pack check is a no-op, so source/slug cases never touch the filesystem. */
    private BrainAdminController noPackCheck() {
        return new BrainAdminController(brains, syncService, packRegistry, router) {
            @Override void validatePack(String packRef, String slug) { /* accept */ }
        };
    }

    @Test
    void createPersistsAndReturnsDtoForALocalBrain() {
        when(brains.findBySlug("lending")).thenReturn(Optional.empty());
        when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));

        BrainAdminController.CreateBrainRequest req = new BrainAdminController.CreateBrainRequest(
                "lending", "Lending Brain", "packs/test-pack", "local",
                null, null, null, "/corpora/lending",
                "anthropic", "claude-haiku-4-5", "openai", "gpt-4.1-nano");

        BrainAdminController.BrainDto dto = noPackCheck().create(req);

        assertEquals("lending", dto.slug());
        assertEquals("Lending Brain", dto.displayName());
        assertEquals("local", dto.sourceType());
        assertEquals("/corpora/lending", dto.localPath());
        assertEquals(false, dto.isDefault());   // never seed a default on create
        assertEquals(true, dto.isActive());
    }

    @Test
    void createRejectsBadSlug() {
        BrainAdminController.CreateBrainRequest req = req("Bad Slug", "local");
        assertThrows(IllegalArgumentException.class, () -> controller.create(req));
    }

    @Test
    void createRejectsDuplicateSlug() {
        when(brains.findBySlug("mortgage")).thenReturn(Optional.of(brain(UUID.randomUUID(), "mortgage", true, true)));
        assertThrows(IllegalArgumentException.class, () -> controller.create(req("mortgage", "local")));
    }

    @Test
    void createRejectsLocalWithoutPath() {
        BrainAdminController.CreateBrainRequest req = new BrainAdminController.CreateBrainRequest(
                "lending", "Lending", "packs/test-pack", "local",
                null, null, null, "   ", "anthropic", "m", "openai", "u");
        assertThrows(IllegalArgumentException.class, () -> controller.create(req));
    }

    @Test
    void createRejectsS3WithoutBucket() {
        BrainAdminController.CreateBrainRequest req = new BrainAdminController.CreateBrainRequest(
                "lending", "Lending", "packs/test-pack", "s3",
                "", "p/", "us-west-1", null, "anthropic", "m", "openai", "u");
        assertThrows(IllegalArgumentException.class, () -> controller.create(req));
    }

    @Test
    void createValidatesPackSlugAgainstRealPack() {
        when(brains.findBySlug("testco")).thenReturn(Optional.empty());
        when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));
        // Real on-disk fixture pack (slug: testco). Brain slug matches -> passes.
        BrainAdminController.CreateBrainRequest req = new BrainAdminController.CreateBrainRequest(
                "testco", "Test Co", "src/test/resources/packs/test-pack", "local",
                null, null, null, "/corpora/testco", "anthropic", "m", "openai", "u");
        assertEquals("testco", controller.create(req).slug());
    }

    @Test
    void createRejectsPackSlugMismatch() {
        when(brains.findBySlug("mismatch")).thenReturn(Optional.empty());
        // Same real pack (slug: testco) but brain slug differs -> 400.
        BrainAdminController.CreateBrainRequest req = new BrainAdminController.CreateBrainRequest(
                "mismatch", "Mismatch", "src/test/resources/packs/test-pack", "local",
                null, null, null, "/corpora/mismatch", "anthropic", "m", "openai", "u");
        assertThrows(IllegalArgumentException.class, () -> controller.create(req));
    }

    // helper
    private BrainAdminController.CreateBrainRequest req(String slug, String sourceType) {
        return new BrainAdminController.CreateBrainRequest(
                slug, "Display", "packs/test-pack", sourceType,
                "bucket", "p/", "us-west-1", "/corpora/x",
                "anthropic", "m", "openai", "u");
    }

    // ---- Task 3: update ----

    @Test
    void updateRevalidatesAndReloadsPackWhenPackRefChanges() {
        UUID id = UUID.randomUUID();
        Brain existing = brain(id, "lending", false, true);
        existing.setPackRef("packs/old");
        when(brains.findById(id)).thenReturn(Optional.of(existing));
        when(brains.findBySlug("lending")).thenReturn(Optional.of(existing)); // self -> allowed
        when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));

        BrainAdminController c = new BrainAdminController(brains, syncService, packRegistry, router) {
            @Override void validatePack(String packRef, String slug) { /* accept */ }
        };
        BrainAdminController.UpdateBrainRequest req = new BrainAdminController.UpdateBrainRequest(
                "lending", "Lending", "packs/new", "local",
                null, null, null, "/corpora/lending", "anthropic", "m", "openai", "u");
        c.update(id, req);
        org.mockito.Mockito.verify(packRegistry).reload(id);
    }

    @Test
    void updateRejectsSlugTakenByAnotherBrain() {
        UUID id = UUID.randomUUID();
        when(brains.findById(id)).thenReturn(Optional.of(brain(id, "lending", false, true)));
        when(brains.findBySlug("mortgage")).thenReturn(Optional.of(brain(UUID.randomUUID(), "mortgage", true, true)));
        BrainAdminController.UpdateBrainRequest req = new BrainAdminController.UpdateBrainRequest(
                "mortgage", "X", "packs/test-pack", "local",
                null, null, null, "/x", "anthropic", "m", "openai", "u");
        BrainAdminController c = new BrainAdminController(brains, syncService, packRegistry, router) {
            @Override void validatePack(String packRef, String slug) { }
        };
        assertThrows(IllegalArgumentException.class, () -> c.update(id, req));
    }

    // ---- Task 4: activate ----

    @Test
    void activateClearsOldDefaultBeforeSettingNew() {
        UUID oldId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        Brain old = brain(oldId, "mortgage", true, true);
        Brain target = brain(newId, "lending", false, true);
        when(brains.findById(newId)).thenReturn(Optional.of(target));
        when(brains.findDefaultBrain()).thenReturn(Optional.of(old));
        when(brains.saveAndFlush(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));
        when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));

        BrainAdminController.BrainDto dto = controller.activate(newId);

        assertEquals(true, dto.isDefault());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(brains);
        order.verify(brains).saveAndFlush(old);   // old cleared + flushed first
        order.verify(brains).save(target);        // then new set
        assertFalse(old.isDefault());
        assertEquals(true, target.isDefault());
    }

    @Test
    void activateRejectsInactiveBrain() {
        UUID id = UUID.randomUUID();
        when(brains.findById(id)).thenReturn(Optional.of(brain(id, "lending", false, false)));
        assertThrows(IllegalArgumentException.class, () -> controller.activate(id));
    }

    // ---- Task 5: sync ----

    @Test
    void syncPassesDryRunThroughByBrainId() {
        UUID id = UUID.randomUUID();
        when(brains.findById(id)).thenReturn(Optional.of(brain(id, "lending", false, true)));
        com.msfg.rag.service.sync.SyncReport report =
                new com.msfg.rag.service.sync.SyncReport(true, java.util.Map.of("skip", 1), java.util.List.of());
        when(syncService.sync(org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(id)))
                .thenReturn(report);

        assertEquals(report, controller.sync(id, true));
        org.mockito.Mockito.verify(syncService)
                .sync(org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(id));
    }

    @Test
    void syncDefaultsToExecute() {
        UUID id = UUID.randomUUID();
        when(brains.findById(id)).thenReturn(Optional.of(brain(id, "lending", false, true)));
        com.msfg.rag.service.sync.SyncReport report =
                new com.msfg.rag.service.sync.SyncReport(false, java.util.Map.of(), java.util.List.of());
        when(syncService.sync(org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(id)))
                .thenReturn(report);

        assertEquals(report, controller.sync(id, false));
    }

    @Test
    void syncRejectsUnknownBrain() {
        UUID id = UUID.randomUUID();
        when(brains.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> controller.sync(id, false));
    }

    // ---- Task 6: soft-delete ----

    @Test
    void deleteSoftDeletesByDeactivating() {
        UUID id = UUID.randomUUID();
        Brain b = brain(id, "lending", false, true);
        when(brains.findById(id)).thenReturn(Optional.of(b));
        when(brains.save(any(Brain.class))).thenAnswer(inv -> inv.getArgument(0));
        assertEquals(false, controller.softDelete(id).isActive());
        assertFalse(b.isActive());
    }

    @Test
    void deleteRefusesTheDefaultBrain() {
        UUID id = UUID.randomUUID();
        when(brains.findById(id)).thenReturn(Optional.of(brain(id, "mortgage", true, true)));
        assertThrows(IllegalArgumentException.class, () -> controller.softDelete(id));
    }
}
