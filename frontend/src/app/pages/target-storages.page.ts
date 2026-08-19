import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  inject,
  signal,
} from '@angular/core';

import { apiFailureFrom } from '../api/api-failure';
import {
  TARGET_STORAGE_API,
  type CreateTargetStorage,
  type TargetClass,
  type TargetStorage,
  type TargetStorageDefaults,
} from '../api/target-storage.api';

const TARGET_CLASSES: readonly TargetClass[] = [
  'local-single-gpu',
  'local-multi-gpu',
  'cloud-on-demand',
  'cloud-spot',
];

const ROLES = [
  'training-process',
  'backend',
  'transfer-worker',
  'metric-view',
] as const;

@Component({
  selector: 'sky-target-storages-page',
  template: `
    <article aria-labelledby="target-storages-heading">
      <p class="eyebrow">Operations</p>
      <h2 id="target-storages-heading">Target Storages</h2>
      <p class="lede">
        Register and qualify S3-compatible destinations before assigning new
        work to them.
      </p>

      <section class="storage-panel" aria-labelledby="register-heading">
        <h3 id="register-heading">Register a destination</h3>
        <form (submit)="create($event)">
          <label>Name <input name="name" required /></label>
          <label>
            Purpose
            <select name="purpose">
              <option value="run-output">Run outputs</option>
              <option value="dataset">Datasets</option>
            </select>
          </label>
          <label>Bucket <input name="bucket" required /></label>
          <label>
            Endpoint
            <input
              name="endpoint"
              type="url"
              required
              placeholder="https://s3.example"
            />
          </label>
          <label
            >Region <input name="region" required value="us-east-1"
          /></label>
          <label class="check-row">
            <input name="pathStyleAccess" type="checkbox" /> Path-style access
          </label>
          <label>
            Chunked encoding
            <select name="chunkedEncoding">
              <option value="disabled">Disabled</option>
              <option value="enabled">Enabled</option>
            </select>
          </label>
          <label>
            Request checksum calculation
            <select name="checksumCalculation">
              <option value="when-required">When required</option>
              <option value="when-supported">When supported</option>
            </select>
          </label>
          <fieldset>
            <legend>Credential Binding references</legend>
            <p>Leave a role blank to keep the registration incomplete.</p>
            @for (role of roles; track role) {
              <label>
                {{ role }} binding ID
                <input [name]="role" autocomplete="off" />
              </label>
              <label>
                {{ role }} binding revision
                <input
                  [name]="role + '-revision'"
                  type="number"
                  min="1"
                  value="1"
                />
              </label>
            }
          </fieldset>
          <button type="submit" [disabled]="busy()">Register</button>
        </form>
      </section>

      @if (message()) {
        <p class="operation-message" role="status">{{ message() }}</p>
      }

      <section aria-labelledby="registrations-heading">
        <h3 id="registrations-heading">Registrations</h3>
        @if (loading()) {
          <p role="status">Loading Target Storages…</p>
        } @else if (storages().length === 0) {
          <p>No Target Storages are registered.</p>
        }
        <div class="storage-grid">
          @for (storage of storages(); track storage.id) {
            <article class="storage-card" [attr.data-storage-id]="storage.id">
              <header>
                <div>
                  <h4>{{ storage.name }}</h4>
                  <p>{{ storage.purpose }} · {{ storage.bucket }}</p>
                </div>
                <span [class.eligible]="storage.eligible">
                  {{ storage.eligible ? 'Eligible' : 'Ineligible' }}
                </span>
              </header>
              <dl>
                <div>
                  <dt>Identity</dt>
                  <dd>{{ storage.id }}</dd>
                </div>
                <div>
                  <dt>Registration revision</dt>
                  <dd>{{ storage.registrationRevision }}</dd>
                </div>
                <div>
                  <dt>Active revision</dt>
                  <dd>{{ storage.activeRevision ?? 'none' }}</dd>
                </div>
                <div>
                  <dt>Candidate revision</dt>
                  <dd>{{ storage.candidateRevision ?? 'none' }}</dd>
                </div>
                <div>
                  <dt>Capability availability</dt>
                  <dd>{{ storage.availability }}</dd>
                </div>
                <div>
                  <dt>Endpoint</dt>
                  <dd>{{ storage.configuration?.endpoint ?? 'not active' }}</dd>
                </div>
              </dl>

              <h5>Credential Binding readiness</h5>
              <ul>
                @for (binding of storage.bindings; track binding.role) {
                  <li>{{ binding.role }}: {{ binding.readiness }}</li>
                } @empty {
                  <li>No bindings associated.</li>
                }
              </ul>

              <h5>Revision history</h5>
              <ul>
                @for (revision of storage.revisions; track revision.revision) {
                  <li>
                    Revision {{ revision.revision }} · {{ revision.state }} ·
                    {{ revision.configuration.endpoint }}
                  </li>
                }
              </ul>

              <h5>Qualification history</h5>
              @for (assessment of storage.assessments; track assessment.id) {
                <details>
                  <summary>
                    Revision {{ assessment.configurationRevision }} ·
                    {{ assessment.availability }}
                  </summary>
                  <p>
                    Binding revisions:
                    @for (
                      binding of assessment.bindingRevisions;
                      track binding.role
                    ) {
                      {{ binding.role }}@{{ binding.bindingRevision }}
                    } @empty {
                      none
                    }
                  </p>
                  <ul>
                    @for (
                      result of assessment.capabilities;
                      track result.capability
                    ) {
                      <li>
                        {{ result.capability }}:
                        {{
                          result.succeeded
                            ? 'passed'
                            : (result.summary ?? result.failureCode)
                        }}
                      </li>
                    }
                  </ul>
                </details>
              } @empty {
                <p>No qualification has run.</p>
              }

              <div class="actions">
                <button
                  type="button"
                  (click)="qualify(storage)"
                  [disabled]="busy()"
                >
                  Qualify
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
                  (click)="remove(storage)"
                  [disabled]="busy()"
                >
                  Delete
                </button>
              </div>

              <form class="inline-form" (submit)="stage(storage, $event)">
                <label>
                  Candidate endpoint
                  <input
                    name="endpoint"
                    type="url"
                    required
                    [value]="storage.configuration?.endpoint ?? ''"
                  />
                </label>
                <label>
                  Region
                  <input
                    name="region"
                    required
                    [value]="storage.configuration?.region ?? 'us-east-1'"
                  />
                </label>
                <label>
                  Chunked encoding
                  <select name="chunkedEncoding">
                    <option
                      value="disabled"
                      [selected]="
                        storage.configuration?.compatibilityOptions?.[
                          'chunkedEncoding'
                        ] !== 'enabled'
                      "
                    >
                      Disabled
                    </option>
                    <option
                      value="enabled"
                      [selected]="
                        storage.configuration?.compatibilityOptions?.[
                          'chunkedEncoding'
                        ] === 'enabled'
                      "
                    >
                      Enabled
                    </option>
                  </select>
                </label>
                <label>
                  Request checksum calculation
                  <select name="checksumCalculation">
                    <option
                      value="when-required"
                      [selected]="
                        storage.configuration?.compatibilityOptions?.[
                          'checksumCalculation'
                        ] !== 'when-supported'
                      "
                    >
                      When required
                    </option>
                    <option
                      value="when-supported"
                      [selected]="
                        storage.configuration?.compatibilityOptions?.[
                          'checksumCalculation'
                        ] === 'when-supported'
                      "
                    >
                      When supported
                    </option>
                  </select>
                </label>
                <button type="submit" [disabled]="busy()">
                  Stage revision
                </button>
              </form>

              <form
                class="inline-form"
                (submit)="replaceBindings(storage, $event)"
              >
                <fieldset>
                  <legend>Replace Credential Bindings</legend>
                  @for (role of roles; track role) {
                    <label
                      >{{ role }} binding ID
                      <input [name]="role" autocomplete="off"
                    /></label>
                    <label>
                      {{ role }} binding revision
                      <input
                        [name]="role + '-revision'"
                        type="number"
                        min="1"
                        value="1"
                      />
                    </label>
                  }
                </fieldset>
                <button type="submit" [disabled]="busy()">
                  Replace bindings
                </button>
              </form>
            </article>
          }
        </div>
      </section>

      <section class="storage-panel" aria-labelledby="defaults-heading">
        <h3 id="defaults-heading">Target Class defaults</h3>
        <form (submit)="assignDefaults($event)">
          <label>
            Target Class
            <select name="targetClass">
              @for (targetClass of targetClasses; track targetClass) {
                <option [value]="targetClass">{{ targetClass }}</option>
              }
            </select>
          </label>
          <label
            >Execution storage ID <input name="executionStorageId" required
          /></label>
          <label class="check-row">
            <input name="repatriationEnabled" type="checkbox" checked />
            Repatriation enabled
          </label>
          <label
            >Repatriation storage ID
            <input name="repatriationStorageId" required
          /></label>
          <button type="submit" [disabled]="busy()">Assign defaults</button>
        </form>
        <ul>
          @for (value of defaults(); track value.targetClass) {
            <li>
              {{ value.targetClass }}: execution {{ value.executionStorageId }},
              Repatriation
              {{
                value.repatriationEnabled
                  ? 'to ' + value.repatriationStorageId
                  : 'disabled'
              }}
            </li>
          } @empty {
            <li>No Target Class defaults are assigned.</li>
          }
        </ul>
      </section>
    </article>
  `,
  styles: `
    .storage-panel,
    .storage-card {
      border: 1px solid var(--border);
      border-radius: 0.75rem;
      padding: 1.25rem;
      background: var(--soft-surface);
    }
    form,
    fieldset {
      display: grid;
      gap: 0.8rem;
    }
    label {
      display: grid;
      gap: 0.3rem;
      font-weight: 650;
    }
    input,
    select,
    button {
      min-height: 2.6rem;
      padding: 0.55rem 0.7rem;
      border: 1px solid var(--border);
      border-radius: 0.45rem;
      font: inherit;
    }
    .check-row {
      display: flex;
      align-items: center;
    }
    .check-row input {
      min-height: auto;
    }
    .storage-grid {
      display: grid;
      gap: 1rem;
    }
    .storage-card > header {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
    }
    .storage-card h4 {
      margin: 0;
      font-size: 1.4rem;
    }
    .storage-card h5 {
      margin-bottom: 0.25rem;
    }
    dl div {
      display: grid;
      grid-template-columns: minmax(9rem, 1fr) 3fr;
      gap: 0.75rem;
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
    .eligible {
      color: var(--accent);
      font-weight: 800;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.6rem;
      margin-block: 1rem;
    }
    .inline-form {
      border-top: 1px solid var(--border);
      padding-top: 1rem;
      margin-top: 1rem;
    }
    .operation-message {
      padding: 0.75rem;
      border-left: 0.3rem solid var(--accent);
    }
    section {
      margin-block: 2rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TargetStoragesPage implements OnInit, OnDestroy {
  private readonly api = inject(TARGET_STORAGE_API);
  private readonly abort = new AbortController();
  protected readonly roles = ROLES;
  protected readonly targetClasses = TARGET_CLASSES;
  protected readonly storages = signal<TargetStorage[]>([]);
  protected readonly defaults = signal<TargetStorageDefaults[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly message = signal('');

  ngOnInit() {
    void this.reload();
  }

  ngOnDestroy() {
    this.abort.abort();
  }

  protected async create(event: SubmitEvent) {
    event.preventDefault();
    const form = event.currentTarget as HTMLFormElement;
    const data = new FormData(form);
    const bindings = this.bindings(data);
    await this.mutate('Registration created.', () =>
      this.api.create({
        name: this.value(data, 'name'),
        purpose: this.value(data, 'purpose') as 'dataset' | 'run-output',
        bucket: this.value(data, 'bucket'),
        configuration: {
          endpoint: this.value(data, 'endpoint'),
          region: this.value(data, 'region'),
          pathStyleAccess: data.has('pathStyleAccess'),
          compatibilityOptions: {
            chunkedEncoding: this.value(data, 'chunkedEncoding'),
            checksumCalculation: this.value(data, 'checksumCalculation'),
          },
        },
        bindings,
      }),
    );
    form.reset();
  }

  protected async qualify(storage: TargetStorage) {
    await this.mutate('Qualification recorded.', () =>
      this.api.qualify(storage.id),
    );
  }

  protected async toggleActivation(storage: TargetStorage) {
    await this.mutate(
      storage.activated
        ? 'Registration deactivated.'
        : 'Registration activated.',
      () => this.api.activate(storage, !storage.activated),
    );
  }

  protected async remove(storage: TargetStorage) {
    await this.mutate('Registration deleted.', () =>
      this.api.remove(storage.id),
    );
  }

  protected async stage(storage: TargetStorage, event: SubmitEvent) {
    event.preventDefault();
    const data = new FormData(event.currentTarget as HTMLFormElement);
    await this.mutate('Candidate revision staged.', () =>
      this.api.stage(
        storage,
        this.value(data, 'endpoint'),
        this.value(data, 'region'),
        {
          chunkedEncoding: this.value(data, 'chunkedEncoding'),
          checksumCalculation: this.value(data, 'checksumCalculation'),
        },
      ),
    );
  }

  protected async replaceBindings(storage: TargetStorage, event: SubmitEvent) {
    event.preventDefault();
    const data = new FormData(event.currentTarget as HTMLFormElement);
    await this.mutate('Credential Binding references replaced.', () =>
      this.api.replaceBindings(storage, this.bindings(data)),
    );
  }

  protected async assignDefaults(event: SubmitEvent) {
    event.preventDefault();
    const data = new FormData(event.currentTarget as HTMLFormElement);
    await this.mutate('Target Class defaults assigned.', () =>
      this.api.assignDefaults(
        this.value(data, 'targetClass') as TargetClass,
        this.value(data, 'executionStorageId'),
        data.has('repatriationEnabled'),
        this.value(data, 'repatriationStorageId'),
      ),
    );
  }

  private async reload() {
    try {
      const [storages, defaults] = await Promise.all([
        this.api.list(this.abort.signal),
        this.api.listDefaults(this.abort.signal),
      ]);
      this.storages.set(storages);
      this.defaults.set(defaults);
    } catch (error) {
      this.message.set(this.safeFailure(error));
    } finally {
      this.loading.set(false);
    }
  }

  private async mutate(message: string, operation: () => Promise<unknown>) {
    this.busy.set(true);
    this.message.set('');
    try {
      await operation();
      this.message.set(message);
      await this.reload();
    } catch (error) {
      this.message.set(this.safeFailure(error));
    } finally {
      this.busy.set(false);
    }
  }

  private bindings(data: FormData): CreateTargetStorage['bindings'] {
    return ROLES.flatMap((role) => {
      const bindingId = this.value(data, role);
      const parsedRevision = Number(this.value(data, `${role}-revision`));
      const bindingRevision =
        Number.isSafeInteger(parsedRevision) && parsedRevision > 0
          ? parsedRevision
          : 1;
      return bindingId ? [{ role, bindingId, bindingRevision }] : [];
    });
  }

  private value(data: FormData, name: string): string {
    const value = data.get(name);
    return typeof value === 'string' ? value.trim() : '';
  }

  private safeFailure(error: unknown): string {
    const failure = apiFailureFrom(error);
    if (failure?.kind === 'problem') {
      return `${failure.problem.errorCode}: ${failure.problem.detail ?? 'The operation failed.'}`;
    }
    return failure?.kind === 'network'
      ? 'The control-plane API could not be reached.'
      : 'The Target Storage operation failed.';
  }
}
