export interface Stats {
  brain: { companyName: string; slug: string };
  corpus: { activeDocuments: number; totalDocuments: number; chunks: number };
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
}

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
