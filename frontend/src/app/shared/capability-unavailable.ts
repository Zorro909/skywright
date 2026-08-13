import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

@Component({
  selector: 'sky-capability-unavailable',
  template: `
    <section
      class="unavailable"
      [attr.aria-label]="capability() + ' unavailable'"
    >
      <h3>{{ capability() }} unavailable</h3>
      <p><strong>Source:</strong> {{ source() }}</p>
      <p>{{ reason() }}</p>
      <p>{{ observationAge() }}</p>
      <ng-content />
      @if (hasLastSeen()) {
        <div class="last-seen">
          <h4>Last-seen information</h4>
          <ng-content select="[last-seen]" />
        </div>
      } @else {
        <p>No last-seen information is available.</p>
      }
      <button type="button" [disabled]="retrying()" (click)="retry.emit()">
        Retry
      </button>
      <p role="status" aria-live="polite">
        @if (retrying()) {
          Retrying {{ capability() }}…
        } @else {
          {{ capability() }} is unavailable.
        }
      </p>
    </section>
  `,
  styles: `
    .unavailable {
      padding: 1rem;
      border: 1px solid var(--border);
      border-radius: 0.75rem;
      background: var(--soft-surface);
    }

    h3 {
      margin-top: 0;
    }

    .last-seen {
      padding-inline-start: 1rem;
      border-inline-start: 0.25rem solid var(--border);
    }

    [role='status']:empty {
      margin: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CapabilityUnavailable {
  private readonly destroyRef = inject(DestroyRef);
  private readonly currentTime = signal(new Date());
  readonly capability = input.required<string>();
  readonly source = input.required<string>();
  readonly reason = input.required<string>();
  readonly observedAt = input.required<Date>();
  readonly now = input<Date>();
  readonly hasLastSeen = input(false);
  readonly retrying = input(false);
  readonly retry = output<void>();

  protected readonly observationAge = computed(() =>
    formatObservationAge(this.observedAt(), this.now() ?? this.currentTime()),
  );

  constructor() {
    const clock = globalThis.setInterval(
      () => this.currentTime.set(new Date()),
      30_000,
    );
    this.destroyRef.onDestroy(() => globalThis.clearInterval(clock));
  }
}

export function formatObservationAge(observedAt: Date, now: Date): string {
  const elapsedSeconds = Math.max(
    0,
    Math.floor((now.getTime() - observedAt.getTime()) / 1000),
  );
  if (elapsedSeconds < 60) {
    return 'Observed just now.';
  }

  const elapsedMinutes = Math.floor(elapsedSeconds / 60);
  if (elapsedMinutes < 60) {
    return `Observed ${elapsedMinutes} ${elapsedMinutes === 1 ? 'minute' : 'minutes'} ago.`;
  }

  const elapsedHours = Math.floor(elapsedMinutes / 60);
  if (elapsedHours < 24) {
    return `Observed ${elapsedHours} ${elapsedHours === 1 ? 'hour' : 'hours'} ago.`;
  }

  const elapsedDays = Math.floor(elapsedHours / 24);
  return `Observed ${elapsedDays} ${elapsedDays === 1 ? 'day' : 'days'} ago.`;
}
