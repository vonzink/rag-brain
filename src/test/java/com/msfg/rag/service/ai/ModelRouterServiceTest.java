package com.msfg.rag.service.ai;

import com.msfg.rag.config.RagProperties;
import com.msfg.rag.domain.Brain;
import com.msfg.rag.provider.AiModelProvider;
import com.msfg.rag.provider.AiRequest;
import com.msfg.rag.provider.AiResponse;
import com.msfg.rag.repository.BrainRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.when;

class ModelRouterServiceTest {

    private static final UUID BRAIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final AiRequest REQUEST = AiRequest.forGuidelineAnswer("test prompt");

    private RagProperties properties(String defaultProvider, String fallbackProvider) {
        return new RagProperties(
                new RagProperties.Routing(defaultProvider, fallbackProvider),
                new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/test"),
                new RagProperties.Admin("test-key"),
                new RagProperties.RateLimit(10));
    }

    /** Default RuntimeSettings mock: answer=anthropic (no model), utility same. */
    private RuntimeSettings defaultSettings(String defaultProvider) {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn(defaultProvider);
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn(defaultProvider);
        when(s.utilityModel()).thenReturn(null);
        return s;
    }

    /** Brain stub with null provider/model columns (falls back to global settings). */
    private Brain brainWithNoOverrides() {
        Brain b = new Brain(BRAIN, "default", "Default Brain");
        // provider/model columns are null → resolver falls back to global settings
        return b;
    }

    /** Brain stub with explicit answer and utility provider+model. */
    private Brain brainWith(String answerProvider, String answerModel,
                            String utilityProvider, String utilityModel) {
        Brain b = new Brain(BRAIN, "test", "Test Brain");
        b.setAnswerProvider(answerProvider);
        b.setAnswerModel(answerModel);
        b.setUtilityProvider(utilityProvider);
        b.setUtilityModel(utilityModel);
        return b;
    }

    /** BrainRepository stub returning the given brain for BRAIN id. */
    private BrainRepository brainRepo(Brain brain) {
        BrainRepository repo = mock(BrainRepository.class);
        when(repo.findById(BRAIN)).thenReturn(Optional.of(brain));
        return repo;
    }

    // ------------------------------------------------------------------
    // Existing tests — updated to pass BrainRepository mock

    @Test
    void routesToDefaultProvider() {
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = new ModelRouterService(
                List.of(capturingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                repo);

        var routed = router.generate(REQUEST, BRAIN);

        assertEquals("anthropic", routed.response().providerName());
        assertFalse(routed.fallbackUsed());
    }

    @Test
    void fallsBackWhenPrimaryFails() {
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = new ModelRouterService(
                List.of(failingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                repo);

        var routed = router.generate(REQUEST, BRAIN);

        assertEquals("openai", routed.response().providerName());
        assertTrue(routed.fallbackUsed());
    }

    @Test
    void throwsWhenPrimaryFailsAndNoFallbackConfigured() {
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = new ModelRouterService(
                List.of(failingProvider("anthropic")),
                properties("anthropic", "anthropic"),
                defaultSettings("anthropic"),
                repo);

        assertThrows(RuntimeException.class, () -> router.generate(REQUEST, BRAIN));
    }

    @Test
    void rejectsUnknownDefaultProviderAtStartup() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> new ModelRouterService(
                List.of(capturingProvider("openai")),
                properties("anthropic", "openai"),
                s,
                mock(BrainRepository.class)));
    }

    @Test
    void rejectsUnknownFallbackProviderAtStartup() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> new ModelRouterService(
                List.of(capturingProvider("anthropic")),
                properties("anthropic", "bogus"),
                s,
                mock(BrainRepository.class)));
    }

    // ------------------------------------------------------------------
    // Settings-fallback tests (brain has null overrides → uses global settings)

    @Test
    void answerPurposeUsesAnswerSettings() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("openai");
        when(s.answerModel()).thenReturn("gpt-x");
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider openai = capturingProvider("openai");
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = new ModelRouterService(
                List.of(openai, capturingProvider("anthropic")),
                properties("openai", "anthropic"),
                s,
                repo);

        router.generate(AiRequest.forGuidelineAnswer("test"), BRAIN);

        assertEquals("gpt-x", openai.lastRequest().model());
    }

    @Test
    void utilityPurposeUsesUtilitySettings() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("openai");
        when(s.answerModel()).thenReturn("gpt-x");
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider anthropic = capturingProvider("anthropic");
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = new ModelRouterService(
                List.of(capturingProvider("openai"), anthropic),
                properties("openai", "anthropic"),
                s,
                repo);

        router.generate(AiRequest.forUtility("rerank prompt", 0.0, 800), BRAIN);

        assertTrue(anthropic.wasCalled(), "anthropic (utility provider) must have been called");
        assertNull(anthropic.lastRequest().model());
    }

    @Test
    void unknownConfiguredProviderFallsBackToDefaultProvider() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("gemini"); // not registered
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn("gemini");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider anthropic = capturingProvider("anthropic");
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = new ModelRouterService(
                List.of(anthropic, capturingProvider("openai")),
                properties("anthropic", "openai"),
                s,
                repo);

        var routed = router.generate(AiRequest.forGuidelineAnswer("test"), BRAIN);

        // should fall back to routing.defaultProvider() = anthropic
        assertEquals("anthropic", routed.response().providerName());
        assertFalse(routed.fallbackUsed());
        assertTrue(anthropic.wasCalled());
    }

    @Test
    void fallbackProviderReceivesNoModelOverride() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn("claude-opus-4-5");
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider openai = capturingProvider("openai");
        BrainRepository repo = brainRepo(brainWithNoOverrides());
        var router = new ModelRouterService(
                List.of(failingProvider("anthropic"), openai),
                properties("anthropic", "openai"),
                s,
                repo);

        var routed = router.generate(AiRequest.forGuidelineAnswer("test"), BRAIN);

        assertTrue(routed.fallbackUsed());
        // fallback must NOT receive the primary's model override
        assertNull(openai.lastRequest().model(),
                "Fallback provider must receive model=null, not the primary's model name");
    }

    @Test
    void providerNames() {
        var router = new ModelRouterService(
                List.of(capturingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                mock(BrainRepository.class));

        assertEquals(Set.of("anthropic", "openai"), router.providerNames());
    }

    // ------------------------------------------------------------------
    // Brain-aware routing tests (Phase 4a)

    @Test
    void answerRequestUsesBrainAnswerProviderAndModel() {
        // brain explicitly configured: answer=anthropic/claude-x, utility=openai/gpt-x
        Brain brain = brainWith("anthropic", "claude-x", "openai", "gpt-x");
        BrainRepository repo = brainRepo(brain);

        CapturingProvider anthropic = capturingProvider("anthropic");
        var router = new ModelRouterService(
                List.of(anthropic, capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                repo);

        var routed = router.generate(AiRequest.forGuidelineAnswer("p"), BRAIN);

        // must use the brain's paired (anthropic, claude-x)
        verify(repo).findById(BRAIN);
        assertEquals("anthropic", routed.response().providerName());
        assertEquals("claude-x", anthropic.lastRequest().model());
        assertFalse(routed.fallbackUsed());
    }

    @Test
    void utilityRequestUsesBrainUtilityProviderAndModel() {
        // brain's utility lane: openai/gpt-x
        Brain brain = brainWith("anthropic", "claude-x", "openai", "gpt-x");
        BrainRepository repo = brainRepo(brain);

        CapturingProvider openai = capturingProvider("openai");
        var router = new ModelRouterService(
                List.of(capturingProvider("anthropic"), openai),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                repo);

        router.generate(AiRequest.forUtility("rerank", 0.0, 800), BRAIN);

        assertTrue(openai.wasCalled(), "utility request must go to brain's utility provider");
        assertEquals("gpt-x", openai.lastRequest().model());
    }

    @Test
    void brainWithNullAnswerProviderFallsBackToGlobalSettings() {
        // brain has no answer_provider set → must use global settings
        Brain brain = brainWithNoOverrides(); // all null
        BrainRepository repo = brainRepo(brain);

        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn("claude-global");
        when(s.utilityProvider()).thenReturn("openai");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider anthropic = capturingProvider("anthropic");
        var router = new ModelRouterService(
                List.of(anthropic, capturingProvider("openai")),
                properties("anthropic", "openai"),
                s,
                repo);

        router.generate(AiRequest.forGuidelineAnswer("test"), BRAIN);

        assertEquals("claude-global", anthropic.lastRequest().model());
    }

    @Test
    void brainWithNullUtilityProviderFallsBackToGlobalSettings() {
        // brain has no utility_provider set → falls back to global utility settings
        Brain brain = brainWithNoOverrides();
        BrainRepository repo = brainRepo(brain);

        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn("openai");
        when(s.utilityModel()).thenReturn("gpt-global");

        CapturingProvider openai = capturingProvider("openai");
        var router = new ModelRouterService(
                List.of(capturingProvider("anthropic"), openai),
                properties("anthropic", "openai"),
                s,
                repo);

        router.generate(AiRequest.forUtility("test", 0.0, 800), BRAIN);

        assertTrue(openai.wasCalled());
        assertEquals("gpt-global", openai.lastRequest().model());
    }

    @Test
    void brainFallbackOnErrorStillUsesNullModel() {
        // even with brain-aware routing, the fallback path must use withModel(null)
        Brain brain = brainWith("anthropic", "claude-x", "openai", "gpt-x");
        BrainRepository repo = brainRepo(brain);

        CapturingProvider openai = capturingProvider("openai");
        var router = new ModelRouterService(
                List.of(failingProvider("anthropic"), openai),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"),
                repo);

        var routed = router.generate(AiRequest.forGuidelineAnswer("test"), BRAIN);

        assertTrue(routed.fallbackUsed());
        assertNull(openai.lastRequest().model(),
                "Fallback provider must receive model=null even in brain-aware routing");
    }

    @Test
    void unknownBrainIdThrows() {
        BrainRepository repo = mock(BrainRepository.class);
        UUID unknown = UUID.randomUUID();
        when(repo.findById(unknown)).thenReturn(Optional.empty());

        var router = new ModelRouterService(
                List.of(capturingProvider("anthropic")),
                properties("anthropic", "anthropic"),
                defaultSettings("anthropic"),
                repo);

        assertThrows(IllegalArgumentException.class,
                () -> router.generate(REQUEST, unknown));
    }

    // ------------------------------------------------------------------

    /** A provider that records the last AiRequest it received. */
    private CapturingProvider capturingProvider(String name) {
        return new CapturingProvider(name);
    }

    private static class CapturingProvider implements AiModelProvider {
        private final String name;
        private AiRequest lastRequest;

        CapturingProvider(String name) {
            this.name = name;
        }

        @Override
        public AiResponse generate(AiRequest request) {
            this.lastRequest = request;
            return new AiResponse("{\"answer\":\"ok\"}", name, name + "-model", 100, 50);
        }

        @Override
        public String getProviderName() {
            return name;
        }

        @Override
        public String getModelName() {
            return name + "-model";
        }

        AiRequest lastRequest() {
            return lastRequest;
        }

        boolean wasCalled() {
            return lastRequest != null;
        }
    }

    private AiModelProvider failingProvider(String name) {
        return new AiModelProvider() {
            @Override
            public AiResponse generate(AiRequest request) {
                throw new RuntimeException(name + " API unavailable");
            }

            @Override
            public String getProviderName() {
                return name;
            }

            @Override
            public String getModelName() {
                return name + "-model";
            }
        };
    }
}
