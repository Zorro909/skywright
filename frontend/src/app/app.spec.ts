import { ErrorHandler } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { App } from './app';
import { ApiRequestFailure } from './api/api-failure';
import { SYSTEM_INFORMATION_LOADER } from './api/system-information.api';
import { routes } from './app.routes';
import { UnexpectedApplicationFailure } from './unexpected-application-failure';

describe('application shell', () => {
  it('keeps product navigation available while routes change', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes)],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/about');
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(root.querySelector('header h1')?.textContent).toContain('Skywright');
    expect(root.querySelector('nav[aria-label="Primary"]')).not.toBeNull();
    expect(root.textContent).toContain('Target Storages');
    expect(root.querySelector('main h2')?.textContent).toContain(
      'About Skywright',
    );
  });

  it('shows the running backend build identity on About', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        {
          provide: SYSTEM_INFORMATION_LOADER,
          useValue: () =>
            Promise.resolve({
              apiVersion: '1.0.0',
              applicationVersion: '0.1.0-test',
              sourceRevision: '0123456789abcdef0123456789abcdef01234567',
            }),
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/about');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const about = fixture.nativeElement as HTMLElement;
    expect(about.textContent).toContain('API version 1.0.0');
    expect(about.textContent).toContain('Application version 0.1.0-test');
    expect(about.textContent).toContain(
      'Source revision 0123456789abcdef0123456789abcdef01234567',
    );
  });

  it('cancels the System Information request when About is destroyed', async () => {
    let requestSignal: AbortSignal | undefined;
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        {
          provide: SYSTEM_INFORMATION_LOADER,
          useValue: (abortSignal: AbortSignal) => {
            requestSignal = abortSignal;
            return new Promise(() => undefined);
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/about');
    fixture.detectChanges();
    await vi.waitFor(() => expect(requestSignal).toBeDefined());

    fixture.destroy();

    expect(requestSignal?.aborted).toBe(true);
  });

  it('routes an unexpected request-loader defect to the application boundary', async () => {
    const consoleError = vi
      .spyOn(console, 'error')
      .mockImplementation(() => undefined);
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        UnexpectedApplicationFailure,
        {
          provide: ErrorHandler,
          useExisting: UnexpectedApplicationFailure,
        },
        {
          provide: SYSTEM_INFORMATION_LOADER,
          useValue: () =>
            Promise.reject(new Error('unsafe request-loader detail')),
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/about');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const view = fixture.nativeElement as HTMLElement;

    expect(view.textContent).toContain('Something went wrong');
    expect(view.textContent).not.toContain('The server could not be reached.');
    expect(view.textContent).not.toContain('unsafe request-loader detail');
    expect(consoleError).toHaveBeenCalled();
  });

  it('retains a resolved identity while reloading and after reload failure', async () => {
    let rejectReload: ((failure: Error) => void) | undefined;
    let loadCount = 0;
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        {
          provide: SYSTEM_INFORMATION_LOADER,
          useValue: () => {
            loadCount += 1;
            if (loadCount === 1) {
              return Promise.resolve({
                apiVersion: '1.0.0',
                applicationVersion: 'last-seen',
                sourceRevision: null,
              });
            }
            return new Promise((_resolve, reject) => {
              rejectReload = reject;
            });
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/about');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const view = fixture.nativeElement as HTMLElement;

    findButton(view, 'Reload System Information').click();
    fixture.detectChanges();

    expect(view.textContent).toContain('Application version last-seen');
    expect(findButton(view, 'Reload System Information').disabled).toBe(true);
    expect(view.querySelector('[role="status"]')?.textContent).toContain(
      'Reloading System Information…',
    );

    rejectReload?.(new ApiRequestFailure({ kind: 'network' }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(view.textContent).toContain('System Information unavailable');
    expect(view.textContent).toContain('Last-seen information');
    expect(view.textContent).toContain('Application version last-seen');
    expect(findButton(view, 'Retry').disabled).toBe(false);
    expect(loadCount).toBe(2);
  });

  it('keeps failure local through a failed retry and recovery', async () => {
    let rejectRetry: ((failure: Error) => void) | undefined;
    let loadCount = 0;
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes),
        {
          provide: SYSTEM_INFORMATION_LOADER,
          useValue: () => {
            loadCount += 1;
            if (loadCount === 1) {
              return Promise.reject(new ApiRequestFailure({ kind: 'network' }));
            }
            if (loadCount === 2) {
              return new Promise((_resolve, reject) => {
                rejectRetry = reject;
              });
            }
            return Promise.resolve({
              apiVersion: '1.0.0',
              applicationVersion: 'recovered',
              sourceRevision: null,
            });
          },
        },
      ],
    }).compileComponents();

    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/about');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const view = fixture.nativeElement as HTMLElement;

    expect(view.textContent).toContain('System Information unavailable');
    expect(view.textContent).toContain(
      'No last-seen information is available.',
    );
    expect(view.querySelector('nav[aria-label="Primary"]')).not.toBeNull();
    expect(findButton(view, 'Retry').disabled).toBe(false);
    expect(view.querySelector('#system-information-heading')).toBe(
      document.activeElement,
    );

    findButton(view, 'Retry').click();
    fixture.detectChanges();
    expect(findButton(view, 'Retry').disabled).toBe(true);
    expect(view.textContent).toContain('Retrying System Information…');

    rejectRetry?.(new ApiRequestFailure({ kind: 'network' }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(view.textContent).toContain('System Information unavailable');
    expect(findButton(view, 'Retry').disabled).toBe(false);
    expect(view.querySelector('#system-information-heading')).toBe(
      document.activeElement,
    );

    findButton(view, 'Retry').click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(view.textContent).toContain('Application version recovered');
    expect(view.textContent).not.toContain('System Information unavailable');
    expect(view.querySelector('#system-information-heading')).toBe(
      document.activeElement,
    );
    expect(loadCount).toBe(3);
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
