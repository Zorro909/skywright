import createClient from 'openapi-fetch';

import type {
  components,
  paths,
} from '../../../target/generated-sources/openapi/skywright-api';
import {
  ApiRequestFailure,
  classifyRequestFailure,
  normalizeProblemResponse,
} from './api-failure';

export type TargetStorage = components['schemas']['TargetStorage'];
export type CreateTargetStorage = components['schemas']['CreateTargetStorage'];
export type TargetStorageDefaults =
  components['schemas']['TargetStorageDefaults'];
export type TargetClass = components['schemas']['TargetClass'];
export type TargetStorageConfiguration =
  components['schemas']['TargetStorageConfiguration'];
export type TargetStorageBindingReference =
  components['schemas']['TargetStorageBindingReference'];

const api = createClient<paths>({
  baseUrl: '/api/v1',
  fetch: async (request) => {
    try {
      return await globalThis.fetch(request);
    } catch (error) {
      throw new ApiRequestFailure(classifyRequestFailure(error));
    }
  },
});

export async function listTargetStorages(
  signal?: AbortSignal,
): Promise<TargetStorage[]> {
  return request(
    api.GET('/target-storages', {
      signal: signal ?? null,
      parseAs: 'json',
    }),
  );
}

export async function getTargetStorage(
  storageId: string,
): Promise<TargetStorage> {
  return request(
    api.GET('/target-storages/{storageId}', {
      params: { path: { storageId } },
      parseAs: 'json',
    }),
  );
}

export async function createTargetStorage(
  body: CreateTargetStorage,
): Promise<TargetStorage> {
  return request(api.POST('/target-storages', { body, parseAs: 'json' }));
}

export async function stageTargetStorageRevision(
  storageId: string,
  expectedRegistrationRevision: number,
  configuration: TargetStorageConfiguration,
): Promise<TargetStorage> {
  return request(
    api.POST('/target-storages/{storageId}/revisions', {
      params: { path: { storageId } },
      body: { expectedRegistrationRevision, configuration },
      parseAs: 'json',
    }),
  );
}

export async function replaceTargetStorageBindings(
  storageId: string,
  expectedRegistrationRevision: number,
  bindings: TargetStorageBindingReference[],
): Promise<TargetStorage> {
  return request(
    api.PUT('/target-storages/{storageId}/bindings', {
      params: { path: { storageId } },
      body: { expectedRegistrationRevision, bindings },
      parseAs: 'json',
    }),
  );
}

export async function qualifyTargetStorage(
  storageId: string,
): Promise<TargetStorage> {
  return request(
    api.POST('/target-storages/{storageId}/qualification', {
      params: { path: { storageId } },
      parseAs: 'json',
    }),
  );
}

export async function setTargetStorageActivation(
  storageId: string,
  expectedRegistrationRevision: number,
  activated: boolean,
): Promise<TargetStorage> {
  return request(
    api.PUT('/target-storages/{storageId}/activation', {
      params: { path: { storageId } },
      body: { expectedRegistrationRevision, activated },
      parseAs: 'json',
    }),
  );
}

export async function deleteTargetStorage(storageId: string): Promise<void> {
  await request(
    api.DELETE('/target-storages/{storageId}', {
      params: { path: { storageId } },
    }),
  );
}

export async function listTargetStorageDefaults(): Promise<
  TargetStorageDefaults[]
> {
  return request(api.GET('/target-storage-defaults', { parseAs: 'json' }));
}

export async function assignTargetStorageDefaults(
  targetClass: TargetClass,
  executionStorageId: string,
  repatriationEnabled: boolean,
  repatriationStorageId: string,
): Promise<TargetStorageDefaults> {
  return request(
    api.PUT('/target-storage-defaults/{targetClass}', {
      params: { path: { targetClass } },
      body: {
        executionStorageId,
        repatriationEnabled,
        repatriationStorageId,
      },
      parseAs: 'json',
    }),
  );
}

async function request<T>(
  pending: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, error, response } = await pending;
  if (!response.ok) {
    throw new ApiRequestFailure(
      await normalizeProblemResponse(response, error),
    );
  }
  return data as T;
}
