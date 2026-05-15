import { Routes } from '@angular/router';
import { Dashboard } from './pages/dashboard/dashboard';
import { Bots } from './pages/bots/bots';
import { BotCreate } from './pages/bots/bot-create';
import { Trades } from './pages/trades/trades';
import { TradeDetail } from './pages/trades/trade-detail';
import { EventDetail } from './pages/trades/event-detail';
import { Orders } from './pages/orders/orders';
import { OrderDetail } from './pages/orders/order-detail';
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
      { path: 'bots/create', component: BotCreate },
      { path: 'trades', component: Trades },
      { path: 'trades/:id', component: TradeDetail },
      { path: 'events/:id', component: EventDetail },
      { path: 'orders', component: Orders },
      { path: 'orders/:id', component: OrderDetail },
      { path: 'markets', component: Markets },
      { path: 'markets/:id', component: MarketDetail },
    ]
  }
];
