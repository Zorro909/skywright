import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  ErrorHandler,
  afterRenderEffect,
  inject,
  resource,
  signal,
  viewChild,
} from '@angular/core';

import type { ApiFailure } from '../api/api-failure';
import { apiFailureFrom } from '../api/api-failure';
import { SYSTEM_INFORMATION_LOADER } from '../api/system-information.api';
import { CapabilityUnavailable } from '../shared/capability-unavailable';
import { RequestFailure } from '../shared/request-failure';

@Component({
  selector: 'sky-about-page',
  imports: [CapabilityUnavailable, RequestFailure],
  template: `
    <article aria-labelledby="about-heading">
      <p class="eyebrow">About</p>
      <h2 id="about-heading">About Skywright</h2>
      <p class="lede">
        Skywright is a portable contract for machine-learning training across
        local and cloud targets.
      </p>
      <p>
        This web application is served by the same private control plane that it
        operates, keeping the interface and backend version-paired.
      </p>
      <section aria-labelledby="system-information-heading">
        <h3
          #systemInformationHeading
          id="system-information-heading"
          tabindex="-1"
        >
          System Information
        </h3>
        @if (failure(); as failure) {
          <sky-capability-unavailable
            capability="System Information"
            source="control-plane API"
            reason="Build identity could not be read from this source."
            [observedAt]="lastSeenObservedAt() ?? failureObservedAt()"
            [hasLastSeen]="lastSeen() !== undefined"
            [retrying]="systemInformation.status() === 'reloading'"
            (retry)="systemInformation.reload()"
          >
            <sky-request-failure [failure]="failure" />
            @if (lastSeen(); as previous) {
              <div last-seen>
                <p>API version {{ previous.apiVersion }}</p>
                <p>Application version {{ previous.applicationVersion }}</p>
                @if (previous.sourceRevision; as sourceRevision) {
                  <p>Source revision {{ sourceRevision }}</p>
                }
              </div>
            }
          </sky-capability-unavailable>
        } @else if (systemInformation.hasValue()) {
          @let current = systemInformation.value();
          <p>API version {{ current.apiVersion }}</p>
          <p>Application version {{ current.applicationVersion }}</p>
          @if (current.sourceRevision; as sourceRevision) {
            <p>Source revision {{ sourceRevision }}</p>
          }
          <button
            type="button"
            [disabled]="systemInformation.status() === 'reloading'"
            (click)="systemInformation.reload()"
          >
            Reload System Information
          </button>
          <p role="status" aria-live="polite">
            @if (systemInformation.status() === 'reloading') {
              Reloading System Information…
            } @else {
              System Information loaded.
            }
          </p>
        } @else {
          <p role="status" aria-live="polite">Loading System Information…</p>
        }
      </section>
    </article>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AboutPage {
  private readonly loadSystemInformation = inject(SYSTEM_INFORMATION_LOADER);
  private readonly unexpectedFailure = inject(ErrorHandler);
  private readonly systemInformationHeading = viewChild<
    ElementRef<HTMLElement>
  >('systemInformationHeading');
  protected readonly lastSeen = signal<
    Awaited<ReturnType<typeof this.loadSystemInformation>> | undefined
  >(undefined);
  protected readonly lastSeenObservedAt = signal<Date | undefined>(undefined);
  protected readonly failureObservedAt = signal(new Date());

  protected readonly systemInformation = resource({
    loader: async ({ abortSignal }) => {
      try {
        const value = await this.loadSystemInformation(abortSignal);
        this.lastSeen.set(value);
        this.lastSeenObservedAt.set(new Date());
        return value;
      } catch (error) {
        this.failureObservedAt.set(new Date());
        if (!apiFailureFrom(error)) {
          this.unexpectedFailure.handleError(error);
        }
        throw error;
      }
    },
  });

  constructor() {
    let previousStatus = this.systemInformation.status();
    afterRenderEffect(() => {
      const status = this.systemInformation.status();
      if (
        status === 'error' ||
        (status === 'resolved' && previousStatus === 'reloading')
      ) {
        this.systemInformationHeading()?.nativeElement.focus();
      }
      previousStatus = status;
    });
  }

  protected failure(): ApiFailure | undefined {
    return apiFailureFrom(this.systemInformation.error());
  }
}
