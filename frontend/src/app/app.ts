import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterRenderEffect,
  inject,
  viewChild,
} from '@angular/core';
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
  private readonly unexpectedFailureRegion = viewChild<ElementRef<HTMLElement>>(
    'unexpectedFailureRegion',
  );
  protected readonly unexpectedFailure = inject(UnexpectedApplicationFailure);

  constructor() {
    afterRenderEffect(() => {
      if (this.unexpectedFailure.isActive()) {
        this.unexpectedFailureRegion()?.nativeElement.focus();
      }
    });
  }

  protected async returnToOverview(): Promise<void> {
    this.unexpectedFailure.clear();
    await this.router.navigateByUrl('/');
    document.querySelector<HTMLElement>('#content')?.focus();
  }
}
