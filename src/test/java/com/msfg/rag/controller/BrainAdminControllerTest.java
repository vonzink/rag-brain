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
}
