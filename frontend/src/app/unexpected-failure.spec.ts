import { Component, ErrorHandler, inject } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import axe from 'axe-core';
import {
  provideRouter,
  RedirectCommand,
  Router,
  withNavigationErrorHandler,
} from '@angular/router';

import { App } from './app';
import { UnexpectedApplicationFailure } from './unexpected-application-failure';
import { OverviewPage } from './pages/overview.page';

@Component({ template: `<p>This route must not render.</p>` })
class BrokenPage {}

describe('unexpected application failure boundary', () => {
  it('contains an unexpected route failure and safely returns to Overview', async () => {
    const consoleError = vi
      .spyOn(console, 'error')
      .mockImplementation(() => undefined);
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(
          [
            { path: '', pathMatch: 'full', component: OverviewPage },
            {
              path: 'broken',
              component: BrokenPage,
              resolve: {
                view: () => {
                  throw new Error('unsafe implementation stack detail');
                },
              },
            },
          ],
          withNavigationErrorHandler((navigationError) => {
            inject(UnexpectedApplicationFailure).handleError(
              navigationError.error,
            );
            return new RedirectCommand(inject(Router).parseUrl('/'));
          }),
        ),
        UnexpectedApplicationFailure,
        {
          provide: ErrorHandler,
          useExisting: UnexpectedApplicationFailure,
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/broken');
    fixture.detectChanges();
    const view = fixture.nativeElement as HTMLElement;

    expect(view.textContent).toContain('Something went wrong');
    expect(view.textContent).toContain('Return to Overview');
    expect(view.textContent).not.toContain(
      'unsafe implementation stack detail',
    );
    expect(view.querySelector('nav[aria-label="Primary"]')).not.toBeNull();
    expect(view.querySelector('[role="alert"]')).toBe(document.activeElement);
    expect(consoleError).toHaveBeenCalled();
    expect((await axe.run(view)).violations).toEqual([]);

    findButton(view, 'Return to Overview').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(TestBed.inject(Router).url).toBe('/');
    expect(view.textContent).toContain('Overview');
    expect(view.textContent).not.toContain('Something went wrong');
  });
});

function findButton(view: HTMLElement, name: string): HTMLButtonElement {
  const button = Array.from(view.querySelectorAll('button')).find(
    (candidate) => candidate.textContent?.trim() === name,
  );
  if (!button) {
    throw new Error(`No button named ${name}`);
  }
  return button;
}
