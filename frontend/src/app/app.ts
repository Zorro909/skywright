import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import {
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';

import { UnexpectedApplicationFailure } from './unexpected-application-failure';

@Component({
  selector: 'sky-root',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  private readonly router = inject(Router);
  protected readonly unexpectedFailure = inject(UnexpectedApplicationFailure);

  protected async returnToOverview(): Promise<void> {
    this.unexpectedFailure.clear();
    await this.router.navigateByUrl('/');
    document.querySelector<HTMLElement>('#content')?.focus();
  }
}
