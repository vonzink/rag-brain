package com.ragbrain.rag.service.ai;

import com.ragbrain.rag.config.AiHttpClientFactory;
import com.ragbrain.rag.config.RagProperties;
import com.ragbrain.rag.domain.Brain;
import com.ragbrain.rag.provider.AiModelProvider;
import com.ragbrain.rag.provider.AiRequest;
import com.ragbrain.rag.provider.AiResponse;
import com.ragbrain.rag.provider.OpenAiCompatibleProvider;
import com.ragbrain.rag.repository.BrainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final LocalEndpointValidator localEndpointValidator;
    private final AiHttpClientFactory httpClientFactory;
    private final String localApiKey;

    /** Per-base-URL cache of providers bound to a brain's own local endpoint. */
    private final Map<String, AiModelProvider> localProviderCache = new ConcurrentHashMap<>();

    public ModelRouterService(List<AiModelProvider> providerBeans, RagProperties properties,
                              RuntimeSettings settings, BrainRepository brainRepository,
                              LocalEndpointValidator localEndpointValidator,
                              AiHttpClientFactory httpClientFactory,
                              @Value("${brain.providers.local.api-key:}") String localApiKey) {
        this.providers = providerBeans.stream()
                .collect(Collectors.toMap(AiModelProvider::getProviderName, Function.identity()));
        this.routing = properties.routing();
        this.settings = settings;
        this.brainRepository = brainRepository;
        this.localEndpointValidator = localEndpointValidator;
        this.httpClientFactory = httpClientFactory;
        // Local servers usually ignore the key; Spring AI still requires a non-blank one.
        this.localApiKey = (localApiKey == null || localApiKey.isBlank()) ? "not-needed" : localApiKey;

        if (!providers.containsKey(routing.defaultProvider())) {
            log.warn("Default AI provider '{}' is not configured. Available providers: {}",
                    routing.defaultProvider(), providers.keySet());
        }
        String fallback = routing.fallbackProvider();
        if (fallback != null && !fallback.isBlank() && !providers.containsKey(fallback)) {
            log.warn("Fallback AI provider '{}' is not configured. Available providers: {}",
                    fallback, providers.keySet());
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

        AiModelProvider primary;
        if ("local".equals(resolved.provider())
                && brain.getLocalBaseUrl() != null && !brain.getLocalBaseUrl().isBlank()) {
            primary = localProviderFor(brain.getLocalBaseUrl());   // per-brain endpoint
        } else {
            primary = providers.get(resolved.provider());
            if (primary == null) {
                log.warn("Configured {} provider '{}' is not registered; using default '{}'",
                        request.purpose() == AiRequest.Purpose.UTILITY ? "utility" : "answer",
                        resolved.provider(), routing.defaultProvider());
                primary = providers.get(routing.defaultProvider());
            }
            if (primary == null) {
                throw missingProvider(resolved.provider(), request.purpose());
            }
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

    /**
     * Returns (and caches) a provider bound to a brain's own local endpoint. The base
     * URL is SSRF-validated on first use (link-local/metadata always blocked; allowlist
     * enforced when set) — also enforced at admin write time. The per-request model is
     * passed via withModel(...), so one cached client per base URL serves any model.
     */
    private AiModelProvider localProviderFor(String baseUrl) {
        return localProviderCache.computeIfAbsent(baseUrl, url -> {
            localEndpointValidator.validate(url);   // SSRF check at first use
            return new OpenAiCompatibleProvider("local", url, localApiKey, "",
                    httpClientFactory.restClientBuilder());
        });
    }

    private IllegalStateException missingProvider(String provider, AiRequest.Purpose purpose) {
        String hint = switch (provider) {
            case "anthropic" -> "Set ANTHROPIC_API_KEY or choose another configured answer provider.";
            case "openai" -> "Set OPENAI_API_KEY or choose another configured provider.";
            case "local" -> "Set LOCAL_LLM_BASE_URL and a local model, or choose another provider.";
            default -> "Configure this provider in environment variables or choose a registered provider.";
        };
        return new IllegalStateException("AI provider '" + provider + "' is not configured for "
                + purpose.name().toLowerCase() + " requests. " + hint
                + " Registered providers: " + providers.keySet());
    }

    private record ResolvedModel(String provider, String model) {}

    public Set<String> providerNames() {
        return java.util.Set.copyOf(providers.keySet());
    }

    public record RoutedResponse(AiResponse response, boolean fallbackUsed) {
    }
}
