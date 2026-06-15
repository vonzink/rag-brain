import { BrainAdminDto, BrainCreateRequest, SyncReport } from "./types";

const KEY_STORAGE = "rag-brain-admin-key";

export class AuthError extends Error {
  constructor() {
    super("Admin key missing or rejected");
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
    throw new Error(body?.error ?? `HTTP ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body: unknown) =>
    request<T>(path, { method: "PUT", body: JSON.stringify(body) }),
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
