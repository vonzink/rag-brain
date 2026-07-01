package com.ragbrain.rag.service.retrieval;

import com.ragbrain.rag.domain.BrainSourceLink;
import com.ragbrain.rag.domain.LinkAuthority;
import com.ragbrain.rag.domain.SourceType;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Tiers and orders the collected side-evidence by trust authority (spec §6.4,
 * §7.6). Pure {@code @Service} — no injected collaborators.
 *
 * <p>This only re-orders the side-evidence
 * {@link RetrievalPlannerService#collect} produces so downstream prompt assembly,
 * response shaping, and trace output can present it trust-first. It still does
 * NOT touch the corpus retrieval or reranker path that grounds the answer body.
 */
@Service
public class AuthorityFilterService {

    /**
     * Maps a link's external authority to its tier. The link row only ever
     * carries the three external authorities (tiers 1–2 come from elsewhere,
     * per {@link LinkAuthority} / spec §6.4). Exhaustive switch.
     */
    public AuthorityTier tierOf(LinkAuthority authority) {
        return switch (authority) {
            case PRIMARY -> AuthorityTier.PRIMARY_EXTERNAL;
            case SECONDARY -> AuthorityTier.SECONDARY_EXTERNAL;
            case BACKGROUND -> AuthorityTier.BACKGROUND;
        };
    }

    /**
     * Maps a corpus source's {@link SourceType} to its tier. Exhaustive switch:
     * {@code INTERNAL_POLICY → COMPANY_RULE} (company rule);
     * {@code AGENCY_GUIDELINE → PRIMARY_EXTERNAL} (agency guides + HUD = primary);
     * {@code INVESTOR_OVERLAY → SECONDARY_EXTERNAL} (approved supporting overlay);
     * {@code EDUCATIONAL → BACKGROUND} (borrower-facing context).
     *
     * <p>Defined for completeness; the current corpus path still uses its existing
     * retrieval/rerank flow rather than applying this authority tiering directly.
     */
    public AuthorityTier tierOf(SourceType sourceType) {
        return switch (sourceType) {
            case INTERNAL_POLICY -> AuthorityTier.COMPANY_RULE;
            case AGENCY_GUIDELINE -> AuthorityTier.PRIMARY_EXTERNAL;
            case INVESTOR_OVERLAY -> AuthorityTier.SECONDARY_EXTERNAL;
            case EDUCATIONAL -> AuthorityTier.BACKGROUND;
        };
    }

    /**
     * Returns a NEW {@link PlannedEvidence} with {@code links} stable-sorted
     * ascending by {@code tierOf(link.getAuthority()).rank()} (PRIMARY first, then
     * SECONDARY, then BACKGROUND). Ties keep the incoming order — the matcher's
     * {@code createdAt}-desc order — because Java's sort is stable and the
     * comparator has no tiebreaker. {@code pageGuides} are all tier
     * {@link AuthorityTier#CURRENT_PAGE_GUIDE}, so their relative order is
     * preserved as-is. Null-safe: {@code order(null)} and empty evidence return
     * {@link PlannedEvidence#empty()}.
     */
    public PlannedEvidence order(PlannedEvidence evidence) {
        if (evidence == null
                || (evidence.pageGuides().isEmpty() && evidence.links().isEmpty())) {
            return PlannedEvidence.empty();
        }
        List<BrainSourceLink> sortedLinks = evidence.links().stream()
                .sorted(Comparator.comparingInt(link -> tierOf(link.getAuthority()).rank()))
                .toList();
        return new PlannedEvidence(evidence.pageGuides(), sortedLinks);
    }
}
