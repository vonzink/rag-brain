import {
  BrainAdminDto,
  BrainCreateRequest,
  BrainProfileDto,
  BrainProfileRequest,
  PublicAskRequest,
  PublicAskResponse,
  SyncReport,
} from "./types";

const KEY_STORAGE = "rag-brain-admin-key";

export class AuthError extends Error {
  constructor() {
    super("Admin key missing or rejected");
  }
}

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export const adminKey = {
  get: () => sessionStorage.getItem(KEY_STORAGE),
  set: (key: string) => sessionStorage.setItem(KEY_STORAGE, key),
  clear: () => sessionStorage.removeItem(KEY_STORAGE),
};

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("X-Admin-Api-Key", adminKey.get() ?? "");
  if (init.body && !(init.body instanceof FormData)) headers.set("Content-Type", "application/json");

  const response = await fetch(path, { ...init, headers });
  if (response.status === 401) {
    adminKey.clear();
    throw new AuthError();
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { error?: string } | null;
    throw new ApiError(response.status, body?.error ?? `HTTP ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body: unknown) =>
    request<T>(path, { method: "PUT", body: JSON.stringify(body) }),
  patch: <T>(path: string, body: unknown) =>
    request<T>(path, { method: "PATCH", body: JSON.stringify(body) }),
  del: <T>(path: string) => request<T>(path, { method: "DELETE" }),
  upload: <T>(path: string, form: FormData) =>
    request<T>(path, { method: "POST", body: form }),
};

export const brainsApi = {
  list: () => api.get<BrainAdminDto[]>("/api/ai/admin/brains"),
  create: (body: BrainCreateRequest) => api.post<BrainAdminDto>("/api/ai/admin/brains", body),
  update: (id: string, body: BrainCreateRequest) =>
    api.put<BrainAdminDto>(`/api/ai/admin/brains/${id}`, body),
  activate: (id: string) => api.post<BrainAdminDto>(`/api/ai/admin/brains/${id}/activate`),
  sync: (id: string, dryRun: boolean) =>
    api.post<SyncReport>(`/api/ai/admin/brains/${id}/sync?dryRun=${dryRun}`),
  remove: (id: string) => api.del<BrainAdminDto>(`/api/ai/admin/brains/${id}`),
};

export const profileApi = {
  get: (brainId: string) => api.get<BrainProfileDto>(`/api/ai/admin/brains/${brainId}/profile`),
  update: (brainId: string, body: BrainProfileRequest) =>
    api.put<BrainProfileDto>(`/api/ai/admin/brains/${brainId}/profile`, body),
  rotatePublicToken: (brainId: string) =>
    api.post<{ token: string }>(`/api/ai/admin/brains/${brainId}/profile/public-token`, {}),
};

export async function publicAsk(slug: string, token: string, body: PublicAskRequest) {
  const headers = new Headers();
  headers.set("X-Public-Brain-Token", token);
  headers.set("Content-Type", "application/json");
  const response = await fetch(`/api/ai/public/${slug}/ask`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    const data = await response.json().catch(() => null) as { error?: string } | null;
    throw new Error(data?.error || `HTTP ${response.status}`);
  }
  return response.json() as Promise<PublicAskResponse>;
}
