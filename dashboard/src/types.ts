export interface Stats {
  brain: { id: string; companyName: string; slug: string };
  corpus: { activeDocuments: number; totalDocuments: number; chunks: number };
}

export interface BrainProfileDto {
  brainId: string;
  mode: "PUBLIC_SITE" | "PRIVATE_SITE" | "SECURE_DEPLOYMENT";
  purpose: string;
  audience: string;
  personality: string;
  tone: string;
  expertiseLevel: string;
  answerLength: string;
  confidenceTarget: number;
  clarificationPolicy: string;
  escalationPolicy: string;
  citationPolicy: string;
  ctaPolicy: string;
  disclaimer: string;
  publicEnabled: boolean;
  allowedDomains: string[];
}

export type BrainProfileRequest = Omit<BrainProfileDto, "brainId">;

export interface PublicAskRequest {
  sessionId: string;
  message: string;
  pageRoute: string | null;
  surface: "PUBLIC" | "INTERNAL" | "SECURE";
  facts: Record<string, unknown>;
}

export interface PublicRecommendedPage {
  label: string;
  url: string;
  reason: string;
}

export interface PublicAskResponse {
  responseType: "ANSWER" | "CLARIFY" | "NAVIGATE" | "ESCALATE";
  message: string | null;
  answer: string | null;
  clarifyingQuestion: string | null;
  missingFacts: string[];
  citations: Citation[];
  recommendedPages: PublicRecommendedPage[];
  confidence: number;
  nextAction: string | null;
  conversationId: string | null;
}

export interface DocumentDto {
  id: string; title: string; sourceName: string; sourceType: string;
  fileName: string; documentVersion: string | null;
  effectiveDate: string | null; expirationDate: string | null; active: boolean;
}

export interface SyncResult {
  fileName: string; action: string; reason: string | null;
  executed: boolean; succeeded: boolean; error: string | null;
}
export interface SyncReport { dryRun: boolean; summary: Record<string, number>; results: SyncResult[] }

export interface SettingsResponse {
  effective: Record<string, string | number | boolean | null>;
  overrides: Record<string, string>;
  providers: { name: string; configured: boolean }[];
}

export interface Citation {
  source_name: string | null; document_name: string | null;
  section: string | null; page_number: string | null; effective_date: string | null;
}
export interface AskResponse {
  conversationId: string; answer: string; citations: Citation[];
  confidence: number; humanEscalationRequired: boolean; disclaimer: string;
  recommendedPage?: RecommendedPage | null;
  links?: Link[] | null;
  nextAction?: string | null;
  traceId?: string | null;
}

export interface RecommendedPage { route: string; label: string }
export interface Link { name: string; url: string; authority: string }

export interface RetrievedChunk {
  content: string; sourceName: string; documentName: string;
  section: string | null; pageNumber: number | null; combinedScore: number;
}
export interface RetrievalResult { chunks: RetrievedChunk[]; confidence: number; sufficientEvidence: boolean }

export interface AuditRow {
  id: string; createdAt: string; question: string; confidence: number | null;
  modelProvider: string | null; modelName: string | null;
  fallbackUsed: boolean; escalated: boolean;
}
export interface AuditPage { items: AuditRow[]; page: number; size: number; total: number }
export interface AuditDetail extends AuditRow {
  rewrittenQuestion: string | null; answer: string | null;
  sources: Record<string, unknown>[];
}

export interface RuleState {
  key: string; content: string; source: "pack" | "custom";
  updatedAt: string | null; updatedBy: string | null;
}
export interface RulesResponse { hard: RuleState; guidance: RuleState }
export interface RuleRevisionDto {
  revision: number; createdAt: string; createdBy: string;
  reverted: boolean; content: string | null;
}

export interface VocabState {
  content: string; source: "pack" | "custom";
  updatedAt: string | null; updatedBy: string | null; entries: number;
}
export interface VocabRevisionDto {
  revision: number; createdAt: string; createdBy: string;
  reverted: boolean; content: string | null;
}
export interface DocumentUpdate {
  title: string; sourceName: string; sourceType: string;
  documentVersion: string | null;
  effectiveDate: string | null; expirationDate: string | null;
}

export interface SourceLinkDto {
  id: string;
  name: string;
  url: string;
  domain: string | null;
  authority: string;
  topics: string[];
  freshness_required: boolean;
  allowed_use: string[];
  do_not_use_for: string[];
  surface: string;
  active: boolean;
  created_at: string | null;
  created_by: string | null;
  updated_at: string | null;
  updated_by: string | null;
}

export interface SourceLinkRequest {
  name: string;
  url: string;
  domain: string | null;
  authority: string;
  topics: string[];
  freshnessRequired: boolean;
  allowedUse: string[];
  doNotUseFor: string[];
  surface: string;
}

export interface LinkRef {
  label: string;
  url: string;
}

export interface PageGuideDto {
  id: string;
  route: string | null;
  title: string;
  purpose: string;
  surface: string;
  user_intents: string[];
  allowed_guidance: string[];
  internal_links: LinkRef[];
  source_link_ids: string[];
  topics: string[];
  active: boolean;
  created_at: string | null;
  created_by: string | null;
  updated_at: string | null;
  updated_by: string | null;
}

export interface PageGuideRequest {
  route: string | null;
  title: string;
  purpose: string;
  surface: string;
  userIntents: string[];
  allowedGuidance: string[];
  internalLinks: LinkRef[];
  sourceLinkIds: string[];
  topics: string[];
}

export interface BrainAdminDto {
  id: string;
  slug: string;
  displayName: string;
  packRef: string | null;
  sourceType: string | null;       // "local" | "s3" | null
  s3Bucket: string | null;
  s3Prefix: string | null;
  s3Region: string | null;
  localPath: string | null;
  answerProvider: string | null;
  answerModel: string | null;
  utilityProvider: string | null;
  utilityModel: string | null;
  isDefault: boolean;
  isActive: boolean;
}

export interface BrainCreateRequest {
  slug: string;
  displayName: string;
  packRef?: string;          // omitted when generating a starter pack
  disclaimer?: string;       // only used when generating
  sourceType: "local" | "s3";
  s3Bucket: string | null;
  s3Prefix: string | null;
  s3Region: string | null;
  localPath: string | null;
  answerProvider: string;
  answerModel: string;
  utilityProvider: string;
  utilityModel: string;
}
