package com.msfg.rag.controller;

import com.msfg.rag.domain.BrainMode;
import com.msfg.rag.domain.BrainProfile;
import com.msfg.rag.dto.BrainProfileRequest;
import com.msfg.rag.service.publicapi.PublicAccessService;
import com.msfg.rag.service.profile.BrainProfileService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBrainProfileControllerTest {

    private final BrainProfileService service = mock(BrainProfileService.class);
    private final PublicAccessService access = mock(PublicAccessService.class);
    private final AdminBrainProfileController controller = new AdminBrainProfileController(service, access);

    private BrainProfile profile(UUID brainId) {
        BrainProfile p = new BrainProfile();
        p.setBrainId(brainId);
        p.setMode(BrainMode.PUBLIC_SITE);
        p.setPurpose("Answer website questions.");
        p.setAudience("public visitor");
        p.setPersonality("Conversational and concise.");
        p.setTone("professional");
        p.setExpertiseLevel("intermediate");
        p.setAnswerLength("balanced");
        p.setConfidenceTarget(0.9);
        p.setClarificationPolicy("Ask one focused question.");
        p.setEscalationPolicy("Escalate low-confidence requests.");
        p.setCitationPolicy("required_when_sources_used");
        p.setCtaPolicy("Recommend pages.");
        p.setDisclaimer("Source-grounded.");
        p.setPublicEnabled(true);
        p.setAllowedDomains(List.of("example.com"));
        return p;
    }

    @Test
    void getReturnsDtoWithoutTokenHash() {
        UUID brainId = UUID.randomUUID();
        BrainProfile p = profile(brainId);
        p.setPublicTokenHash("secret-hash");
        when(service.getOrCreate(brainId)).thenReturn(p);

        var dto = controller.get(brainId);

        assertEquals(brainId, dto.brainId());
        assertEquals("PUBLIC_SITE", dto.mode());
        assertEquals(List.of("example.com"), dto.allowedDomains());
        assertEquals(false, dto.toString().contains("secret-hash"));
    }

    @Test
    void putRejectsOutOfRangeConfidenceTarget() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = new BrainProfileRequest(
                "PUBLIC_SITE", "Purpose", "Audience", "Personality", "tone",
                "intermediate", "balanced", 1.2, "clarify", "escalate",
                "required_when_sources_used", "cta", "disclaimer", true, List.of("example.com"));
        assertThrows(IllegalArgumentException.class, () -> controller.put(brainId, req));
    }

    @Test
    void putRejectsMissingRequestBody() {
        UUID brainId = UUID.randomUUID();

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> controller.put(brainId, null));

        assertEquals("request body is required", ex.getMessage());
    }

    @Test
    void putDelegatesValidRequest() {
        UUID brainId = UUID.randomUUID();
        BrainProfileRequest req = new BrainProfileRequest(
                "PUBLIC_SITE", "Purpose", "Audience", "Personality", "tone",
                "intermediate", "balanced", 0.91, "clarify", "escalate",
                "required_when_sources_used", "cta", "disclaimer", true, List.of("example.com"));
        when(service.update(brainId, req)).thenReturn(profile(brainId));

        controller.put(brainId, req);

        verify(service).update(brainId, req);
    }

    @Test
    void rotatePublicTokenReturnsPlainTokenOnce() {
        UUID brainId = UUID.randomUUID();
        when(access.rotateToken(brainId)).thenReturn("rb_pub_secret");
        AdminBrainProfileController c = new AdminBrainProfileController(service, access);

        assertEquals("rb_pub_secret", c.rotatePublicToken(brainId).token());
    }
}
