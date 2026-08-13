import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import axe from 'axe-core';

import type { ApiFailure } from '../api/api-failure';
import { RequestFailure } from './request-failure';

@Component({
  imports: [RequestFailure],
  template: `<sky-request-failure [failure]="failure()" />`,
})
class TestHost {
  readonly failure = signal<ApiFailure>({ kind: 'network' });
}

describe('request failure presentation', () => {
  let fixture: ComponentFixture<TestHost>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TestHost],
    }).compileComponents();
    fixture = TestBed.createComponent(TestHost);
  });

  it('shows only safe Problem diagnostics and copies the correlation identifier', async () => {
    const writeText = vi.fn<(text: string) => Promise<void>>(() =>
      Promise.resolve(),
    );
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    });
    fixture.componentInstance.failure.set({
      kind: 'problem',
      problem: {
        title: 'Invalid request',
        detail: 'Two fields need attention.',
        errorCode: 'SKYWRIGHT_INVALID_REQUEST',
        correlationId: 'request-42',
        fieldViolations: [
          {
            field: 'configuration.batchSize',
            code: 'minimum',
            message: 'Must be at least 1.',
          },
          {
            field: 'configuration.batchSize',
            code: 'multipleOf',
            message: 'Must be a whole number.',
          },
        ],
      },
      response: new Response('unsafe raw body', { status: 422 }),
    });
    fixture.detectChanges();

    const view = fixture.nativeElement as HTMLElement;
    expect(view.textContent).toContain('Invalid request');
    expect(view.textContent).toContain('Two fields need attention.');
    expect(view.textContent).toContain('SKYWRIGHT_INVALID_REQUEST');
    expect(view.textContent).toContain('configuration.batchSize');
    expect(view.textContent).toContain('Must be at least 1.');
    expect(view.textContent).toContain('Must be a whole number.');
    expect(
      Array.from(view.querySelectorAll('li')).filter((item) =>
        item.textContent?.includes('configuration.batchSize'),
      ),
    ).toHaveLength(2);
    expect(view.textContent).toContain('request-42');
    expect(view.textContent).not.toContain('unsafe raw body');

    view.querySelector<HTMLButtonElement>('button')?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(writeText).toHaveBeenCalledWith('request-42');
    expect(view.querySelector('[role="status"]')?.textContent).toContain(
      'Correlation identifier copied.',
    );
    expect((await axe.run(view)).violations).toEqual([]);
  });

  it.each([
    [
      { kind: 'malformed-response', response: new Response('secret') },
      'The server returned an unreadable response.',
    ],
    [{ kind: 'network' }, 'The server could not be reached.'],
    [{ kind: 'aborted' }, 'The request was cancelled.'],
  ] satisfies readonly [ApiFailure, string][])(
    '%s stays safely distinct',
    (failure, message) => {
      fixture.componentInstance.failure.set(failure);
      fixture.detectChanges();

      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        message,
      );
      expect((fixture.nativeElement as HTMLElement).textContent).not.toContain(
        'secret',
      );
    },
  );
});
