import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'sky-not-found-page',
  imports: [RouterLink],
  template: `
    <section aria-labelledby="not-found-heading">
      <p class="eyebrow">404</p>
      <h2 id="not-found-heading">Page not found</h2>
      <p>The application location you requested does not exist.</p>
      <a routerLink="/">Return to overview</a>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotFoundPage {}
