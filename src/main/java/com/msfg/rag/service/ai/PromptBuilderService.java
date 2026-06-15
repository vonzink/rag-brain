package com.msfg.rag.service.ai;

import com.msfg.rag.pack.DomainPackRegistry;
import com.msfg.rag.service.retrieval.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Builds the final prompt sent to the LLM. The template and disclaimer come
 * from the brain's pack (resolved per request via DomainPackRegistry), keeping
 * all company-specific text out of code. Hard rules and guidance are injected
 * live from {@link RulesService} (pack defaults until edited) and remain
 * compliance-critical: the model must answer only from the supplied source
 * context, never from general knowledge.
 */
@Service
public class PromptBuilderService {

    private final DomainPackRegistry registry;
    private final RulesService rules;

    public PromptBuilderService(DomainPackRegistry registry, RulesService rules) {
        this.registry = registry;
        this.rules = rules;
    }

    /** The brain's public disclaimer, appended to every website response. */
    public String disclaimer(UUID brainId) {
        return registry.bundle(brainId).pack().disclaimer();
    }

    public String build(String question, List<RetrievedChunk> chunks, UUID brainId) {
        var pack = registry.bundle(brainId).pack();
        return pack.promptTemplate().formatted(
                rules.effectiveHard(brainId),
                rules.effectiveGuidance(brainId),
                formatContext(chunks),
                question,
                pack.disclaimer());
    }

    /**
     * Each chunk is labeled [Source N] with its citation metadata so the model
     * can attribute statements to specific sources.
     */
    private String formatContext(List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return "(no source context found)";
        }
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (RetrievedChunk chunk : chunks) {
            sb.append("[Source ").append(n++).append("]\n");
            sb.append("source_name: ").append(chunk.sourceName()).append('\n');
            sb.append("document_name: ").append(chunk.documentName()).append('\n');
            if (chunk.section() != null) {
                sb.append("section: ").append(chunk.section()).append('\n');
            }
            if (chunk.pageNumber() != null) {
                sb.append("page_number: ").append(chunk.pageNumber()).append('\n');
            }
            if (chunk.effectiveDate() != null) {
                sb.append("effective_date: ").append(chunk.effectiveDate()).append('\n');
            }
            sb.append("content:\n").append(chunk.content()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
