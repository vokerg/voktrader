import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { TradeSummaryResponse, DashboardOptionsResponse } from '../../models/api.models';

@Component({
  selector: 'app-trades',
  imports: [CommonModule, RouterModule, FormsModule],
  template: `
    <div class="header">
      <h1>Trade History</h1>
    </div>

    <div class="card filters-card">
      <div class="filters-grid">
        <div class="filter-group">
          <label>Status</label>
          <select [(ngModel)]="filters.status" (change)="loadTrades()">
            <option value="">All Statuses</option>
            <option *ngFor="let s of options()?.tradeStatuses" [value]="s">{{ s }}</option>
          </select>
        </div>

        <div class="filter-group">
          <label>Mode</label>
          <select [(ngModel)]="filters.mode" (change)="loadTrades()">
            <option value="">All Modes</option>
            <option *ngFor="let m of options()?.executionModes" [value]="m">{{ m }}</option>
          </select>
        </div>

        <div class="filter-group">
          <label>Strategy</label>
          <select [(ngModel)]="filters.strategyId" (change)="loadTrades()">
            <option value="">All Strategies</option>
            <option *ngFor="let s of options()?.strategies" [value]="s.id">{{ s.name }}</option>
          </select>
        </div>

        <div class="filter-group">
          <label>Bot ID</label>
          <input type="number" [(ngModel)]="filters.botId" (input)="loadTrades()" placeholder="Any ID">
        </div>

        <div class="filter-group">
          <label>Limit</label>
          <select [(ngModel)]="filters.limit" (change)="loadTrades()">
            <option [value]="50">50</option>
            <option [value]="100">100</option>
            <option [value]="200">200</option>
          </select>
        </div>
      </div>
    </div>

    <div class="card table-card">
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Trade / Bot</th>
            <th>Strategy</th>
            <th>Market</th>
            <th>Side</th>
            <th>Status</th>
            <th>Fill / Intended</th>
            <th>PnL</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let trade of trades()" [routerLink]="['/trades', trade.id]" class="clickable-row">
            <td>
              <div class="time-main">{{ trade.decisionAt | date:'MMM d, HH:mm' }}</div>
              <div class="time-sub">{{ trade.decisionAt | date:'ss' }}s</div>
            </td>
            <td>
              <div class="trade-id">Trade #{{ trade.id }}</div>
              <div class="bot-info">
                <span class="bot-id">Bot #{{ trade.botId }}</span>
                <span class="mode-badge" [attr.data-mode]="trade.mode">{{ trade.mode }}</span>
              </div>
            </td>
            <td>
              <div class="strategy-id">{{ trade.strategyId }}</div>
            </td>
            <td>
              <div class="slug">{{ trade.marketSlug }}</div>
              <div class="question">{{ trade.question }}</div>
            </td>
            <td>
              <span class="side" [class.side-buy]="trade.decisionSide === 'BUY'" [class.side-sell]="trade.decisionSide === 'SELL'">
                {{ trade.decisionSide }}
              </span>
            </td>
            <td>
              <div class="status-badge" [attr.data-status]="trade.status">{{ trade.status }}</div>
            </td>
            <td>
              <div class="amount-main">$ {{ trade.entryFilledUsd | number:'1.2-2' }}</div>
              <div class="amount-sub">of $ {{ trade.intendedAmountUsd | number:'1.2-2' }}</div>
            </td>
            <td [class.positive]="trade.finalPnlUsd > 0" [class.negative]="trade.finalPnlUsd < 0">
              <div class="pnl-value">$ {{ trade.finalPnlUsd | number:'1.2-2' }}</div>
              <div class="pnl-exit" *ngIf="trade.exitFilledUsd">Exit: $ {{ trade.exitFilledUsd | number:'1.2-2' }}</div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  `,
  styles: `
    .header { margin-bottom: 24px; }
    h1 { font-size: 24px; font-weight: 700; }

    .card {
      background-color: #fff;
      border-radius: 8px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.1);
      margin-bottom: 24px;
    }

    .filters-card { padding: 20px; }
    .filters-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 20px; }
    .filter-group { display: flex; flex-direction: column; gap: 6px; }
    .filter-group label { font-size: 12px; font-weight: 600; color: #6b7280; text-transform: uppercase; }
    .filter-group select, .filter-group input { 
      padding: 8px 12px; 
      border: 1px solid #d1d5db; 
      border-radius: 6px; 
      font-size: 14px;
      outline: none;
    }
    .filter-group select:focus, .filter-group input:focus { border-color: #3b82f6; }

    .table-card { overflow: hidden; }
    table { width: 100%; border-collapse: collapse; }
    th {
      text-align: left;
      padding: 12px 16px;
      background-color: #f9fafb;
      border-bottom: 1px solid #e5e7eb;
      font-size: 11px;
      font-weight: 600;
      color: #6b7280;
      text-transform: uppercase;
    }

    td { padding: 12px 16px; border-bottom: 1px solid #f3f4f6; font-size: 14px; vertical-align: top; }

    .clickable-row { cursor: pointer; transition: background-color 0.2s; }
    .clickable-row:hover { background-color: #f9fafb; }

    .time-main { font-weight: 600; }
    .time-sub { font-size: 11px; color: #9ca3af; }

    .trade-id { font-weight: 700; font-size: 14px; }
    .bot-info { display: flex; align-items: center; gap: 6px; margin-top: 2px; }
    .bot-id { font-size: 11px; color: #6b7280; font-weight: 600; }
    .mode-badge { 
      font-size: 10px; 
      font-weight: 800; 
      padding: 1px 4px; 
      border-radius: 4px; 
      display: inline-block;
      background: #f3f4f6;
      color: #374151;
    }
    .mode-badge[data-mode="LIVE"] { background: #fee2e2; color: #991b1b; }
    .mode-badge[data-mode="PAPER"] { background: #d1fae5; color: #065f46; }

    .strategy-id { font-family: monospace; font-size: 12px; color: #4b5563; }

    .slug { font-weight: 600; font-size: 13px; }
    .question { font-size: 12px; color: #6b7280; line-height: 1.2; margin-top: 2px; }

    .side { font-weight: 700; font-size: 11px; }
    .side-buy { color: #10b981; }
    .side-sell { color: #3b82f6; }

    .status-badge {
      font-size: 11px;
      font-weight: 600;
      padding: 2px 8px;
      border-radius: 9999px;
      background: #f3f4f6;
      display: inline-block;
      color: #fff;
    }
    .status-badge[data-status="OPEN"] { background: #f59e0b; }
    .status-badge[data-status="CLOSED"] { background: #3b82f6; }
    .status-badge[data-status="FAILED"] { background: #ef4444; }
    .status-badge[data-status="FILLED"] { background: #10b981; }

    .amount-main { font-weight: 600; }
    .amount-sub { font-size: 11px; color: #6b7280; }

    .pnl-value { font-weight: 700; }
    .pnl-exit { font-size: 11px; color: #6b7280; }
    .positive { color: #10b981; }
    .negative { color: #ef4444; }
  `
})
export class Trades implements OnInit {
  private apiService = inject(ApiService);
  
  trades = signal<TradeSummaryResponse[]>([]);
  options = signal<DashboardOptionsResponse | null>(null);

  filters = {
    status: '',
    mode: '',
    strategyId: '',
    botId: null as number | null,
    limit: 100
  };

  ngOnInit() {
    this.apiService.getDashboardOptions().subscribe(opt => this.options.set(opt));
    this.loadTrades();
  }

  loadTrades() {
    const params: any = {
      limit: this.filters.limit
    };
    if (this.filters.status) params.status = this.filters.status;
    if (this.filters.mode) params.mode = this.filters.mode;
    if (this.filters.strategyId) params.strategyId = this.filters.strategyId;
    if (this.filters.botId) params.botId = this.filters.botId;

    this.apiService.getTrades(params).subscribe(trades => {
      this.trades.set(trades);
    });
  }
}
