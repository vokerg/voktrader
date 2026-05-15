import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { TradeDetailResponse } from '../../models/api.models';

@Component({
  selector: 'app-trade-detail',
  standalone: true,
  imports: [CommonModule, RouterModule],
  template: `
    <div *ngIf="trade() as t" class="container">
      <div class="header">
        <a routerLink="/trades" class="back-link">← Back to Trades</a>
        <h1>Trade Details #{{ t.id }}</h1>
        <div class="status-badge" [attr.data-status]="t.status">{{ t.status }}</div>
      </div>

      <div class="grid">
        <!-- Basic Info -->
        <div class="card">
          <h2>Summary</h2>
          <div class="info-grid">
            <div class="info-item"><label>Market</label><span>{{ t.marketSlug }}</span></div>
            <div class="info-item"><label>Question</label><span>{{ t.question }}</span></div>
            <div class="info-item"><label>Outcome</label><span>{{ t.outcome }}</span></div>
            <div class="info-item"><label>Side</label><span [class.side-buy]="t.decisionSide === 'BUY'" [class.side-sell]="t.decisionSide === 'SELL'">{{ t.decisionSide }}</span></div>
            <div class="info-item"><label>Status</label><span>{{ t.status }}</span></div>
            <div class="info-item"><label>Bot ID</label><span>#{{ t.botId }}</span></div>
            <div class="info-item"><label>Strategy</label><span>{{ t.strategyId }}</span></div>
            <div class="info-item"><label>Mode</label><span>{{ t.mode }}</span></div>
          </div>
        </div>

        <!-- PnL & Economics -->
        <div class="card">
          <h2>Economics</h2>
          <div class="info-grid">
            <div class="info-item"><label>Intended USD</label><span>$ {{ t.intendedAmountUsd | number:'1.2-2' }}</span></div>
            <div class="info-item"><label>Entry USD</label><span>$ {{ t.entryFilledUsd | number:'1.2-2' }}</span></div>
            <div class="info-item"><label>Exit USD</label><span>$ {{ t.exitFilledUsd | number:'1.2-2' }}</span></div>
            <div class="info-item"><label>Entry Fee</label><span>$ {{ t.entryFeeUsd | number:'1.2-2' }}</span></div>
            <div class="info-item"><label>Exit Fee</label><span>$ {{ t.exitFeeUsd | number:'1.2-2' }}</span></div>
            <div class="info-item"><label>Total Fee</label><span>$ {{ t.totalFeeUsd | number:'1.2-2' }}</span></div>
            <div class="info-item"><label>Final PnL</label><span [class.positive]="t.finalPnlUsd > 0" [class.negative]="t.finalPnlUsd < 0">$ {{ t.finalPnlUsd | number:'1.2-2' }}</span></div>
            <div class="info-item"><label>Entry Price</label><span>{{ t.entryAvgPrice | number:'1.3-3' }}</span></div>
            <div class="info-item"><label>Exit Price</label><span>{{ t.exitAvgPrice | number:'1.3-3' }}</span></div>
          </div>
        </div>
      </div>

      <!-- Fills -->
      <div class="card mt-4">
        <h2>Fills</h2>
        <table>
          <thead>
            <tr>
              <th>Time</th>
              <th>Side</th>
              <th>Price</th>
              <th>Shares</th>
              <th>Amount USD</th>
              <th>Fee</th>
              <th>Venue</th>
              <th>Order</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let fill of t.fills" [routerLink]="fill.orderId ? ['/orders', fill.orderId] : null" [class.clickable-row]="fill.orderId">
              <td>{{ fill.filledAt | date:'medium' }}</td>
              <td><span class="side" [class.side-buy]="fill.side === 'BUY'" [class.side-sell]="fill.side === 'SELL'">{{ fill.side }}</span></td>
              <td>{{ fill.price | number:'1.3-3' }}</td>
              <td>{{ fill.shares | number:'1.2-2' }}</td>
              <td>$ {{ fill.amountUsd | number:'1.2-2' }}</td>
              <td>$ {{ fill.feeUsd | number:'1.4-4' }}</td>
              <td>{{ fill.venue }}</td>
              <td>{{ fill.orderId ? '#' + fill.orderId : 'N/A' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Orders -->
      <div class="card mt-4">
        <h2>Orders</h2>
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>Created At</th>
              <th>Side</th>
              <th>Status</th>
              <th>Req Price</th>
              <th>Req Shares</th>
              <th>Filled Shares</th>
              <th>Venue</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let order of t.orders" [routerLink]="['/orders', order.id]" class="clickable-row">
              <td>#{{ order.id }}</td>
              <td>{{ order.createdAt | date:'medium' }}</td>
              <td><span class="side" [class.side-buy]="order.side === 'BUY'" [class.side-sell]="order.side === 'SELL'">{{ order.side }}</span> ({{ order.phase }})</td>
              <td><div class="status-badge" [attr.data-status]="order.status">{{ order.status }}</div></td>
              <td>{{ order.requestedPrice | number:'1.3-3' }}</td>
              <td>{{ order.requestedShares | number:'1.2-2' }}</td>
              <td>{{ order.filledShares | number:'1.2-2' }}</td>
              <td>{{ order.venue }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Events -->
      <div class="card mt-4">
        <h2>Events</h2>
        <div class="events-list">
          <div *ngFor="let event of t.events" class="event-item">
            <span class="event-time">{{ event.createdAt | date:'mediumTime' }}</span>
            <span class="event-type">{{ event.eventType }}</span>
            <span class="event-message">{{ event.message }}</span>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: `
    .container { padding: 24px; }
    .header { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; }
    .back-link { text-decoration: none; color: #3b82f6; font-weight: 500; }
    h1 { font-size: 24px; font-weight: 700; margin: 0; }
    
    .status-badge {
      padding: 4px 12px;
      border-radius: 9999px;
      font-size: 12px;
      font-weight: 600;
      text-transform: uppercase;
      background: #e5e7eb;
    }
    .status-badge[data-status="OPEN"] { background: #d1fae5; color: #065f46; }
    .status-badge[data-status="CLOSED"] { background: #dbeafe; color: #1e40af; }
    .status-badge[data-status="RESOLVED"] { background: #fef3c7; color: #92400e; }
    
    .status-badge[data-status="FILLED"] { background: #d1fae5; color: #065f46; }
    .status-badge[data-status="PARTIALLY_FILLED"] { background: #dbeafe; color: #1e40af; }
    .status-badge[data-status="FAILED"], .status-badge[data-status="REJECTED"] { background: #fee2e2; color: #991b1b; }

    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
    
    .card {
      background: #fff;
      border-radius: 8px;
      padding: 20px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.1);
    }
    .mt-4 { margin-top: 24px; }
    h2 { font-size: 18px; font-weight: 600; margin-top: 0; margin-bottom: 16px; color: #374151; }

    .info-grid { display: grid; grid-template-columns: 1fr; gap: 8px; }
    .info-item { display: flex; justify-content: space-between; font-size: 14px; }
    .info-item label { color: #6b7280; }
    .info-item span { font-weight: 500; }

    table { width: 100%; border-collapse: collapse; }
    th { text-align: left; padding: 12px 8px; border-bottom: 2px solid #f3f4f6; color: #6b7280; font-size: 12px; text-transform: uppercase; }
    td { padding: 12px 8px; border-bottom: 1px solid #f3f4f6; font-size: 14px; }

    .clickable-row { cursor: pointer; transition: background-color 0.2s; }
    .clickable-row:hover { background-color: #f9fafb; }

    .side { font-weight: 700; }
    .side-buy { color: #10b981; }
    .side-sell { color: #3b82f6; }
    .positive { color: #10b981; font-weight: 600; }
    .negative { color: #ef4444; font-weight: 600; }

    .events-list { display: flex; flex-direction: column; gap: 4px; }
    .event-item { display: flex; gap: 12px; font-size: 13px; padding: 4px 0; border-bottom: 1px solid #f9fafb; }
    .event-time { color: #9ca3af; white-space: nowrap; }
    .event-type { font-weight: 600; color: #4b5563; min-width: 120px; }
    .event-message { color: #374151; }
  `
})
export class TradeDetail implements OnInit {
  private apiService = inject(ApiService);
  private route = inject(ActivatedRoute);
  trade = signal<TradeDetailResponse | null>(null);

  ngOnInit() {
    this.route.params.subscribe(params => {
      const id = +params['id'];
      if (id) {
        this.apiService.getTrade(id).subscribe(trade => {
          this.trade.set(trade);
        });
      }
    });
  }
}
