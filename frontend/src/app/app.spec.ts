import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { App } from './app';
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
});
