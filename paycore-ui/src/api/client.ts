import type { ApiErrorBody, LoginResponse, TokenResponse } from './types';

const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');

export interface Session {
  customerId: string;
  email: string;
  sessionId: string;
  sessionToken: string;
  sessionExpiresAt: string;
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
}

const SESSION_KEY = 'paycore.session';

let session: Session | null = readSession();
const sessionListeners = new Set<(s: Session | null) => void>();

function readSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function getSession(): Session | null {
  return session;
}

export function setSession(next: Session | null) {
  session = next;
  try {
    if (next) sessionStorage.setItem(SESSION_KEY, JSON.stringify(next));
    else sessionStorage.removeItem(SESSION_KEY);
  } catch {
  }
  sessionListeners.forEach((listener) => listener(next));
}

export function onSessionChange(listener: (s: Session | null) => void) {
  sessionListeners.add(listener);
  return () => {
    sessionListeners.delete(listener);
  };
}

export function sessionFromLogin(response: LoginResponse): Session {
  return {
    customerId: response.customerId,
    email: response.email,
    sessionId: response.sessionId,
    sessionToken: response.sessionToken,
    sessionExpiresAt: response.sessionExpiresAt,
    accessToken: response.accessToken,
    accessTokenExpiresAt: response.accessTokenExpiresAt,
    refreshToken: response.refreshToken,
  };
}

const DEVICE_KEY = 'paycore.deviceId';
let fallbackDeviceId: string | null = null;

export function deviceId(): string {
  try {
    let id = localStorage.getItem(DEVICE_KEY);
    if (!id) {
      id = crypto.randomUUID();
      localStorage.setItem(DEVICE_KEY, id);
    }
    return id;
  } catch {
    fallbackDeviceId ??= crypto.randomUUID();
    return fallbackDeviceId;
  }
}

export type AuthMode = 'bearer' | 'none' | { token: string; label: string };

export interface ApiCall {
  id: number;
  at: Date;
  method: string;
  path: string;
  auth: string;
  status: number | null;
  ms: number;
  requestBody?: unknown;
  responseBody?: unknown;
}

const MAX_CALLS = 100;
let calls: ApiCall[] = [];
let nextCallId = 1;
const callListeners = new Set<(calls: ApiCall[]) => void>();

export function getCalls() {
  return calls;
}

export function onCallsChange(listener: (calls: ApiCall[]) => void) {
  callListeners.add(listener);
  return () => {
    callListeners.delete(listener);
  };
}

export function clearCalls() {
  calls = [];
  callListeners.forEach((listener) => listener(calls));
}

function record(call: ApiCall) {
  calls = [call, ...calls].slice(0, MAX_CALLS);
  callListeners.forEach((listener) => listener(calls));
}

const SECRET_FIELDS = new Set(['password', 'staffPassword']);
const TOKEN_FIELDS = new Set(['accessToken', 'refreshToken', 'sessionToken']);

function redact(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, v]) => {
        if (SECRET_FIELDS.has(key) && typeof v === 'string') return [key, '••••••••'];
        if ((key === 'bvn' || key === 'nin') && typeof v === 'string') return [key, `•••••••${v.slice(-4)}`];
        if (TOKEN_FIELDS.has(key) && typeof v === 'string') return [key, `${v.slice(0, 12)}…`];
        return [key, redact(v)];
      }),
    );
  }
  return value;
}

export class ApiError extends Error {
  readonly status: number;
  readonly body: ApiErrorBody;

  constructor(status: number, body: ApiErrorBody) {
    super(body.message ?? describeStatus(status));
    this.status = status;
    this.body = body;
  }
}

function describeStatus(status: number) {
  if (status === 0) return 'The PayCore API could not be reached';
  if (status === 401) return 'Not authenticated';
  if (status === 403) return 'You do not have permission to do that';
  return `Request failed with HTTP ${status}`;
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  form?: FormData;
  auth?: AuthMode;
  headers?: Record<string, string>;
  formSummary?: unknown;
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const auth = options.auth ?? 'bearer';
  const first = await send(path, options, auth);

  if (first.status === 401 && auth === 'bearer' && session?.refreshToken) {
    if (await refreshTokens()) {
      return unwrap<T>(await send(path, options, auth));
    }
  }

  return unwrap<T>(first);
}

interface Sent {
  status: number;
  body: unknown;
}

async function send(path: string, options: RequestOptions, auth: AuthMode): Promise<Sent> {
  const method = options.method ?? (options.body !== undefined || options.form ? 'POST' : 'GET');
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
  if (path.startsWith('/api/v1/auth/login') || path.startsWith('/api/v1/auth/google')) {
    headers['X-Device-Id'] = deviceId();
  }

  let authLabel = 'none';
  if (auth === 'bearer' && session) {
    headers.Authorization = `Bearer ${session.accessToken}`;
    authLabel = 'Bearer';
  } else if (typeof auth === 'object') {
    headers.Authorization = `Bearer ${auth.token}`;
    authLabel = auth.label;
  }

  let body: BodyInit | undefined;
  if (options.form) {
    body = options.form;
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }

  const started = performance.now();
  let status = 0;
  let responseBody: unknown = undefined;

  try {
    const response = await fetch(`${API_BASE}${path}`, { method, headers, body });
    status = response.status;
    const text = await response.text();
    if (text) {
      try {
        responseBody = JSON.parse(text);
      } catch {
        responseBody = text;
      }
    }
  } catch {
    status = 0;
  }

  record({
    id: nextCallId++,
    at: new Date(),
    method,
    path,
    auth: authLabel,
    status: status || null,
    ms: Math.round(performance.now() - started),
    requestBody: redact(options.formSummary ?? options.body),
    responseBody: redact(responseBody),
  });

  return { status, body: responseBody };
}

export async function openFile(path: string) {
  const preview = window.open('', '_blank');
  const started = performance.now();
  const response = await fetch(`${API_BASE}${path}`, {
    headers: session ? { Authorization: `Bearer ${session.accessToken}` } : {},
  }).catch(() => null);

  record({
    id: nextCallId++,
    at: new Date(),
    method: 'GET',
    path,
    auth: session ? 'Bearer' : 'none',
    status: response?.status ?? null,
    ms: Math.round(performance.now() - started),
    responseBody: response?.ok ? `(${response.headers.get('Content-Type')} file)` : undefined,
  });

  if (!response?.ok) {
    preview?.close();
    const body = response ? await response.json().catch(() => ({})) : {};
    throw new ApiError(response?.status ?? 0, body);
  }

  const url = URL.createObjectURL(await response.blob());
  if (preview) preview.location.href = url;
  else window.location.assign(url);
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

function unwrap<T>(sent: Sent): T {
  if (sent.status >= 200 && sent.status < 300) return sent.body as T;
  const body = sent.body && typeof sent.body === 'object' ? (sent.body as ApiErrorBody) : {};
  throw new ApiError(sent.status, body);
}

let refreshing: Promise<boolean> | null = null;

function refreshTokens(): Promise<boolean> {
  refreshing ??= (async () => {
    const current = session;
    if (!current) return false;
    try {
      const response = await api<TokenResponse>('/api/v1/auth/refresh', {
        body: { refreshToken: current.refreshToken },
        auth: 'none',
      });
      setSession({
        ...current,
        accessToken: response.accessToken,
        accessTokenExpiresAt: response.accessTokenExpiresAt,
        refreshToken: response.refreshToken,
      });
      return true;
    } catch {
      setSession(null);
      return false;
    }
  })().finally(() => {
    refreshing = null;
  });
  return refreshing;
}

export interface AccessClaims {
  sub: string;
  roles: string[];
  permissions: string[];
  iat: number;
  exp: number;
}

export function decodeClaims(token: string): AccessClaims | null {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const json = decodeURIComponent(
      atob(payload)
        .split('')
        .map((c) => `%${c.charCodeAt(0).toString(16).padStart(2, '0')}`)
        .join(''),
    );
    const claims = JSON.parse(json);
    return {
      sub: claims.sub,
      roles: claims.roles ?? [],
      permissions: claims.permissions ?? [],
      iat: claims.iat,
      exp: claims.exp,
    };
  } catch {
    return null;
  }
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    const missing = error.body.missing ?? [];
    const unmentioned = missing.filter((item) => !error.message.includes(item));
    return unmentioned.length ? `${error.message}: ${unmentioned.join(', ')}` : error.message;
  }
  return error instanceof Error ? error.message : 'Something went wrong';
}
