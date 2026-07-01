package com.ragbrain.rag.service.profile;

import com.ragbrain.rag.domain.Brain;
import com.ragbrain.rag.domain.BrainMode;
import com.ragbrain.rag.domain.BrainProfile;
import com.ragbrain.rag.dto.BrainProfileRequest;
import com.ragbrain.rag.repository.BrainProfileRepository;
import com.ragbrain.rag.repository.BrainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrainProfileServiceTest {

    private BrainRepository brains;
    private BrainProfileRepository profiles;
    private BrainProfileService service;

    @BeforeEach
    void setUp() {
        brains = mock(BrainRepository.class);
        profiles = mock(BrainProfileRepository.class);
        service = new BrainProfileService(brains, profiles);

        UUID brainId = UUID.randomUUID();
        Brain brain = new Brain(brainId, "test-brain", "Test Brain");
        BrainProfile profile = new BrainProfile();
        profile.setBrainId(brainId);
        profile.setMode(BrainMode.PUBLIC_SITE);

        when(brains.findById(any(UUID.class))).thenReturn(Optional.of(brain));
        when(profiles.findByBrainId(any(UUID.class))).thenReturn(Optional.of(profile));
        when(profiles.save(any(BrainProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void updateRejectsNullAllowedDomainEntries() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = request("PUBLIC_SITE", Arrays.asList("example.com", null));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.update(brainId, req));

        assertEquals("allowedDomains must not contain null entries", ex.getMessage());
    }

    @Test
    void updateRejectsInvalidModeWithClearMessage() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = request("NOT_A_MODE", List.of("example.com"));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.update(brainId, req));

        assertEquals(
                "mode must be one of PUBLIC_SITE, PRIVATE_SITE, SECURE_DEPLOYMENT",
                ex.getMessage());
    }

    @Test
    void getOrCreateDefaultsToNotPublished() {
        UUID brainId = UUID.randomUUID();
        Brain brain = new Brain(brainId, "test-brain", "Test Brain");
        when(brains.findById(brainId)).thenReturn(Optional.of(brain));
        when(profiles.findByBrainId(brainId)).thenReturn(Optional.empty());

        BrainProfile profile = service.getOrCreate(brainId);

        assertEquals(false, profile.isPublicEnabled());
        assertEquals(List.of(), profile.getAllowedDomains());
    }

    @Test
    void updateRejectsPublicEnabledWithoutAllowedDomains() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = request("PUBLIC_SITE", List.of());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.update(brainId, req));

        assertEquals("allowedDomains is required when public access is enabled", ex.getMessage());
    }

    @Test
    void updateNormalizesAllowedDomainOriginsToHosts() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = request("PUBLIC_SITE",
                List.of("https://Example.com/path", "example.com", "https://www.example.com"));

        BrainProfile profile = service.update(brainId, req);

        assertEquals(List.of("example.com", "www.example.com"), profile.getAllowedDomains());
    }

    private BrainProfileRequest request(String mode, List<String> allowedDomains) {
        return new BrainProfileRequest(
                mode,
                "Purpose",
                "Audience",
                "Personality",
                "professional",
                "intermediate",
                "balanced",
                0.9,
                "Ask one focused clarifying question.",
                "Escalate low-confidence requests.",
                "required_when_sources_used",
                "Recommend relevant pages.",
                "Source-grounded.",
                true,
                allowedDomains);
    }
}
