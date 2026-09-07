import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ErrorHandler,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import {
  RUN_API,
  isLifecycle,
  type Run,
  type CommandReceipt,
} from '../api/run.api';
import { ApiRequestFailure, type ApiFailure } from '../api/api-failure';
import { RUN_REQUEST_JOURNAL } from './run-request-journal';
import { RequestFailure } from './request-failure';

@Component({
  selector: 'sky-run-cancellation',
  imports: [DatePipe, RequestFailure],
  template: `
    <section aria-label="Run cancellation">
      <h3>Cancellation</h3>
      @if (terminal()) {
        <p>
          The backend has confirmed a terminal outcome. A new cancellation is
          unavailable.
        </p>
      } @else {
        <button
          type="button"
          [disabled]="busy() || storageFailure() || historical()"
          (click)="cancel()"
        >
          {{
            commandId() ? 'Replay Cancellation Request' : 'Request cancellation'
          }}
        </button>
      }
      @if (historical()) {
        <p>Controls unavailable until a fresh Run read succeeds.</p>
      }
      @if (storageFailure()) {
        <p role="alert">
          Cancellation controls unavailable: the browser could not retain the
          request identity.
        </p>
      }
      @if (commandId(); as id) {
        <p>
          Cancellation request: <code>{{ id }}</code>
        </p>
        <button type="button" [disabled]="busy()" (click)="refresh()">
          Refresh cancellation receipt
        </button>
        @if (!receipt()) {
          <p>
            Acceptance or delivery is unresolved. Replaying uses the same
            request identity.
          </p>
        }
      }
      @if (busy()) {
        <p role="status">Reading cancellation delivery evidence…</p>
      }
      @if (failure(); as failure) {
        <sky-request-failure [failure]="failure" />
      }
      @if (receipt(); as receipt) {
        @if (failure() || busy()) {
          <p>
            Last received delivery evidence; current delivery is unavailable.
          </p>
        }
        <dl>
          <div>
            <dt>Accepted intent</dt>
            <dd>
              {{ receipt.kind }} · {{ receipt.acceptedAt | date: 'medium' }}
            </dd>
          </div>
          <div>
            <dt>Delivery progress</dt>
            <dd>{{ receipt.disposition }}</dd>
          </div>
          <div>
            <dt>Cooperative request published</dt>
            <dd>
              {{
                receipt.projectedAt
                  ? (receipt.projectedAt | date: 'medium')
                  : 'Not confirmed'
              }}
            </dd>
          </div>
          <div>
            <dt>Forced cancellation eligible after</dt>
            <dd>
              {{
                receipt.forceAfter
                  ? (receipt.forceAfter | date: 'medium')
                  : 'Unavailable'
              }}
            </dd>
          </div>
          <div>
            <dt>Stop delivery attempted</dt>
            <dd>
              {{
                receipt.stopAttemptedAt
                  ? (receipt.stopAttemptedAt | date: 'medium')
                  : 'Not confirmed'
              }}
            </dd>
          </div>
        </dl>
      }
      <p>
        Accepted cancellation is intent. The observed lifecycle below determines
        the outcome. Source loss may delay delivery beyond the escalation
        deadline.
      </p>
    </section>
  `,
  styleUrl: '../pages/run-views.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunCancellation {
  readonly run = input.required<Run>();
  readonly historical = input(false);
  readonly refreshRun = output<void>();
  private readonly api = inject(RUN_API);
  private readonly journal = inject(RUN_REQUEST_JOURNAL);
  private readonly errors = inject(ErrorHandler);
  private request?: AbortController;
  protected readonly busy = signal(false);
  protected readonly storageFailure = signal(false);
  protected readonly commandId = signal<string | undefined>(undefined);
  protected readonly receipt = signal<CommandReceipt | undefined>(undefined);
  protected readonly failure = signal<ApiFailure | undefined>(undefined);
  protected readonly terminal = computed(
    () =>
      isLifecycle(this.run().lifecycle) &&
      this.run().lifecycle?.terminalLatched === true,
  );
  private readonly identity = computed(() => {
    const run = this.run();
    const known = isLifecycle(run.lifecycle)
      ? run.lifecycle.controlDecisions.find(
          (decision) => decision.kind === 'CANCELLATION_REQUEST',
        )?.id
      : undefined;
    return `${run.runId}/${known ?? ''}`;
  });

  constructor() {
    effect(() => {
      const identity = this.identity();
      untracked(() => {
        this.request?.abort();
        this.busy.set(false);
        this.receipt.set(undefined);
        this.failure.set(undefined);
        this.storageFailure.set(false);
        const [runId, known] = identity.split('/');
        if (!runId) return;
        try {
          this.commandId.set(known || this.journal.savedCancellation(runId));
        } catch {
          this.commandId.set(known || undefined);
          this.storageFailure.set(true);
        }
        if (this.commandId()) void this.refresh();
      });
    });
    inject(DestroyRef).onDestroy(() => this.request?.abort());
  }

  protected cancel() {
    return this.perform(true);
  }
  protected refresh() {
    return this.perform(false);
  }

  private async perform(submit: boolean) {
    if (
      this.busy() ||
      (submit &&
        (this.historical() || this.terminal() || this.storageFailure()))
    )
      return;
    this.request?.abort();
    const controller = (this.request = new AbortController());
    const runId = this.run().runId;
    this.busy.set(true);
    this.failure.set(undefined);
    try {
      let id = this.commandId();
      if (submit) {
        try {
          id = await this.journal.cancellation(runId, id);
        } catch {
          this.storageFailure.set(true);
          return;
        }
        if (controller.signal.aborted) return;
        this.commandId.set(id);
      }
      if (!id) return;
      const signal = AbortSignal.any([
        controller.signal,
        AbortSignal.timeout(40_000),
      ]);
      const receipt = await (submit
        ? this.api.cancel(runId, id, signal)
        : this.api.command(runId, id, signal));
      if (controller.signal.aborted) return;
      this.receipt.set(receipt);
      if (submit) this.refreshRun.emit();
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof ApiRequestFailure) this.failure.set(error.outcome);
      else this.errors.handleError(error);
    } finally {
      if (!controller.signal.aborted) this.busy.set(false);
    }
  }
}
