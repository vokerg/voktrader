import { Routes } from '@angular/router';
import { Dashboard } from './pages/dashboard/dashboard';
import { Bots } from './pages/bots/bots';
import { Trades } from './pages/trades/trades';
import { TradeDetail } from './pages/trades/trade-detail';
import { Markets } from './pages/markets/markets';
import { MarketDetail } from './pages/markets/market-detail';
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
      { path: 'trades/:id', component: TradeDetail },
      { path: 'markets', component: Markets },
      { path: 'markets/:id', component: MarketDetail },
    ]
  }
];
