import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { BotConfigResponse, TradeSummaryResponse } from '../../models/api.models';

@Component({
  selector: 'app-dashboard',
  imports: [CommonModule],
  template: `
    <div class="dashboard-grid">
      <div class="stat-card">
        <h3>Active Bots</h3>
        <div class="value">{{ activeBotsCount() }}</div>
      </div>
      <div class="stat-card">
        <h3>Total Trades</h3>
        <div class="value">{{ trades().length }}</div>
      </div>
      <div class="stat-card">
        <h3>Daily PnL</h3>
        <div class="value" [class.positive]="dailyPnl() > 0" [class.negative]="dailyPnl() < 0">
          $ {{ dailyPnl() | number:'1.2-2' }}
        </div>
      </div>
    </div>

    <div class="recent-section">
      <h2>Recent Trades</h2>
      <div class="card">
        <table *ngIf="trades().length > 0; else noTrades">
          <thead>
            <tr>
              <th>Time</th>
              <th>Bot</th>
              <th>Market</th>
              <th>Status</th>
              <th>PnL</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let trade of trades() | slice:0:5">
              <td>{{ trade.decisionAt | date:'short' }}</td>
              <td>Bot #{{ trade.botId }}</td>
              <td>{{ trade.marketSlug }}</td>
              <td>{{ trade.status }}</td>
              <td [class.positive]="trade.finalPnlUsd > 0" [class.negative]="trade.finalPnlUsd < 0">
                $ {{ trade.finalPnlUsd | number:'1.2-2' }}
              </td>
            </tr>
          </tbody>
        </table>
        <ng-template #noTrades>
          <p>No recent trades found.</p>
        </ng-template>
      </div>
    </div>
  `,
  styles: `
    .dashboard-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 24px;
      margin-bottom: 32px;
    }

    .stat-card {
      background-color: #fff;
      padding: 24px;
      border-radius: 8px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.1);
    }

    .stat-card h3 {
      margin: 0 0 8px 0;
      color: #6b7280;
      font-size: 14px;
      font-weight: 500;
      text-transform: uppercase;
    }

    .stat-card .value {
      font-size: 32px;
      font-weight: 700;
    }

    .positive { color: #10b981; }
    .negative { color: #ef4444; }

    .recent-section h2 {
      margin-bottom: 16px;
      font-size: 20px;
    }

    .card {
      background-color: #fff;
      border-radius: 8px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.1);
      overflow: hidden;
    }

    table {
      width: 100%;
      border-collapse: collapse;
    }

    th {
      text-align: left;
      padding: 12px 16px;
      background-color: #f9fafb;
      border-bottom: 1px solid #e5e7eb;
      font-size: 12px;
      font-weight: 600;
      color: #6b7280;
      text-transform: uppercase;
    }

    td {
      padding: 12px 16px;
      border-bottom: 1px solid #f3f4f6;
      font-size: 14px;
    }

    tr:last-child td {
      border-bottom: none;
    }
  `
})
export class Dashboard implements OnInit {
  private apiService = inject(ApiService);
  
  bots = signal<BotConfigResponse[]>([]);
  trades = signal<TradeSummaryResponse[]>([]);
  
  activeBotsCount = signal(0);
  dailyPnl = signal(0);

  ngOnInit() {
    this.loadData();
  }

  loadData() {
    this.apiService.getBots().subscribe(bots => {
      this.bots.set(bots);
      this.activeBotsCount.set(bots.filter(b => b.enabled).length);
    });

    this.apiService.getTrades({ limit: 50 }).subscribe(trades => {
      this.trades.set(trades);
      const pnl = trades.reduce((acc, t) => acc + (t.finalPnlUsd || 0), 0);
      this.dailyPnl.set(pnl);
    });
  }
}
