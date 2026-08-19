import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  signal,
} from '@angular/core';

import {
  assignTargetStorageDefaults,
  createTargetStorage,
  deleteTargetStorage,
  listTargetStorageDefaults,
  listTargetStorages,
  qualifyTargetStorage,
  replaceTargetStorageBindings,
  setTargetStorageActivation,
  stageTargetStorageRevision,
  type TargetClass,
  type TargetStorage,
  type TargetStorageBindingReference,
  type TargetStorageConfiguration,
  type TargetStorageDefaults,
} from '../api/target-storage.api';

const TARGET_CLASSES: TargetClass[] = [
  'local-single-gpu',
  'local-multi-gpu',
  'cloud-on-demand',
  'cloud-spot',
];

const BINDING_ROLES: TargetStorageBindingReference['role'][] = [
  'training-process',
  'backend',
  'transfer-worker',
  'metric-view',
];

@Component({
  selector: 'sky-target-storages-page',
  template: `
    <section aria-labelledby="target-storages-heading">
      <p class="eyebrow">Storage control plane</p>
      <h2 id="target-storages-heading">Target Storages</h2>
      <p class="lede">
        Register and qualify explicit S3-compatible destinations for training
        data and run outputs.
      </p>

      @if (failure()) {
        <p class="failure" role="alert">{{ failure() }}</p>
      }

      <details class="panel">
        <summary>Register Target Storage</summary>
        <form (submit)="register($event)">
          <label>
            Name
            <input name="name" required />
          </label>
          <label>
            Purpose
            <select name="purpose">
              <option value="run-output">Run output</option>
              <option value="dataset">Dataset</option>
            </select>
          </label>
          <label>
            Endpoint URL
            <input name="endpoint" type="url" required />
          </label>
          <label>
            Bucket
            <input name="bucket" required />
          </label>
          <label>
            Region
            <input name="region" value="us-east-1" required />
          </label>
          <label class="checkbox">
            <input name="pathStyleAccess" type="checkbox" checked />
            Use path-style access
          </label>
          <fieldset>
            <legend>Credential Bindings</legend>
            <p>
              Binding UUIDs only. Credential values are never accepted here.
            </p>
            @for (role of bindingRoles; track role) {
              <label>
                {{ roleLabel(role) }} binding UUID
                <input [name]="role" type="text" />
              </label>
              <label>
                {{ roleLabel(role) }} binding revision
                <input
                  [name]="role + '-revision'"
                  type="number"
                  min="1"
                  value="1"
                />
              </label>
            }
          </fieldset>
          <label>
            Chunked encoding option
            <input name="chunkedEncoding" />
          </label>
          <label>
            Checksum calculation option
            <input name="checksumCalculation" />
          </label>
          <button type="submit" [disabled]="busy()">Register candidate</button>
        </form>
      </details>

      @if (loading()) {
        <p aria-live="polite">Loading Target Storages…</p>
      } @else if (storages().length === 0) {
        <p>No Target Storages are registered.</p>
      }

      <div class="storage-grid">
        @for (storage of storages(); track storage.id) {
          <article class="panel storage-card">
            <header>
              <div>
                <p class="eyebrow">{{ storage.purpose }}</p>
                <h3>{{ storage.name }}</h3>
              </div>
              <span class="status" [class.eligible]="storage.eligible">
                {{ storage.eligible ? 'Eligible' : 'Not eligible' }}
              </span>
            </header>
            <dl>
              <div>
                <dt>ID</dt>
                <dd>{{ storage.id }}</dd>
              </div>
              <div>
                <dt>Bucket</dt>
                <dd>{{ storage.bucket }}</dd>
              </div>
              <div>
                <dt>Registration revision</dt>
                <dd>{{ storage.registrationRevision }}</dd>
              </div>
              <div>
                <dt>Active revision</dt>
                <dd>{{ storage.activeRevision ?? 'None' }}</dd>
              </div>
              <div>
                <dt>Candidate revision</dt>
                <dd>{{ storage.candidateRevision ?? 'None' }}</dd>
              </div>
            </dl>

            <h4>Credential Binding readiness</h4>
            <ul>
              @for (binding of storage.bindings; track binding.role) {
                <li>
                  {{ roleLabel(binding.role) }}:
                  <strong>{{ binding.readiness }}</strong>
                </li>
              }
            </ul>

            <h4>Configuration history</h4>
            <ol>
              @for (revision of storage.revisions; track revision.revision) {
                <li>
                  Revision {{ revision.revision }} — {{ revision.state }} —
                  {{ revision.configuration.endpoint }}
                </li>
              }
            </ol>

            @if (latestAssessment(storage); as assessment) {
              <h4>Latest qualification</h4>
              <p>
                Revision {{ assessment.configurationRevision }}:
                <strong>{{ assessment.availability }}</strong>
              </p>
              @if (failedCapabilities(storage).length > 0) {
                <ul>
                  @for (
                    capability of failedCapabilities(storage);
                    track capability.capability
                  ) {
                    <li>
                      {{ capability.capability }} —
                      {{
                        capability.failureCode ?? capability.summary ?? 'failed'
                      }}
                    </li>
                  }
                </ul>
              }
            }

            <div class="actions">
              <button
                type="button"
                (click)="qualify(storage)"
                [disabled]="busy()"
              >
                Qualify current revision
              </button>
              <button
                type="button"
                (click)="toggleActivation(storage)"
                [disabled]="busy()"
              >
                {{ storage.activated ? 'Deactivate' : 'Activate' }}
              </button>
              <button
                type="button"
                class="danger"
                (click)="remove(storage)"
                [disabled]="busy()"
              >
                Delete
              </button>
            </div>

            <details>
              <summary>Stage revised configuration</summary>
              <form (submit)="stage($event, storage)">
                <label>
                  Endpoint URL
                  <input
                    name="endpoint"
                    type="url"
                    [value]="storage.configuration?.endpoint ?? ''"
                    required
                  />
                </label>
                <label>
                  Region
                  <input
                    name="region"
                    [value]="storage.configuration?.region ?? 'us-east-1'"
                    required
                  />
                </label>
                <label class="checkbox">
                  <input
                    name="pathStyleAccess"
                    type="checkbox"
                    [checked]="storage.configuration?.pathStyleAccess ?? true"
                  />
                  Use path-style access
                </label>
                <label>
                  Chunked encoding option
                  <input
                    name="chunkedEncoding"
                    [value]="
                      storage.configuration?.compatibilityOptions?.[
                        'chunkedEncoding'
                      ] ?? ''
                    "
                  />
                </label>
                <label>
                  Checksum calculation option
                  <input
                    name="checksumCalculation"
                    [value]="
                      storage.configuration?.compatibilityOptions?.[
                        'checksumCalculation'
                      ] ?? ''
                    "
                  />
                </label>
                <button type="submit" [disabled]="busy()">
                  Stage revision
                </button>
              </form>
            </details>

            <details>
              <summary>Replace Credential Bindings</summary>
              <form (submit)="replaceBindings($event, storage)">
                <p>
                  Binding readiness is always resolved by the server authority.
                </p>
                @for (role of bindingRoles; track role) {
                  <label>
                    {{ roleLabel(role) }} binding UUID
                    <input [name]="role" [value]="bindingId(storage, role)" />
                  </label>
                  <label>
                    {{ roleLabel(role) }} binding revision
                    <input
                      [name]="role + '-revision'"
                      type="number"
                      min="1"
                      [value]="bindingRevision(storage, role)"
                    />
                  </label>
                }
                <button type="submit" [disabled]="busy()">
                  Replace bindings
                </button>
              </form>
            </details>
          </article>
        }
      </div>

      <section class="defaults" aria-labelledby="defaults-heading">
        <h3 id="defaults-heading">Target Class defaults</h3>
        <p>
          Execution and repatriation destinations are explicit. Skywright does
          not silently fall back to another Target Storage.
        </p>
        <div class="storage-grid">
          @for (targetClass of targetClasses; track targetClass) {
            <form class="panel" (submit)="assignDefaults($event, targetClass)">
              <h4>{{ targetClass }}</h4>
              <label>
                Execution destination
                <select
                  name="executionStorageId"
                  [value]="defaultFor(targetClass)?.executionStorageId ?? ''"
                  required
                >
                  <option value="">
                    Select an eligible run-output storage
                  </option>
                  @for (storage of eligibleRunOutputs(); track storage.id) {
                    <option [value]="storage.id">{{ storage.name }}</option>
                  }
                </select>
              </label>
              <label class="checkbox">
                <input
                  name="repatriationEnabled"
                  type="checkbox"
                  [checked]="
                    defaultFor(targetClass)?.repatriationEnabled ?? false
                  "
                />
                Enable repatriation
              </label>
              <label>
                Repatriation destination
                <select
                  name="repatriationStorageId"
                  [value]="defaultFor(targetClass)?.repatriationStorageId ?? ''"
                  required
                >
                  <option value="">
                    Select an eligible run-output storage
                  </option>
                  @for (storage of eligibleRunOutputs(); track storage.id) {
                    <option [value]="storage.id">{{ storage.name }}</option>
                  }
                </select>
              </label>
              <button
                type="submit"
                [disabled]="busy() || eligibleRunOutputs().length === 0"
              >
                Save defaults
              </button>
            </form>
          }
        </div>
      </section>
    </section>
  `,
  styles: `
    .panel {
      padding: 1.25rem;
      border: 1px solid var(--border);
      border-radius: 0.8rem;
      background: color-mix(in srgb, var(--soft-surface) 65%, transparent);
    }
    summary {
      cursor: pointer;
      font-weight: 750;
    }
    form {
      display: grid;
      gap: 1rem;
      margin-top: 1rem;
    }
    label {
      display: grid;
      gap: 0.35rem;
      color: var(--muted-text);
    }
    .checkbox {
      display: flex;
      align-items: center;
      gap: 0.55rem;
    }
    input,
    select,
    button {
      min-height: 2.65rem;
      padding: 0.6rem 0.75rem;
      border: 1px solid var(--border);
      border-radius: 0.45rem;
      background: var(--surface);
      color: var(--text);
      font: inherit;
    }
    input[type='checkbox'] {
      min-height: auto;
    }
    button {
      border-color: var(--accent);
      background: var(--accent);
      color: var(--accent-text);
      cursor: pointer;
    }
    button:disabled {
      cursor: wait;
      opacity: 0.55;
    }
    button.danger {
      border-color: #a33838;
      background: transparent;
      color: var(--text);
    }
    fieldset {
      display: grid;
      gap: 1rem;
      border: 1px solid var(--border);
    }
    .storage-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 24rem), 1fr));
      gap: 1rem;
      margin-top: 1rem;
    }
    .storage-card > header {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
    }
    h3,
    h4 {
      overflow-wrap: anywhere;
    }
    .storage-card h3 {
      margin: 0;
      font-size: 1.55rem;
    }
    .status {
      align-self: start;
      padding: 0.3rem 0.55rem;
      border-radius: 999px;
      background: var(--soft-surface);
      white-space: nowrap;
    }
    .status.eligible {
      background: var(--accent);
      color: var(--accent-text);
    }
    dl div {
      display: grid;
      grid-template-columns: minmax(8rem, 1fr) 2fr;
      gap: 0.5rem;
      padding-block: 0.3rem;
      border-bottom: 1px solid var(--border);
    }
    dt {
      color: var(--muted-text);
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.6rem;
      margin-block: 1rem;
    }
    .storage-card details + details {
      margin-top: 0.75rem;
    }
    .defaults {
      margin-top: 3rem;
    }
    .failure {
      padding: 0.8rem;
      border-left: 0.3rem solid #a33838;
      background: var(--soft-surface);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TargetStoragesPage implements OnInit, OnDestroy {
  protected readonly bindingRoles = BINDING_ROLES;
  protected readonly targetClasses = TARGET_CLASSES;
  protected readonly storages = signal<TargetStorage[]>([]);
  protected readonly defaults = signal<TargetStorageDefaults[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | undefined>(undefined);
  private readonly abortController = new AbortController();

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      const [storages, defaults] = await Promise.all([
        listTargetStorages(this.abortController.signal),
        listTargetStorageDefaults(),
      ]);
      this.storages.set(storages);
      this.defaults.set(defaults);
    } catch {
      if (!this.abortController.signal.aborted) {
        this.failure.set('Target Storage data could not be loaded.');
      }
    } finally {
      this.loading.set(false);
    }
  }

  ngOnDestroy(): void {
    this.abortController.abort();
  }

  protected async register(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    const form = event.currentTarget as HTMLFormElement;
    const data = new FormData(form);
    await this.perform(async () =>
      createTargetStorage({
        name: this.value(data, 'name'),
        purpose: this.value(data, 'purpose') as 'dataset' | 'run-output',
        bucket: this.value(data, 'bucket'),
        configuration: this.configuration(data),
        bindings: this.bindingReferences(data),
      }),
    );
    form.reset();
  }

  protected async qualify(storage: TargetStorage): Promise<void> {
    await this.perform(() => qualifyTargetStorage(storage.id));
  }

  protected async toggleActivation(storage: TargetStorage): Promise<void> {
    await this.perform(() =>
      setTargetStorageActivation(
        storage.id,
        storage.registrationRevision,
        !storage.activated,
      ),
    );
  }

  protected async stage(
    event: SubmitEvent,
    storage: TargetStorage,
  ): Promise<void> {
    event.preventDefault();
    const data = new FormData(event.currentTarget as HTMLFormElement);
    await this.perform(() =>
      stageTargetStorageRevision(
        storage.id,
        storage.registrationRevision,
        this.configuration(data),
      ),
    );
  }

  protected async replaceBindings(
    event: SubmitEvent,
    storage: TargetStorage,
  ): Promise<void> {
    event.preventDefault();
    const data = new FormData(event.currentTarget as HTMLFormElement);
    await this.perform(() =>
      replaceTargetStorageBindings(
        storage.id,
        storage.registrationRevision,
        this.bindingReferences(data),
      ),
    );
  }

  protected async remove(storage: TargetStorage): Promise<void> {
    this.busy.set(true);
    this.failure.set(undefined);
    try {
      await deleteTargetStorage(storage.id);
      this.storages.update((storages) =>
        storages.filter((candidate) => candidate.id !== storage.id),
      );
    } catch {
      this.failure.set('The Target Storage could not be deleted.');
    } finally {
      this.busy.set(false);
    }
  }

  protected async assignDefaults(
    event: SubmitEvent,
    targetClass: TargetClass,
  ): Promise<void> {
    event.preventDefault();
    const data = new FormData(event.currentTarget as HTMLFormElement);
    this.busy.set(true);
    this.failure.set(undefined);
    try {
      const assigned = await assignTargetStorageDefaults(
        targetClass,
        this.value(data, 'executionStorageId'),
        data.has('repatriationEnabled'),
        this.value(data, 'repatriationStorageId'),
      );
      this.defaults.update((defaults) => [
        ...defaults.filter((entry) => entry.targetClass !== targetClass),
        assigned,
      ]);
    } catch {
      this.failure.set('The Target Class defaults could not be saved.');
    } finally {
      this.busy.set(false);
    }
  }

  protected latestAssessment(storage: TargetStorage) {
    return storage.assessments.at(-1);
  }

  protected failedCapabilities(storage: TargetStorage) {
    return (
      this.latestAssessment(storage)?.capabilities.filter(
        (capability) => !capability.succeeded,
      ) ?? []
    );
  }

  protected eligibleRunOutputs(): TargetStorage[] {
    return this.storages().filter(
      (storage) => storage.purpose === 'run-output' && storage.eligible,
    );
  }

  protected defaultFor(targetClass: TargetClass) {
    return this.defaults().find((entry) => entry.targetClass === targetClass);
  }

  protected bindingId(
    storage: TargetStorage,
    role: TargetStorageBindingReference['role'],
  ): string {
    return (
      storage.bindings.find((binding) => binding.role === role)?.bindingId ?? ''
    );
  }

  protected bindingRevision(
    storage: TargetStorage,
    role: TargetStorageBindingReference['role'],
  ): number {
    return (
      storage.bindings.find((binding) => binding.role === role)
        ?.bindingRevision ?? 1
    );
  }

  protected roleLabel(role: TargetStorageBindingReference['role']): string {
    return role
      .split('-')
      .map((word) => word[0]?.toUpperCase() + word.slice(1))
      .join(' ');
  }

  private async perform(
    operation: () => Promise<TargetStorage>,
  ): Promise<void> {
    this.busy.set(true);
    this.failure.set(undefined);
    try {
      const updated = await operation();
      this.storages.update((storages) => [
        ...storages.filter((storage) => storage.id !== updated.id),
        updated,
      ]);
    } catch {
      this.failure.set('The Target Storage operation could not be completed.');
    } finally {
      this.busy.set(false);
    }
  }

  private configuration(data: FormData): TargetStorageConfiguration {
    const compatibilityOptions: Record<string, string> = {};
    for (const option of ['chunkedEncoding', 'checksumCalculation']) {
      const value = this.value(data, option);
      if (value !== '') {
        compatibilityOptions[option] = value;
      }
    }
    return {
      endpoint: this.value(data, 'endpoint'),
      region: this.value(data, 'region'),
      pathStyleAccess: data.has('pathStyleAccess'),
      compatibilityOptions,
    };
  }

  private bindingReferences(data: FormData): TargetStorageBindingReference[] {
    return BINDING_ROLES.filter((role) => this.value(data, role) !== '').map(
      (role) => ({
        role,
        bindingId: this.value(data, role),
        bindingRevision: Number(this.value(data, role + '-revision') || '1'),
      }),
    );
  }

  private value(data: FormData, name: string): string {
    const value = data.get(name);
    return typeof value === 'string' ? value.trim() : '';
  }
}
