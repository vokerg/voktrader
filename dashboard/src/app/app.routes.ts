import { Routes } from '@angular/router';
import { Dashboard } from './pages/dashboard/dashboard';
import { Bots } from './pages/bots/bots';
import { Trades } from './pages/trades/trades';
import { Markets } from './pages/markets/markets';
import { Shell } from './components/shell/shell';

export const routes: Routes = [
  {
    path: '',
    component: Shell,
    children: [
      { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
      { path: 'dashboard', component: Dashboard },
      { path: 'bots', component: Bots },
      { path: 'trades', component: Trades },
      { path: 'markets', component: Markets },
    ]
  }
];
