import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'sky-overview-page',
  template: `
    <section aria-labelledby="overview-heading">
      <p class="eyebrow">Control plane</p>
      <h2 id="overview-heading">Overview</h2>
      <p class="lede">
        Skywright provides a portable contract for defining and running
        machine-learning training work across local and cloud targets.
      </p>
      <p>
        It keeps project-specific training control flow where it belongs: with
        the project.
      </p>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OverviewPage {}
