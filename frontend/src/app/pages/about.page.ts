import { ChangeDetectionStrategy, Component } from '@angular/core';

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
    </article>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AboutPage {}
