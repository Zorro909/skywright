import {
  ChangeDetectionStrategy,
  Component,
  input,
  signal,
} from '@angular/core';

import type { ApiFailure } from '../api/api-failure';

@Component({
  selector: 'sky-request-failure',
  template: `
    <section class="request-failure" aria-label="Request failure">
      @switch (failure().kind) {
        @case ('problem') {
          @if (problem(); as problem) {
            <h3>
              {{ problem.title ?? 'Request failed' }}
            </h3>
            @if (problem.detail) {
              <p>{{ problem.detail }}</p>
            }
            <p>
              <strong>Error code:</strong> <code>{{ problem.errorCode }}</code>
            </p>
            @if (problem.fieldViolations.length > 0) {
              <h4>Fields that need attention</h4>
              <ul>
                @for (violation of problem.fieldViolations; track $index) {
                  <li>
                    <code>{{ violation.field }}</code
                    >: {{ violation.message }}
                  </li>
                }
              </ul>
            }
            <p>
              <strong>Correlation identifier:</strong>
              <code>{{ problem.correlationId }}</code>
              <button
                type="button"
                (click)="copyCorrelation(problem.correlationId)"
              >
                Copy
              </button>
            </p>
          }
        }
        @case ('malformed-response') {
          <h3>Request failed</h3>
          <p>The server returned an unreadable response.</p>
        }
        @case ('network') {
          <h3>Request failed</h3>
          <p>The server could not be reached.</p>
        }
        @case ('aborted') {
          <h3>Request cancelled</h3>
          <p>The request was cancelled.</p>
        }
      }
      <p class="copy-status" role="status" aria-live="polite">
        {{ copyStatus() }}
      </p>
    </section>
  `,
  styles: `
    .request-failure {
      padding: 1rem;
      border: 1px solid var(--danger-border, var(--border));
      border-radius: 0.75rem;
      background: var(--soft-surface);
    }

    h3 {
      margin-top: 0;
    }

    button {
      margin-inline-start: 0.5rem;
    }

    .copy-status:empty {
      margin: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RequestFailure {
  readonly failure = input.required<ApiFailure>();
  protected readonly copyStatus = signal('');

  protected problem() {
    const failure = this.failure();
    return failure.kind === 'problem' ? failure.problem : undefined;
  }

  protected async copyCorrelation(correlationId: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(correlationId);
      this.copyStatus.set('Correlation identifier copied.');
    } catch {
      this.copyStatus.set('Could not copy the correlation identifier.');
    }
  }
}
