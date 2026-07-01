import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import Corpus from "./Corpus";
import { Stats } from "../types";

vi.mock("../api", () => ({
  api: {
    get: vi.fn(),
    upload: vi.fn(),
    patch: vi.fn(),
    del: vi.fn(),
    post: vi.fn(),
  },
}));

import { api } from "../api";

const stats: Stats = {
  brain: { id: "brain-1", companyName: "Generic", slug: "generic" },
  corpus: { activeDocuments: 1, totalDocuments: 1, chunks: 3 },
};

describe("Corpus", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.get).mockImplementation(async (path: string) => {
      if (path === "/api/ai/documents") {
        return [];
      }
      if (path === "/api/ai/admin/ingestion-quality") {
        return {
          brainId: "brain-1",
          documentCount: 1,
          activeDocumentCount: 1,
          chunkCount: 3,
          embeddedChunkCount: 2,
          chunksMissingEmbeddingCount: 1,
          parentChunkCount: 1,
          childChunkCount: 2,
          orphanChildChunkCount: 1,
          emptyChunkCount: 0,
          duplicateChunkTextGroups: 0,
          chunksMissingCitationMetadata: 1,
          documents: [],
          warnings: ["Child chunks missing embeddings: 1", "Orphan child chunks: 1"],
        };
      }
      throw new Error(`unexpected path ${path}`);
    });
  });

  it("loads and renders ingestion quality metrics", async () => {
    render(<Corpus stats={stats} onCorpusChanged={vi.fn()} />);

    await waitFor(() => {
      expect(api.get).toHaveBeenCalledWith("/api/ai/admin/ingestion-quality");
    });
    expect(await screen.findByText("Ingestion quality")).toBeTruthy();
    expect(screen.getByText("2 / 3")).toBeTruthy();
    expect(screen.getByText("Child chunks missing embeddings: 1")).toBeTruthy();
    expect(screen.getByText("Orphan child chunks: 1")).toBeTruthy();
  });
});
