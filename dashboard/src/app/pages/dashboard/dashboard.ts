import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { BotConfigResponse, RuntimeStatusResponse, StrategyCatalogResponse, TradeSummaryResponse } from '../../models/api.models';

@Component({
  selector: 'app-dashboard',
  imports: [CommonModule, RouterModule],
  template: `
    <div class="dashboard-grid">
      <div class="stat-card clickable" routerLink="/bots">
        <h3>Active Bots</h3>
        <div class="value">{{ activeBotsCount() }}</div>
      </div>
      <div class="stat-card clickable" routerLink="/trades">
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

    <section class="runtime-section" *ngIf="runtimeStatus() as runtime">
      <h2>Runtime</h2>
      <div class="runtime-grid">
        <div class="card panel">
          <h3>Mode</h3>
          <dl>
            <div><dt>Profiles</dt><dd>{{ runtime.activeProfiles.join(', ') || 'default' }}</dd></div>
            <div><dt>Trading</dt><dd>{{ runtime.tradingMode }}</dd></div>
            <div><dt>Kill Switch</dt><dd [class.negative]="runtime.killSwitchEnabled" [class.positive]="!runtime.killSwitchEnabled">{{ runtime.killSwitchEnabled ? 'ON' : 'OFF' }}</dd></div>
            <div><dt>Live Enabled</dt><dd [class.positive]="runtime.liveEnabled" [class.negative]="!runtime.liveEnabled">{{ runtime.liveEnabled ? 'YES' : 'NO' }}</dd></div>
          </dl>
        </div>

        <div class="card panel">
          <h3>Risk</h3>
          <dl>
            <div><dt>Max Order</dt><dd>$ {{ runtime.maxOrderUsd | number:'1.2-2' }}</dd></div>
            <div><dt>Trades / Market</dt><dd>{{ runtime.maxTradesPerMarket }}</dd></div>
            <div><dt>Open Live</dt><dd>{{ runtime.maxOpenLiveTrades }}</dd></div>
            <div><dt>Allowed</dt><dd>{{ runtime.allowedStrategyIds.join(', ') }}</dd></div>
          </dl>
        </div>

        <div class="card panel">
          <h3>Executor</h3>
          <dl>
            <div><dt>Enabled</dt><dd>{{ runtime.executor.enabled ? 'YES' : 'NO' }}</dd></div>
            <div><dt>Dry Run</dt><dd>{{ runtime.executor.dryRun ? 'YES' : 'NO' }}</dd></div>
            <div><dt>Base URL</dt><dd>{{ runtime.executor.baseUrl }}</dd></div>
            <div><dt>Order Layer</dt><dd>{{ runtime.orderLayer.enabled ? 'ON' : 'OFF' }}</dd></div>
          </dl>
        </div>
      </div>
    </section>

    <section class="runtime-section" *ngIf="strategyCatalog() as catalog">
      <h2>Strategies</h2>
      <div class="card panel">
        <dl>
          <div><dt>Default</dt><dd>{{ catalog.currentDefaultActiveStrategy }}</dd></div>
          <div><dt>V2 Sets</dt><dd>{{ catalog.strategyV2SetIds.join(', ') }}</dd></div>
          <div><dt>V2 Active Inner IDs</dt><dd>{{ catalog.strategyV2ActiveInnerStrategyIds.join(', ') }}</dd></div>
          <div><dt>Backtest IDs</dt><dd>{{ catalog.validBacktestStrategyIds.join(', ') }}</dd></div>
        </dl>
      </div>
    </section>

    <section class="runtime-section" *ngIf="runtimeStatus()?.enabledBots?.length">
      <h2>Enabled Bots</h2>
      <div class="card">
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>Name</th>
              <th>Market</th>
              <th>Strategy</th>
              <th>Set</th>
              <th>Sub</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let bot of runtimeStatus()?.enabledBots" class="clickable-row" routerLink="/bots">
              <td>{{ bot.id }}</td>
              <td>{{ bot.name }}</td>
              <td>{{ bot.marketFamily }}</td>
              <td>{{ bot.strategyId }}</td>
              <td>{{ bot.strategySetId || '-' }}</td>
              <td>{{ bot.subStrategyId || '-' }}</td>
              <td>{{ bot.status }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div class="recent-section">
      <div class="section-header">
        <h2>Recent Trades</h2>
        <a routerLink="/trades" class="view-all">View All →</a>
      </div>
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
            <tr *ngFor="let trade of trades() | slice:0:5" class="clickable-row" [routerLink]="['/trades', trade.id]">
              <td>{{ trade.decisionAt | date:'short' }}</td>
              <td>Bot #{{ trade.botId }}</td>
              <td>
                <a [routerLink]="['/markets', trade.marketId]" (click)="$event.stopPropagation()" class="link">
                  {{ trade.marketSlug }}
                </a>
              </td>
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
      transition: transform 0.2s, box-shadow 0.2s;
    }

    .stat-card.clickable:hover {
      transform: translateY(-2px);
      box-shadow: 0 4px 6px rgba(0,0,0,0.1);
      cursor: pointer;
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

    .section-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 16px;
    }

    .section-header h2 {
      margin: 0;
      font-size: 20px;
    }

    .view-all {
      font-size: 14px;
      color: #3b82f6;
      text-decoration: none;
      font-weight: 500;
    }

    .runtime-section {
      margin-bottom: 32px;
    }

    .runtime-section h2 {
      margin-bottom: 16px;
      font-size: 20px;
    }

    .runtime-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
      gap: 16px;
    }

    .card {
      background-color: #fff;
      border-radius: 8px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.1);
      overflow: hidden;
    }

    .panel {
      padding: 18px;
    }

    .panel h3 {
      margin: 0 0 12px;
      font-size: 14px;
      text-transform: uppercase;
      color: #6b7280;
    }

    dl {
      margin: 0;
      display: grid;
      gap: 10px;
    }

    dl div {
      display: grid;
      grid-template-columns: 120px minmax(0, 1fr);
      gap: 12px;
    }

    dt {
      color: #6b7280;
      font-size: 13px;
    }

    dd {
      margin: 0;
      font-size: 13px;
      overflow-wrap: anywhere;
      font-weight: 600;
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

    .clickable-row {
      cursor: pointer;
      transition: background-color 0.2s;
    }

    .clickable-row:hover {
      background-color: #f9fafb;
    }

    .link {
      color: #3b82f6;
      text-decoration: none;
    }

    .link:hover {
      text-decoration: underline;
    }
  `
})
export class Dashboard implements OnInit {
  private apiService = inject(ApiService);
  
  bots = signal<BotConfigResponse[]>([]);
  trades = signal<TradeSummaryResponse[]>([]);
  runtimeStatus = signal<RuntimeStatusResponse | null>(null);
  strategyCatalog = signal<StrategyCatalogResponse | null>(null);
  
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

    this.apiService.getRuntimeStatus().subscribe(status => this.runtimeStatus.set(status));
    this.apiService.getStrategies().subscribe(catalog => this.strategyCatalog.set(catalog));
  }
}
