import { Routes } from '@angular/router';

import { AboutPage } from './pages/about.page';
import { NotFoundPage } from './pages/not-found.page';
import { OverviewPage } from './pages/overview.page';
import { TargetStoragesPage } from './pages/target-storages.page';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    component: OverviewPage,
    title: 'Overview · Skywright',
  },
  { path: 'about', component: AboutPage, title: 'About · Skywright' },
  {
    path: 'target-storages',
    component: TargetStoragesPage,
    title: 'Target Storages · Skywright',
  },
  { path: '**', component: NotFoundPage, title: 'Page not found · Skywright' },
];
