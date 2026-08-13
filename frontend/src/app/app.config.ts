import {
  ApplicationConfig,
  ErrorHandler,
  inject,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import {
  provideRouter,
  RedirectCommand,
  Router,
  withInMemoryScrolling,
  withNavigationErrorHandler,
} from '@angular/router';

import { routes } from './app.routes';
import { UnexpectedApplicationFailure } from './unexpected-application-failure';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    UnexpectedApplicationFailure,
    {
      provide: ErrorHandler,
      useExisting: UnexpectedApplicationFailure,
    },
    provideRouter(
      routes,
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
      withNavigationErrorHandler((navigationError) => {
        inject(UnexpectedApplicationFailure).handleError(navigationError.error);
        return new RedirectCommand(inject(Router).parseUrl('/'));
      }),
    ),
  ],
};
