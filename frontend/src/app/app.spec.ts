import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { App } from './app';
import { SYSTEM_INFORMATION_LOADER } from './api/system-information.api';
import { routes } from './app.routes';

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
});
