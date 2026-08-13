import {
  ChangeDetectionStrategy,
  Component,
  inject,
  resource,
} from '@angular/core';

import { SYSTEM_INFORMATION_LOADER } from '../api/system-information.api';

@Component({
  selector: 'sky-about-page',
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
        <h3 id="system-information-heading">System Information</h3>
        @if (systemInformation.hasValue()) {
          <p>API version {{ systemInformation.value().apiVersion }}</p>
          <p>
            Application version
            {{ systemInformation.value().applicationVersion }}
          </p>
          @if (systemInformation.value().sourceRevision; as sourceRevision) {
            <p>Source revision {{ sourceRevision }}</p>
          }
        } @else if (systemInformation.isLoading()) {
          <p>Loading System Information…</p>
        } @else {
          <p>System Information is unavailable.</p>
        }
      </section>
    </article>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AboutPage {
  private readonly loadSystemInformation = inject(SYSTEM_INFORMATION_LOADER);

  protected readonly systemInformation = resource({
    loader: ({ abortSignal }) => this.loadSystemInformation(abortSignal),
  });
}
