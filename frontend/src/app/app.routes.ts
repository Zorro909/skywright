import { Routes } from '@angular/router';

import { AboutPage } from './pages/about.page';
import { NotFoundPage } from './pages/not-found.page';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () =>
      import('./pages/overview.page').then((module) => module.OverviewPage),
    title: 'Overview · Skywright',
  },
  {
    path: 'runs/new',
    loadComponent: () =>
      import('./pages/managed-run.page').then(
        (module) => module.ManagedRunPage,
      ),
    title: 'Create Run · Skywright',
  },
  {
    path: 'runs/new/advanced',
    loadComponent: () =>
      import('./pages/new-run.page').then((module) => module.NewRunPage),
    title: 'Create Run · Skywright',
  },
  {
    path: 'runs/:runId',
    loadComponent: () =>
      import('./pages/run-detail.page').then((module) => module.RunDetailPage),
    title: 'Run · Skywright',
  },
  { path: 'about', component: AboutPage, title: 'About · Skywright' },
  {
    path: 'target-storages',
    loadComponent: () =>
      import('./pages/target-storages.page').then(
        (module) => module.TargetStoragesPage,
      ),
    title: 'Target Storages · Skywright',
  },
  { path: '**', component: NotFoundPage, title: 'Page not found · Skywright' },
];
