package com.ragbrain.rag.service;

import com.ragbrain.rag.domain.Brain;
import com.ragbrain.rag.repository.BrainRepository;
import org.springframework.stereotype.Service;

/** Resolves which brain a request targets: an explicit ?brain=<slug>, else the default brain. */
@Service
public class BrainResolver {

    private final BrainRepository brains;

    public BrainResolver(BrainRepository brains) {
        this.brains = brains;
    }

    public Brain resolve(String brainSlug) {
        if (brainSlug != null && !brainSlug.isBlank()) {
            Brain brain = brains.findBySlug(brainSlug.trim())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + brainSlug));
            if (!brain.isActive()) {
                throw new IllegalArgumentException("Brain is inactive: " + brainSlug);
            }
            return brain;
        }
        return brains.findDefaultBrain()
                .orElseThrow(() -> new IllegalStateException("No default brain configured"));
    }
}
