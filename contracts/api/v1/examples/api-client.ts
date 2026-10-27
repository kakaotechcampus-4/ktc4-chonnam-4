import type { ApiErrorDetail, ApiResponse, CsrfData } from "./types.ts";

export type AuthMode = "public" | "instructor" | "child";
export interface CallOptions { signal?: AbortSignal | undefined; }
export interface AccessTokenStore { get(): string | null; set(value: string): void; clear(): void; }
export interface RequestOptions extends CallOptions {
  method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  auth: AuthMode;
  body?: unknown;
  form?: FormData;
  idempotencyKey?: string;
  expectedStatuses: readonly number[];
  draft?: boolean;
  conditional?: boolean;
}
export interface ClientConfig {
  origin: string;
  tokenStore: AccessTokenStore;
  fetchImpl?: typeof fetch;
  allowDrafts?: boolean;
  /** 서버 활성화·필수 운영 연결을 확인한 경우에만 true */
  allowConditional?: boolean;
  onInstructorUnauthorized?: () => void;
}
export interface ApiClient {
  request<T>(path: string, options: RequestOptions): Promise<ApiResponse<T>>;
  requestEmpty(path: string, options: RequestOptions): Promise<void>;
  getCsrf(signal?: AbortSignal): Promise<ApiResponse<CsrfData>>;
  refreshCsrf(signal?: AbortSignal): Promise<ApiResponse<CsrfData>>;
  rememberLoginToken(token: string): void;
  forgetLoginToken(): void;
}
export class ApiHttpError extends Error {
  public readonly detail: ApiErrorDetail;
  constructor(detail: ApiErrorDetail) {
    super(detail.message);
    this.detail = detail;
    this.name = "ApiHttpError";
  }
}
export class ApiNetworkError extends Error {
  constructor() {
    super("응답을 확인하지 못했습니다. 생성 요청은 원래 키와 입력을 유지하고 상태를 확인하세요.");
    this.name = "ApiNetworkError";
  }
}
export class ApiContractError extends Error {
  constructor(message: string) { super(message); this.name = "ApiContractError"; }
}
export class DraftContractError extends Error {
  constructor() {
    super("미구현/미정 계약입니다. Mock 검증 또는 FE/BE 승인·배포 확인 전 호출하지 마세요.");
    this.name = "DraftContractError";
  }
}

export class ConditionalContractError extends Error {
  constructor() {
    super("조건부 API입니다. 서버 활성화와 운영 연결을 확인한 뒤 allowConditional을 설정하세요.");
    this.name = "ConditionalContractError";
  }
}

export function createSessionTokenStore(
  storage: Storage,
  key = "neuringo.accessToken"
): AccessTokenStore {
  // key는 예제값입니다. 기존 FE 저장소/키가 있으면 그 값으로 맞추세요.
  return {
    get: () => storage.getItem(key),
    set: (value) => storage.setItem(key, value),
    clear: () => storage.removeItem(key)
  };
}
export function queryString(query?: object): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === undefined || value === null) continue;
    if (!["string", "number", "boolean"].includes(typeof value)) {
      throw new ApiContractError("Query에는 문자열/숫자/boolean만 사용하세요.");
    }
    params.set(key, String(value)); // 0/false를 누락하지 않습니다.
  }
  const encoded = params.toString();
  return encoded ? "?" + encoded : "";
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
function assertEnvelope<T>(value: unknown): ApiResponse<T> {
  if (!isObject(value) || !("data" in value) ||
      !isObject(value.meta) || typeof value.meta.traceId !== "string") {
    throw new ApiContractError("공통 data/meta 응답 형식과 다릅니다.");
  }
  // 공통 envelope만 검사합니다. T의 세부 DTO 검증은 별도 Schema/계약 테스트가 필요합니다.
  return value as unknown as ApiResponse<T>;
}
async function readJson(response: Response): Promise<unknown> {
  try { return await response.json(); }
  catch { throw new ApiContractError("JSON 응답을 해석하지 못했습니다."); }
}
async function httpError(response: Response): Promise<ApiHttpError> {
  let parsed: unknown;
  try { parsed = await response.json(); } catch { parsed = undefined; }
  const error: Record<string, unknown> = isObject(parsed) && isObject(parsed.error) ? parsed.error : {};
  return new ApiHttpError({
    status: response.status,
    code: typeof error.code === "string" ? error.code : "HTTP_ERROR",
    message: typeof error.message === "string" ? error.message : "요청을 처리하지 못했습니다.",
    path: typeof error.path === "string" ? error.path : "",
    traceId: typeof error.traceId === "string" ? error.traceId : "",
    fieldErrors: Array.isArray(error.fieldErrors)
      ? error.fieldErrors.filter(isObject).filter(x => typeof x.field === "string" && typeof x.message === "string")
          .map(x => ({ field: x.field as string, message: x.message as string }))
      : []
  });
}

export function createApiClient(config: ClientConfig): ApiClient {
  const origin = config.origin.trim().replace(/\/+$/, "");
  if (origin.endsWith("/api/v1")) {
    throw new ApiContractError("origin에는 서버 주소만 넣으세요. /api/v1은 함수 경로에 포함돼 있습니다.");
  }
  const fetcher = config.fetchImpl ?? globalThis.fetch.bind(globalThis);
  let csrf: CsrfData | null = null;

  async function send(path: string, options: RequestOptions): Promise<Response> {
    if (options.draft && !config.allowDrafts) throw new DraftContractError();
    if (options.conditional && !config.allowConditional) throw new ConditionalContractError();
    const headers = new Headers({ Accept: "application/json" });
    if (options.auth === "instructor") {
      const token = config.tokenStore.get();
      if (!token) throw new ApiContractError("강사 토큰을 먼저 저장하세요.");
      headers.set("Authorization", "Bearer " + token);
    }
    // public/child에는 남아 있는 강사 토큰을 붙이지 않습니다.
    if (options.method !== "GET") {
      if (!csrf) {
        const result = await getCsrf(options.signal);
        csrf = result.data;
      }
      headers.set(csrf.headerName, csrf.token);
    }
    if (options.idempotencyKey) headers.set("Idempotency-Key", options.idempotencyKey);
    if (options.form && options.body !== undefined) throw new ApiContractError("JSON과 FormData를 함께 보내지 마세요.");
    let body: BodyInit | undefined;
    if (options.form) {
      body = options.form; // Content-Type boundary는 브라우저가 설정합니다.
    } else if (options.body !== undefined) {
      headers.set("Content-Type", "application/json; charset=utf-8");
      body = JSON.stringify(options.body);
    }
    let response: Response;
    try {
      response = await fetcher(origin + path, {
        method: options.method, headers,
        credentials: "include",
        ...(body !== undefined ? { body } : {}),
        ...(options.signal ? { signal: options.signal } : {})
      });
    } catch (error) {
      if (options.signal?.aborted ||
          (error instanceof Error && error.name === "AbortError")) throw error;
      throw new ApiNetworkError();
    }
    if (!response.ok) {
      const error = await httpError(response);
      if (error.detail.code === "CSRF_TOKEN_INVALID") csrf = null;
      if (response.status === 401 && options.auth === "instructor") {
        config.tokenStore.clear();
        config.onInstructorUnauthorized?.(); // 폼/캐시 삭제 및 로그인 이동은 앱이 구현합니다.
      }
      throw error; // 변경 요청 자동 재전송 없음.
    }
    if (!options.expectedStatuses.includes(response.status)) {
      throw new ApiContractError("문서와 다른 HTTP 성공 상태입니다: " + response.status);
    }
    return response;
  }
  async function getCsrf(signal?: AbortSignal): Promise<ApiResponse<CsrfData>> {
    const response = await send("/api/v1/csrf", {
      method: "GET", auth: "public", expectedStatuses: [200], signal
    });
    const result = assertEnvelope<CsrfData>(await readJson(response));
    if (!isObject(result.data) || typeof result.data.headerName !== "string" ||
        typeof result.data.token !== "string") throw new ApiContractError("CSRF 응답 형식 오류");
    csrf = result.data;
    return result;
  }
  return {
    getCsrf,
    refreshCsrf: (signal) => { csrf = null; return getCsrf(signal); },
    request: async <T>(path: string, options: RequestOptions) => {
      const response = await send(path, options);
      return assertEnvelope<T>(await readJson(response));
    },
    requestEmpty: async (path, options) => {
      const response = await send(path, options);
      if (response.status !== 204) throw new ApiContractError("본문 없는 성공은204 계약입니다.");
      // response.json()을 호출하지 않습니다.
    },
    rememberLoginToken: (token) => config.tokenStore.set(token),
    forgetLoginToken: () => config.tokenStore.clear()
  };
}
