package com.msfg.rag.service.profile;

import com.msfg.rag.domain.BrainMode;
import com.msfg.rag.domain.BrainProfile;
import com.msfg.rag.dto.BrainProfileRequest;
import com.msfg.rag.repository.BrainProfileRepository;
import com.msfg.rag.repository.BrainRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BrainProfileService {

    private final BrainRepository brains;
    private final BrainProfileRepository profiles;

    public BrainProfileService(BrainRepository brains, BrainProfileRepository profiles) {
        this.brains = brains;
        this.profiles = profiles;
    }

    @Transactional
    public BrainProfile getOrCreate(UUID brainId) {
        brains.findById(brainId).orElseThrow(() -> new IllegalArgumentException("Brain not found: " + brainId));
        return profiles.findByBrainId(brainId).orElseGet(() -> {
            BrainProfile profile = new BrainProfile();
            profile.setBrainId(brainId);
            profile.setMode(BrainMode.PUBLIC_SITE);
            profile.setPurpose("Answer questions from approved sources.");
            profile.setAudience("public visitor");
            profile.setPersonality("Conversational, concise, source-grounded assistant.");
            profile.setTone("professional");
            profile.setExpertiseLevel("intermediate");
            profile.setAnswerLength("balanced");
            profile.setConfidenceTarget(0.90);
            profile.setClarificationPolicy("Ask one focused clarifying question when required facts are missing.");
            profile.setEscalationPolicy("Escalate personalized, unsupported, sensitive, or low-confidence requests.");
            profile.setCitationPolicy("required_when_sources_used");
            profile.setCtaPolicy("Recommend relevant pages or a human handoff when useful.");
            profile.setDisclaimer("This answer is generated from approved source context and may be incomplete.");
            profile.setPublicEnabled(true);
            profile.setAllowedDomains(List.of());
            return profiles.save(profile);
        });
    }

    @Transactional
    public BrainProfile update(UUID brainId, BrainProfileRequest req) {
        BrainProfile profile = getOrCreate(brainId);
        profile.setMode(parseMode(req.mode()));
        profile.setPurpose(required(req.purpose(), "purpose"));
        profile.setAudience(required(req.audience(), "audience"));
        profile.setPersonality(required(req.personality(), "personality"));
        profile.setTone(required(req.tone(), "tone"));
        profile.setExpertiseLevel(required(req.expertiseLevel(), "expertiseLevel"));
        profile.setAnswerLength(required(req.answerLength(), "answerLength"));
        profile.setConfidenceTarget(req.confidenceTarget());
        profile.setClarificationPolicy(required(req.clarificationPolicy(), "clarificationPolicy"));
        profile.setEscalationPolicy(required(req.escalationPolicy(), "escalationPolicy"));
        profile.setCitationPolicy(required(req.citationPolicy(), "citationPolicy"));
        profile.setCtaPolicy(required(req.ctaPolicy(), "ctaPolicy"));
        profile.setDisclaimer(required(req.disclaimer(), "disclaimer"));
        profile.setPublicEnabled(req.publicEnabled());
        profile.setAllowedDomains(normalizeAllowedDomains(req.allowedDomains()));
        return profiles.save(profile);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.strip();
    }

    private static BrainMode parseMode(String value) {
        String mode = required(value, "mode");
        try {
            return BrainMode.valueOf(mode);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "mode must be one of PUBLIC_SITE, PRIVATE_SITE, SECURE_DEPLOYMENT");
        }
    }

    private static List<String> normalizeAllowedDomains(List<String> allowedDomains) {
        if (allowedDomains == null) {
            return List.of();
        }
        if (allowedDomains.stream().anyMatch(domain -> domain == null)) {
            throw new IllegalArgumentException("allowedDomains must not contain null entries");
        }
        return allowedDomains.stream()
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .map(String::toLowerCase)
                .distinct()
                .toList();
    }
}
