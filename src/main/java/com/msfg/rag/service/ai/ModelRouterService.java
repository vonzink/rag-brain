package com.msfg.rag.service.ai;

import com.msfg.rag.config.RagProperties;
import com.msfg.rag.domain.Brain;
import com.msfg.rag.provider.AiModelProvider;
import com.msfg.rag.provider.AiRequest;
import com.msfg.rag.provider.AiResponse;
import com.msfg.rag.repository.BrainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes requests to the per-purpose provider resolved per brain (Phase 4a),
 * falling back to global RuntimeSettings when brain columns are unset, and to
 * the configured fallback provider if the primary call fails.
 *
 * Resolution order (paired — never mixing a model name across providers):
 *   1. Brain's answer_/utility_provider + model column (when non-null/non-blank)
 *   2. Global RuntimeSettings answer/utility provider + model
 *   3. Fallback provider on exception (always withModel(null))
 *
 * CRITICAL invariant: the fallback provider always receives request.withModel(null)
 * — a primary's model name must never be sent to a different provider.
 */
@Service
public class ModelRouterService {

    private static final Logger log = LoggerFactory.getLogger(ModelRouterService.class);

    private final Map<String, AiModelProvider> providers;
    private final RagProperties.Routing routing;
    private final RuntimeSettings settings;
    private final BrainRepository brainRepository;

    public ModelRouterService(List<AiModelProvider> providerBeans, RagProperties properties,
                              RuntimeSettings settings, BrainRepository brainRepository) {
        this.providers = providerBeans.stream()
                .collect(Collectors.toMap(AiModelProvider::getProviderName, Function.identity()));
        this.routing = properties.routing();
        this.settings = settings;
        this.brainRepository = brainRepository;

        if (!providers.containsKey(routing.defaultProvider())) {
            throw new IllegalStateException(
                    "Default AI provider '" + routing.defaultProvider() + "' is not registered. "
                    + "Available: " + providers.keySet());
        }
        String fallback = routing.fallbackProvider();
        if (fallback != null && !fallback.isBlank() && !providers.containsKey(fallback)) {
            throw new IllegalStateException(
                    "Fallback AI provider '" + fallback + "' is not registered. "
                    + "Available: " + providers.keySet());
        }
    }

    /**
     * Generates a response for the given request, resolving the provider+model
     * from the brain's columns first, then global settings.
     *
     * @return the response plus whether the fallback provider had to be used
     */
    public RoutedResponse generate(AiRequest request, UUID brainId) {
        Brain brain = brainRepository.findById(brainId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + brainId));
        ResolvedModel resolved = resolve(brain, request.purpose());

        AiModelProvider primary = providers.get(resolved.provider());
        if (primary == null) {
            log.warn("Configured {} provider '{}' is not registered; using default '{}'",
                    request.purpose() == AiRequest.Purpose.UTILITY ? "utility" : "answer",
                    resolved.provider(), routing.defaultProvider());
            primary = providers.get(routing.defaultProvider());
        }

        try {
            return new RoutedResponse(primary.generate(request.withModel(resolved.model())), false);
        } catch (Exception primaryFailure) {
            log.error("Primary AI provider '{}' failed: {}",
                    primary.getProviderName(), primaryFailure.getMessage());

            AiModelProvider fallback = providers.get(routing.fallbackProvider());
            if (fallback == null || fallback == primary) {
                throw primaryFailure;
            }
            log.warn("Falling back to provider '{}'", fallback.getProviderName());
            // The fallback always runs its own default model — a primary's
            // model name must never be sent to a different provider.
            return new RoutedResponse(fallback.generate(request.withModel(null)), true);
        }
    }

    /**
     * Resolves a paired (provider, model) for the given purpose.
     * Uses the brain's column when set, else the global RuntimeSettings default.
     * A model name is only ever paired with its own provider — never mixed across providers.
     */
    private ResolvedModel resolve(Brain brain, AiRequest.Purpose purpose) {
        boolean utility = purpose == AiRequest.Purpose.UTILITY;
        String brainProvider = utility ? brain.getUtilityProvider() : brain.getAnswerProvider();
        String brainModel = utility ? brain.getUtilityModel() : brain.getAnswerModel();
        if (brainProvider != null && !brainProvider.isBlank()) {
            return new ResolvedModel(brainProvider, brainModel);
        }
        return utility
                ? new ResolvedModel(settings.utilityProvider(), settings.utilityModel())
                : new ResolvedModel(settings.answerProvider(), settings.answerModel());
    }

    private record ResolvedModel(String provider, String model) {}

    public Set<String> providerNames() {
        return java.util.Set.copyOf(providers.keySet());
    }

    public record RoutedResponse(AiResponse response, boolean fallbackUsed) {
    }
}
