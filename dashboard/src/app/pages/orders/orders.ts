import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { TradeOrderResponse, DashboardOptionsResponse } from '../../models/api.models';

@Component({
  selector: 'app-orders',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  template: `
    <div class="header">
      <h1>Order History</h1>
    </div>

    <div class="card filters-card">
      <div class="filters-grid">
        <div class="filter-group">
          <label>Status</label>
          <select [(ngModel)]="filters.status" (change)="loadOrders()">
            <option value="">All Statuses</option>
            <option value="PENDING">PENDING</option>
            <option value="SUBMITTED">SUBMITTED</option>
            <option value="ACKNOWLEDGED">ACKNOWLEDGED</option>
            <option value="PARTIALLY_FILLED">PARTIALLY_FILLED</option>
            <option value="FILLED">FILLED</option>
            <option value="CANCELLED">CANCELLED</option>
            <option value="REJECTED">REJECTED</option>
            <option value="FAILED">FAILED</option>
          </select>
        </div>

        <div class="filter-group">
          <label>Mode</label>
          <select [(ngModel)]="filters.mode" (change)="loadOrders()">
            <option value="">All Modes</option>
            <option *ngFor="let m of options()?.executionModes" [value]="m">{{ m }}</option>
          </select>
        </div>

        <div class="filter-group">
          <label>Side</label>
          <select [(ngModel)]="filters.side" (change)="loadOrders()">
            <option value="">All Sides</option>
            <option value="BUY">BUY</option>
            <option value="SELL">SELL</option>
          </select>
        </div>

        <div class="filter-group">
          <label>Type</label>
          <select [(ngModel)]="filters.orderType" (change)="loadOrders()">
            <option value="">All Types</option>
            <option value="GTD">GTD (Good Til Guard)</option>
            <option value="FAK">FAK (Fill And Kill)</option>
            <option value="FOK">FOK (Fill Or Kill)</option>
            <option value="GTC">GTC (Good Til Cancelled)</option>
            <option value="SIMULATED">SIMULATED</option>
          </select>
        </div>

        <div class="filter-group">
          <label>Bot ID</label>
          <input type="number" [(ngModel)]="filters.botId" (input)="loadOrders()" placeholder="Any ID">
        </div>

        <div class="filter-group">
          <label>Limit</label>
          <select [(ngModel)]="filters.limit" (change)="loadOrders()">
            <option [value]="50">50</option>
            <option [value]="100">100</option>
            <option [value]="200">200</option>
            <option [value]="500">500</option>
          </select>
        </div>
      </div>
    </div>

    <div class="card table-card">
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>ID / Bot</th>
            <th>Market / Token</th>
            <th>Side / Type</th>
            <th>Status</th>
            <th>Last Recon</th>
            <th>Price</th>
            <th>Filled / Req</th>
            <th>Amount USD</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let order of orders()" [routerLink]="['/orders', order.id]" class="clickable-row">
            <td>
              <div class="time-main">{{ order.createdAt | date:'MMM d, HH:mm' }}</div>
              <div class="time-sub">{{ order.createdAt | date:'ss' }}s</div>
            </td>
            <td>
              <div class="order-id">#{{ order.id }}</div>
              <div class="bot-id">Bot #{{ order.botId }}</div>
              <div class="mode-badge" [attr.data-mode]="order.mode">{{ order.mode }}</div>
            </td>
            <td>
              <div class="market-id">{{ order.marketId }}</div>
              <div class="token-id">{{ order.tokenId }}</div>
            </td>
            <td>
              <span class="side" [class.side-buy]="order.side === 'BUY'" [class.side-sell]="order.side === 'SELL'">
                {{ order.side }}
              </span>
              <div class="order-type">{{ order.orderType }}</div>
            </td>
            <td>
              <div class="status-badge" [attr.data-status]="order.status">{{ order.status }}</div>
            </td>
            <td>
              <div *ngIf="order.lastReconciledAt" class="time-main">{{ order.lastReconciledAt | date:'HH:mm:ss' }}</div>
              <div *ngIf="!order.lastReconciledAt" class="time-sub">N/A</div>
            </td>
            <td>
              <div class="price-main">{{ order.avgFillPrice || order.requestedPrice | number:'1.3-3' }}</div>
              <div class="price-req" *ngIf="order.avgFillPrice">Req: {{ order.requestedPrice | number:'1.3-3' }}</div>
            </td>
            <td>
              <div class="shares-main">{{ order.filledShares | number:'1.2-2' }}</div>
              <div class="shares-sub">of {{ order.requestedShares | number:'1.2-2' }}</div>
            </td>
            <td>
              <div class="amount-main">$ {{ (order.filledAmountUsd || 0) | number:'1.2-2' }}</div>
              <div class="amount-sub" *ngIf="order.realizedFeeUsd">Fee: $ {{ order.realizedFeeUsd | number:'1.2-2' }}</div>
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

    .order-id { font-weight: 700; }
    .bot-id { font-size: 11px; color: #6b7280; }
    .mode-badge { 
      font-size: 9px; 
      font-weight: 800; 
      padding: 0px 3px; 
      border-radius: 3px; 
      display: inline-block;
      background: #f3f4f6;
      color: #374151;
      text-transform: uppercase;
    }
    .mode-badge[data-mode="LIVE"] { background: #fee2e2; color: #991b1b; }
    .mode-badge[data-mode="PAPER"] { background: #d1fae5; color: #065f46; }

    .market-id { font-weight: 600; font-size: 12px; }
    .token-id { font-size: 11px; color: #6b7280; font-family: monospace; }

    .side { font-weight: 700; font-size: 11px; }
    .side-buy { color: #10b981; }
    .side-sell { color: #3b82f6; }
    .order-type { font-size: 11px; color: #6b7280; }

    .status-badge {
      font-size: 11px;
      font-weight: 600;
      padding: 2px 8px;
      border-radius: 9999px;
      background: #f3f4f6;
      display: inline-block;
    }
    .status-badge[data-status="FILLED"] { background: #d1fae5; color: #065f46; }
    .status-badge[data-status="PARTIALLY_FILLED"] { background: #dbeafe; color: #1e40af; }
    .status-badge[data-status="CANCELLED"] { background: #f3f4f6; color: #6b7280; }
    .status-badge[data-status="FAILED"], .status-badge[data-status="REJECTED"] { background: #fee2e2; color: #991b1b; }

    .price-main { font-weight: 600; }
    .price-req { font-size: 11px; color: #9ca3af; }

    .shares-main { font-weight: 600; }
    .shares-sub { font-size: 11px; color: #6b7280; }

    .amount-main { font-weight: 600; }
    .amount-sub { font-size: 11px; color: #6b7280; }
  `
})
export class Orders implements OnInit {
  private apiService = inject(ApiService);
  
  orders = signal<TradeOrderResponse[]>([]);
  options = signal<DashboardOptionsResponse | null>(null);

  filters = {
    status: '',
    mode: '',
    side: '',
    orderType: '',
    botId: null as number | null,
    limit: 100
  };

  ngOnInit() {
    this.apiService.getDashboardOptions().subscribe(opt => this.options.set(opt));
    this.loadOrders();
  }

  loadOrders() {
    const params: any = {
      limit: this.filters.limit
    };
    if (this.filters.status) params.status = this.filters.status;
    if (this.filters.mode) params.mode = this.filters.mode;
    if (this.filters.side) params.side = this.filters.side;
    if (this.filters.orderType) params.orderType = this.filters.orderType;
    if (this.filters.botId) params.botId = this.filters.botId;

    this.apiService.getOrders(params).subscribe(orders => {
      this.orders.set(orders);
    });
  }
}
