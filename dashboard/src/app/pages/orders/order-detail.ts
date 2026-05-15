import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { TradeOrderDetailResponse } from '../../models/api.models';

@Component({
  selector: 'app-order-detail',
  standalone: true,
  imports: [CommonModule, RouterModule],
  template: `
    <div *ngIf="detail() as d" class="container">
      <div class="header">
        <a [routerLink]="['/trades', d.order.tradeId]" class="back-link">← Back to Trade #{{ d.order.tradeId }}</a>
        <h1>Order Details #{{ d.order.id }}</h1>
        <div class="status-badge" [attr.data-status]="d.order.status">{{ d.order.status }}</div>
      </div>

      <div class="grid">
        <!-- Basic Info -->
        <div class="card">
          <h2>Summary</h2>
          <div class="info-grid">
            <div class="info-item"><label>Market</label><span>{{ d.order.marketId }}</span></div>
            <div class="info-item"><label>Token</label><span>{{ d.order.tokenId }}</span></div>
            <div class="info-item"><label>Outcome</label><span>{{ d.order.outcome }}</span></div>
            <div class="info-item"><label>Side</label><span [class.side-buy]="d.order.side === 'BUY'" [class.side-sell]="d.order.side === 'SELL'">{{ d.order.side }}</span></div>
            <div class="info-item"><label>Phase</label><span>{{ d.order.phase }}</span></div>
            <div class="info-item"><label>Type</label><span>{{ d.order.orderType }}</span></div>
            <div class="info-item"><label>Mode</label><span>{{ d.order.mode }}</span></div>
            <div class="info-item"><label>Venue</label><span>{{ d.order.venue }}</span></div>
          </div>
        </div>

        <!-- IDs & Timestamps -->
        <div class="card">
          <h2>Identifiers & Time</h2>
          <div class="info-grid">
            <div class="info-item"><label>Local ID</label><span>{{ d.order.localOrderId }}</span></div>
            <div class="info-item"><label>Client ID</label><span>{{ d.order.clientOrderId }}</span></div>
            <div class="info-item"><label>Remote ID</label><span>{{ d.order.remoteOrderId || 'N/A' }}</span></div>
            <div class="info-item"><label>Exchange ID</label><span>{{ d.order.exchangeOrderId || 'N/A' }}</span></div>
            <div class="info-item"><label>Created At</label><span>{{ d.order.createdAt | date:'medium' }}</span></div>
            <div class="info-item"><label>Submitted</label><span>{{ d.order.submittedAt | date:'medium' }}</span></div>
            <div class="info-item"><label>Completed</label><span>{{ d.order.completedAt | date:'medium' }}</span></div>
            <div class="info-item"><label>Last Reconciled</label><span>{{ d.order.lastReconciledAt | date:'medium' }}</span></div>
            <div class="info-item"><label>Latency</label><span>{{ d.order.latencyMs }} ms</span></div>
          </div>
        </div>

        <!-- Execution -->
        <div class="card">
          <h2>Execution</h2>
          <div class="info-grid">
            <div class="info-item"><label>Requested Price</label><span>{{ d.order.requestedPrice | number:'1.3-3' }}</span></div>
            <div class="info-item"><label>Requested Shares</label><span>{{ d.order.requestedShares | number:'1.2-2' }}</span></div>
            <div class="info-item"><label>Filled Shares</label><span>{{ d.order.filledShares | number:'1.2-2' }}</span></div>
            <div class="info-item"><label>Avg Fill Price</label><span>{{ d.order.avgFillPrice | number:'1.3-3' }}</span></div>
            <div class="info-item"><label>Filled Amount</label><span>$ {{ d.order.filledAmountUsd | number:'1.2-2' }}</span></div>
            <div class="info-item"><label>Fee</label><span>$ {{ d.order.realizedFeeUsd | number:'1.4-4' }}</span></div>
          </div>
        </div>

        <!-- Error/Rejection -->
        <div class="card" *ngIf="d.order.errorMessage || d.order.status === 'REJECTED' || d.order.status === 'FAILED'">
          <h2 class="text-red">Failure Details</h2>
          <div class="info-grid">
            <div class="info-item" *ngIf="d.order.errorMessage"><label>Error</label><span>{{ d.order.errorMessage }}</span></div>
          </div>
        </div>
      </div>

      <!-- Raw Data -->
      <div class="card mt-4">
        <h2>Raw Communication</h2>
        <div class="raw-grid">
          <div>
            <h3>Raw Request</h3>
            <pre class="raw-data">{{ d.order.rawRequest || 'No request data' }}</pre>
          </div>
          <div>
            <h3>Raw Response</h3>
            <pre class="raw-data">{{ d.order.rawResponse || 'No response data' }}</pre>
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
              <th>Price</th>
              <th>Shares</th>
              <th>Amount USD</th>
              <th>Fee</th>
              <th>Role</th>
              <th>Remote ID</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let fill of d.fills">
              <td>{{ fill.filledAt | date:'medium' }}</td>
              <td>{{ fill.price | number:'1.3-3' }}</td>
              <td>{{ fill.shares | number:'1.2-2' }}</td>
              <td>$ {{ fill.amountUsd | number:'1.2-2' }}</td>
              <td>$ {{ fill.feeUsd | number:'1.4-4' }}</td>
              <td>{{ fill.liquidityRole }}</td>
              <td class="id-cell">{{ fill.remoteFillId }}</td>
            </tr>
            <tr *ngIf="d.fills.length === 0">
              <td colspan="7" class="text-center">No fills recorded for this order.</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Events -->
      <div class="card mt-4">
        <h2>Order Events</h2>
        <div class="events-list">
          <div *ngFor="let event of d.events" class="event-item">
            <span class="event-time">{{ event.createdAt | date:'mediumTime' }}</span>
            <span class="event-type">{{ event.eventType }}</span>
            <span class="event-message">{{ event.message }}</span>
          </div>
          <div *ngIf="d.events.length === 0" class="text-center p-4">No events recorded for this order.</div>
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
    .status-badge[data-status="FILLED"] { background: #d1fae5; color: #065f46; }
    .status-badge[data-status="PARTIALLY_FILLED"] { background: #dbeafe; color: #1e40af; }
    .status-badge[data-status="CANCELLED"] { background: #f3f4f6; color: #6b7280; }
    .status-badge[data-status="FAILED"], .status-badge[data-status="REJECTED"] { background: #fee2e2; color: #991b1b; }

    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(350px, 1fr)); gap: 24px; }
    
    .card {
      background: #fff;
      border-radius: 8px;
      padding: 20px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.1);
    }
    .mt-4 { margin-top: 24px; }
    h2 { font-size: 18px; font-weight: 600; margin-top: 0; margin-bottom: 16px; color: #374151; }
    .text-red { color: #ef4444; }

    .info-grid { display: grid; grid-template-columns: 1fr; gap: 8px; }
    .info-item { 
      display: flex; 
      justify-content: space-between; 
      font-size: 14px; 
      gap: 12px;
      padding: 4px 0;
      border-bottom: 1px solid #f9fafb;
      align-items: flex-start;
    }
    .info-item label { color: #6b7280; flex-shrink: 0; font-weight: 500; }
    .info-item span { 
      font-weight: 500; 
      text-align: right; 
      word-break: break-all; 
      font-family: monospace;
      font-size: 13px;
    }

    table { width: 100%; border-collapse: collapse; }
    th { text-align: left; padding: 12px 8px; border-bottom: 2px solid #f3f4f6; color: #6b7280; font-size: 12px; text-transform: uppercase; }
    td { padding: 12px 8px; border-bottom: 1px solid #f3f4f6; font-size: 14px; }
    .id-cell { font-family: monospace; font-size: 12px; color: #6b7280; }
    .text-center { text-align: center; color: #9ca3af; padding: 16px; }

    .side-buy { color: #10b981; font-weight: 600; }
    .side-sell { color: #3b82f6; font-weight: 600; }

    .events-list { display: flex; flex-direction: column; gap: 4px; }
    .event-item { display: flex; gap: 12px; font-size: 13px; padding: 4px 0; border-bottom: 1px solid #f9fafb; }
    .event-time { color: #9ca3af; white-space: nowrap; }
    .event-type { font-weight: 600; color: #4b5563; min-width: 120px; }
    .event-message { color: #374151; }

    .raw-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 24px; }
    .raw-grid h3 { font-size: 14px; font-weight: 600; color: #6b7280; margin-bottom: 8px; }
    .raw-data {
      background: #f9fafb;
      border: 1px solid #e5e7eb;
      border-radius: 4px;
      padding: 12px;
      font-size: 11px;
      font-family: monospace;
      white-space: pre-wrap;
      word-break: break-all;
      max-height: 300px;
      overflow-y: auto;
      margin: 0;
      color: #374151;
    }
  `
})
export class OrderDetail implements OnInit {
  private apiService = inject(ApiService);
  private route = inject(ActivatedRoute);
  detail = signal<TradeOrderDetailResponse | null>(null);

  ngOnInit() {
    this.route.params.subscribe(params => {
      const id = +params['id'];
      if (id) {
        this.apiService.getOrder(id).subscribe(detail => {
          this.detail.set(detail);
        });
      }
    });
  }
}
