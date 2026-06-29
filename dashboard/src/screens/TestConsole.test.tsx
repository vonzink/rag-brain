import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import TestConsole from "./TestConsole";

vi.mock("../api", () => ({
  api: {
    post: vi.fn(),
    get: vi.fn(),
  },
  legacyAskPath: (slug: string) => `/api/ai/generic/ask?brain=${encodeURIComponent(slug)}`,
  publicAsk: vi.fn(),
}));

import { api, publicAsk } from "../api";

describe("TestConsole", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal("crypto", { randomUUID: () => "session-123" });
  });

  it("renders public clarify responses and shows the effective origin as read-only", async () => {
    vi.mocked(publicAsk).mockResolvedValue({
      responseType: "CLARIFY",
      message: "Need one more detail.",
      answer: null,
      clarifyingQuestion: "Which loan program is this for?",
      missingFacts: ["loanProgram"],
      citations: [],
      recommendedPages: [],
      confidence: 0.42,
      nextAction: "Ask the user for the loan program.",
      conversationId: "conv-1",
      disclaimer: null,
      humanEscalationRequired: false,
    });

    render(<TestConsole slug="msfg" />);

    await userEvent.click(screen.getByRole("button", { name: "Public ask" }));
    const originInput = screen.getByDisplayValue(window.location.origin) as HTMLInputElement;
    expect(originInput.readOnly).toBe(true);

    await userEvent.type(screen.getByPlaceholderText("public token"), "pub_test_token");
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));

    await waitFor(() => {
      expect(publicAsk).toHaveBeenCalledWith(
        "msfg",
        "pub_test_token",
        expect.objectContaining({
          sessionId: "dashboard-session-123",
          message: "What is PMI?",
          surface: "PUBLIC",
        }),
      );
    });
    expect(await screen.findByText("Which loan program is this for?")).toBeTruthy();
    expect(screen.getByText("Missing: loanProgram")).toBeTruthy();
    expect(screen.queryByText(/trace/i)).toBeNull();
  });

  it("shows public-mode API errors", async () => {
    vi.mocked(publicAsk).mockRejectedValue(new Error("origin blocked"));

    render(<TestConsole slug="msfg" />);

    await userEvent.click(screen.getByRole("button", { name: "Public ask" }));
    await userEvent.type(screen.getByPlaceholderText("public token"), "pub_test_token");
    await userEvent.click(screen.getByRole("button", { name: "Ask" }));

    expect(await screen.findByText("origin blocked")).toBeTruthy();
  });

  it("routes full ask through the configured legacy endpoint with the target brain query", async () => {
    vi.mocked(api.post).mockResolvedValue({
      conversationId: "conv-1",
      answer: "PMI is mortgage insurance.",
      citations: [],
      confidence: 0.83,
      humanEscalationRequired: false,
      disclaimer: "General guidance only.",
      recommendedPage: null,
      links: [],
      nextAction: null,
      traceId: "trace-1",
    });

    render(<TestConsole slug="msfg" />);

    await userEvent.click(screen.getByRole("button", { name: "Ask" }));

    await waitFor(() => {
      expect(api.post).toHaveBeenCalledWith(
        "/api/ai/generic/ask?brain=msfg",
        expect.objectContaining({
          pageRoute: null,
          sessionId: "dashboard-session-123",
          question: "What is PMI?",
          surface: "PUBLIC",
        }),
      );
    });
  });
});
