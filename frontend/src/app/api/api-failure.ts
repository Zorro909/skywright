export interface FieldViolation {
  readonly field: string;
  readonly code: string;
  readonly message: string;
}

export interface SafeProblem {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly errorCode: string;
  readonly correlationId: string;
  readonly fieldViolations: readonly FieldViolation[];
  readonly unavailableSource?: string | null;
  readonly retryable?: boolean;
}

export type ApiFailure =
  | {
      readonly kind: 'problem';
      readonly problem: SafeProblem;
      readonly response: Response;
    }
  | { readonly kind: 'malformed-response'; readonly response: Response }
  | { readonly kind: 'network' }
  | { readonly kind: 'aborted' };

export class ApiRequestFailure extends Error {
  constructor(readonly outcome: ApiFailure) {
    super('The API request failed.');
    this.name = 'ApiRequestFailure';
  }
}

const SKYWRIGHT_ERROR_CODE = /^SKYWRIGHT_[A-Z0-9_]+$/u;

export async function normalizeProblemResponse(
  response: Response,
  parsedBody?: unknown,
): Promise<ApiFailure> {
  if (!isProblemContentType(response.headers.get('Content-Type'))) {
    return { kind: 'malformed-response', response };
  }

  let body: unknown;
  try {
    body = arguments.length === 1 ? await response.clone().json() : parsedBody;
  } catch {
    return { kind: 'malformed-response', response };
  }

  if (!isRecord(body) || !isSafeProblem(body)) {
    return { kind: 'malformed-response', response };
  }

  const correlationHeader = response.headers.get('X-Correlation-ID')?.trim();
  const correlationId = correlationHeader || body['correlationId'];
  return {
    kind: 'problem',
    problem: {
      ...(typeof body['type'] === 'string' ? { type: body['type'] } : {}),
      ...(typeof body['title'] === 'string' ? { title: body['title'] } : {}),
      ...(typeof body['status'] === 'number' ? { status: body['status'] } : {}),
      ...(typeof body['detail'] === 'string' ? { detail: body['detail'] } : {}),
      ...(typeof body['instance'] === 'string'
        ? { instance: body['instance'] }
        : {}),
      errorCode: body['errorCode'],
      correlationId,
      fieldViolations: body['fieldViolations'],
      ...(typeof body['unavailableSource'] === 'string'
        ? { unavailableSource: body['unavailableSource'] }
        : {}),
      ...(typeof body['retryable'] === 'boolean'
        ? { retryable: body['retryable'] }
        : {}),
    },
    response,
  };
}

export function classifyRequestFailure(error: unknown): ApiFailure {
  if (
    error instanceof DOMException &&
    error.name.toLocaleLowerCase() === 'aborterror'
  ) {
    return { kind: 'aborted' };
  }
  return { kind: 'network' };
}

export function isApiFailure(value: unknown): value is ApiFailure {
  if (!isRecord(value)) {
    return false;
  }
  switch (value['kind']) {
    case 'problem':
      return (
        value['response'] instanceof Response &&
        isRecord(value['problem']) &&
        isSafeProblem(value['problem'])
      );
    case 'malformed-response':
      return value['response'] instanceof Response;
    case 'network':
    case 'aborted':
      return true;
    default:
      return false;
  }
}

export function apiFailureFrom(value: unknown): ApiFailure | undefined {
  const visited = new Set<Error>();
  let current = value;
  while (true) {
    if (current instanceof ApiRequestFailure && isApiFailure(current.outcome)) {
      return current.outcome;
    }
    if (isApiFailure(current)) {
      return current;
    }
    if (!(current instanceof Error) || visited.has(current)) {
      return undefined;
    }
    visited.add(current);
    current = current.cause;
  }
}

function isProblemContentType(contentType: string | null): boolean {
  return (
    contentType?.split(';', 1)[0]?.trim().toLocaleLowerCase() ===
    'application/problem+json'
  );
}

function isSafeProblem(value: Record<string, unknown>): value is Record<
  string,
  unknown
> & {
  errorCode: string;
  correlationId: string;
  fieldViolations: FieldViolation[];
} {
  return (
    isOptionalString(value['type']) &&
    isOptionalString(value['title']) &&
    isOptionalNumber(value['status']) &&
    isOptionalString(value['detail']) &&
    isOptionalString(value['instance']) &&
    isOptionalNullableString(value['unavailableSource']) &&
    isOptionalBoolean(value['retryable']) &&
    typeof value['errorCode'] === 'string' &&
    SKYWRIGHT_ERROR_CODE.test(value['errorCode']) &&
    typeof value['correlationId'] === 'string' &&
    value['correlationId'].length > 0 &&
    Array.isArray(value['fieldViolations']) &&
    value['fieldViolations'].every(isFieldViolation)
  );
}

function isFieldViolation(value: unknown): value is FieldViolation {
  return (
    isRecord(value) &&
    typeof value['field'] === 'string' &&
    typeof value['code'] === 'string' &&
    typeof value['message'] === 'string'
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isOptionalString(value: unknown): boolean {
  return value === undefined || typeof value === 'string';
}

function isOptionalNullableString(value: unknown): boolean {
  return value === undefined || value === null || typeof value === 'string';
}

function isOptionalNumber(value: unknown): boolean {
  return value === undefined || typeof value === 'number';
}

function isOptionalBoolean(value: unknown): boolean {
  return value === undefined || typeof value === 'boolean';
}
