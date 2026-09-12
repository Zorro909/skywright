import { InjectionToken } from '@angular/core';
import { object, type CreateRun, type CreateManagedRun } from '../api/run.api';

export interface SubmissionJournal {
  readonly version: 1;
  readonly request: CreateRun;
  readonly acceptedRunId?: string;
  readonly rejected?: true;
}
export interface ManagedSubmissionJournal {
  readonly version: 2;
  readonly request: CreateManagedRun;
  readonly acceptedRunId?: string;
  readonly rejected?: true;
}
const key = 'skywright.local-submission.v1';
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;

/** One recoverable submission per browser origin. Changes serialize across tabs. */
export class RunRequestJournal {
  constructor(private readonly providedStorage?: Storage) {}

  private get storage(): Storage {
    return this.providedStorage ?? globalThis.localStorage;
  }

  read(): SubmissionJournal | undefined {
    const saved = this.readAny();
    if (saved && saved.version !== 1)
      throw new Error('A managed submission is saved');
    return saved;
  }

  readManaged(): ManagedSubmissionJournal | undefined {
    const saved = this.readAny();
    if (saved && saved.version !== 2)
      throw new Error('An earlier submission is saved');
    return saved;
  }

  private readAny(): SubmissionJournal | ManagedSubmissionJournal | undefined {
    const raw = this.storage.getItem(key);
    if (raw === null) return undefined;
    const value: unknown = JSON.parse(raw);
    if (
      !object(value) ||
      !onlyKeys(value, ['version', 'request', 'acceptedRunId', 'rejected']) ||
      !(value['version'] === 1
        ? savedRequest(value['request'])
        : value['version'] === 2 && savedManagedRequest(value['request']))
    )
      throw new Error('Unreadable saved submission');
    if (
      (value['acceptedRunId'] !== undefined &&
        (typeof value['acceptedRunId'] !== 'string' ||
          !uuid.test(value['acceptedRunId']))) ||
      (value['rejected'] !== undefined && value['rejected'] !== true) ||
      (value['acceptedRunId'] !== undefined && value['rejected'] !== undefined)
    )
      throw new Error('Unreadable saved submission');
    return value as unknown as SubmissionJournal | ManagedSubmissionJournal;
  }

  freezeManaged(request: CreateManagedRun): Promise<ManagedSubmissionJournal> {
    return this.lock(() => {
      const previous = this.readManaged();
      if (previous) return previous;
      const saved: ManagedSubmissionJournal = { version: 2, request };
      this.write(saved);
      return saved;
    });
  }

  freeze(request: CreateRun): Promise<SubmissionJournal> {
    return this.lock(() => {
      const previous = this.read();
      if (previous) return previous;
      const saved: SubmissionJournal = { version: 1, request };
      this.write(saved);
      return saved;
    });
  }

  settle(
    submissionId: string,
    result: { acceptedRunId: string } | { rejected: true },
  ): Promise<void> {
    return this.lock(() => {
      const saved = this.readAny();
      if (saved?.request.submissionId === submissionId)
        this.write({ ...saved, ...result });
    });
  }

  clearSettled(): Promise<void> {
    return this.lock(() => {
      const saved = this.readAny();
      if (saved && !saved.acceptedRunId && !saved.rejected)
        throw new Error('Submission acceptance is still uncertain');
      this.storage.removeItem(key);
    });
  }

  savedCancellation(runId: string): string | undefined {
    const value = this.storage.getItem(`skywright.cancel.v1.${runId}`);
    if (value !== null && !uuid.test(value))
      throw new Error('Unreadable saved cancellation');
    return value ?? undefined;
  }

  cancellation(runId: string, knownCommandId?: string): Promise<string> {
    return this.lock(() => {
      if (
        !uuid.test(runId) ||
        (knownCommandId !== undefined && !uuid.test(knownCommandId))
      )
        throw new Error('Invalid cancellation identity');
      const cancelKey = `skywright.cancel.v1.${runId}`;
      const saved = this.storage.getItem(cancelKey);
      if (saved !== null && !uuid.test(saved))
        throw new Error('Unreadable saved cancellation');
      const identity = knownCommandId ?? saved ?? crypto.randomUUID();
      this.storage.setItem(cancelKey, identity);
      if (this.storage.getItem(cancelKey) !== identity)
        throw new Error('Cancellation identity was not saved');
      return identity;
    });
  }

  private write(value: SubmissionJournal | ManagedSubmissionJournal) {
    const encoded = JSON.stringify(value);
    if (new TextEncoder().encode(encoded).length > 1024 * 1024)
      throw new Error('Saved request exceeds 1 MiB');
    this.storage.setItem(key, encoded);
    if (this.storage.getItem(key) !== encoded)
      throw new Error('Submission identity was not saved');
  }

  private lock<T>(operation: () => T): Promise<T> {
    if (!navigator.locks)
      return Promise.reject(
        new Error('Browser request locking is unavailable'),
      );
    return navigator.locks.request(key, operation);
  }
}

function onlyKeys(value: Record<string, unknown>, keys: readonly string[]) {
  return Object.keys(value).every((field) => keys.includes(field));
}

function savedManagedRequest(value: unknown): value is CreateManagedRun {
  return (
    object(value) &&
    onlyKeys(value, ['submissionId', 'workload', 'target']) &&
    typeof value['submissionId'] === 'string' &&
    uuid.test(value['submissionId']) &&
    value['workload'] === 'demonstration' &&
    typeof value['target'] === 'string' &&
    !!value['target']
  );
}

/** Validate stored request structure; the backend owns input constraints. */
function savedRequest(value: unknown): value is CreateRun {
  if (!object(value)) return false;
  const seed = value['checkpointSeed'];
  return (
    onlyKeys(value, [
      'submissionId',
      'trainingProjectId',
      'manifestArtifactDigest',
      'datasetDefinitionId',
      'preferredDatasetCopyId',
      'executionStorageId',
      'target',
      'gpuCount',
      'configuration',
      'maximumRecoveryDebt',
      'checkpointSeed',
    ] satisfies (keyof CreateRun)[]) &&
    typeof value['submissionId'] === 'string' &&
    uuid.test(value['submissionId']) &&
    [
      'trainingProjectId',
      'manifestArtifactDigest',
      'datasetDefinitionId',
      'target',
    ].every((field) => typeof value[field] === 'string') &&
    ['preferredDatasetCopyId', 'executionStorageId'].every(
      (field) => value[field] === undefined || typeof value[field] === 'string',
    ) &&
    typeof value['gpuCount'] === 'number' &&
    Number.isFinite(value['gpuCount']) &&
    object(value['configuration']) &&
    (value['maximumRecoveryDebt'] === undefined ||
      (typeof value['maximumRecoveryDebt'] === 'number' &&
        Number.isFinite(value['maximumRecoveryDebt']))) &&
    (seed === undefined ||
      (object(seed) &&
        onlyKeys(seed, ['predecessorRunId', 'checkpointReference']) &&
        typeof seed['predecessorRunId'] === 'string' &&
        typeof seed['checkpointReference'] === 'string'))
  );
}

export const RUN_REQUEST_JOURNAL = new InjectionToken<RunRequestJournal>(
  'RUN_REQUEST_JOURNAL',
  {
    providedIn: 'root',
    factory: () => new RunRequestJournal(),
  },
);
