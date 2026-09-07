import {
  ChangeDetectionStrategy,
  Component,
  ErrorHandler,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RUN_API, type Progress } from '../api/run.api';
import { ApiRequestFailure, type ApiFailure } from '../api/api-failure';
import { RequestFailure } from './request-failure';
import { ObservationAge } from './observation-age';

@Component({
  selector: 'sky-run-progress',
  imports: [DatePipe, DecimalPipe, RequestFailure, ObservationAge],
  template: `
    <section aria-label="Committed progress">
      <h4>Committed progress</h4>
      @if (loading()) {
        <p role="status">Reading Progress Record…</p>
      }
      @if (failure(); as failure) {
        <p>Progress unavailable.</p>
        <sky-request-failure [failure]="failure" />
      }
      @if (observation(); as observation) {
        @if (observation.record; as record) {
          @if (failure() || loading()) {
            <p><strong>Last-seen Progress Record</strong></p>
          }
          <p>
            Committed Step <strong>{{ record.currentStep }}</strong>
            @if (
              record.targetStep !== null && record.targetStep !== undefined
            ) {
              of {{ record.targetStep }}
              @if (record.targetStep > 0) {
                ({{
                  (record.currentStep / record.targetStep) * 100
                    | number: '1.0-1'
                }}%)
              }
            }
          </p>
          @if (record.targetStep === null || record.targetStep === undefined) {
            <p>No target Step reported.</p>
          }
          <p>
            Latest Durable Safe Point:
            <strong>{{ record.latestDurableStep ?? 'not reported' }}</strong>
          </p>
          @if (
            record.latestDurableStep !== null &&
            record.latestDurableStep !== undefined &&
            record.latestDurableStep < record.currentStep
          ) {
            <p>
              {{ record.currentStep - record.latestDurableStep }} committed
              Steps beyond the Durable Safe Point.
            </p>
          }
          @if (record.latestDurableCheckpoint) {
            <p class="reference">
              Checkpoint: <code>{{ record.latestDurableCheckpoint }}</code>
            </p>
          }
          <p>
            Written {{ record.writtenAt | date: 'medium' }} ·
            <sky-observation-age [at]="record.writtenAt" />
          </p>
          <p>
            Progress is a saved observation; it does not establish lifecycle or
            a newer durable checkpoint.
          </p>
        } @else {
          <p>
            Progress {{ observation.availability }}. No committed Step or
            Durable Safe Point can be reported from this read.
          </p>
        }
        <p>Run Store read {{ observation.fetchedAt | date: 'medium' }}</p>
      }
      <button
        type="button"
        [disabled]="loading()"
        (click)="revision.update(increment)"
      >
        Refresh progress
      </button>
    </section>
  `,
  styles: `
    .reference {
      overflow-wrap: anywhere;
    }
    h4 {
      margin-bottom: 0.5rem;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunProgress {
  readonly runId = input.required<string>();
  private readonly api = inject(RUN_API);
  private readonly errors = inject(ErrorHandler);
  protected readonly observation = signal<Progress | undefined>(undefined);
  protected readonly failure = signal<ApiFailure | undefined>(undefined);
  protected readonly loading = signal(false);
  protected readonly revision = signal(0);
  protected readonly increment = (value: number) => value + 1;
  private previousId?: string;
  constructor() {
    effect((cleanup) => {
      const id = this.runId();
      this.revision();
      if (id !== this.previousId) this.observation.set(undefined);
      this.previousId = id;
      const request = new AbortController();
      cleanup(() => request.abort());
      this.loading.set(true);
      this.failure.set(undefined);
      void this.api
        .progress(
          id,
          AbortSignal.any([request.signal, AbortSignal.timeout(10_000)]),
        )
        .then((value) => {
          if (!request.signal.aborted) this.observation.set(value);
        })
        .catch((error: unknown) => {
          if (request.signal.aborted) return;
          if (error instanceof ApiRequestFailure)
            this.failure.set(error.outcome);
          else this.errors.handleError(error);
        })
        .finally(() => {
          if (!request.signal.aborted) this.loading.set(false);
        });
    });
  }
}
