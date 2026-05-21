import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { TradeOrderDetailResponse, TradeOrderResponse } from '../../models/api.models';
import { ExecutionEvents } from '../../components/execution-events/execution-events';

@Component({
  selector: 'app-order-detail',
  standalone: true,
  imports: [CommonModule, RouterModule, ExecutionEvents],
  template: `
    <div *ngIf="detail() as d" class="container">
      <div class="header">
        <a [routerLink]="['/trades', d.order.tradeId]" class="back-link">Back to Trade #{{ d.order.tradeId }}</a>
        <div class="header-main">
          <h1>Order #{{ d.order.id }}</h1>
          <div class="badges">
            <div class="status-badge" [attr.data-status]="d.order.status">{{ d.order.status }}</div>
            <div class="phase-badge">{{ d.order.phase }}</div>
            <div class="type-badge">{{ d.order.orderType }}</div>
            <div class="mode-badge" [attr.data-mode]="d.order.mode">{{ d.order.mode }}</div>
          </div>
          <button class="btn btn-secondary" (click)="reconcile()">Reconcile</button>
        </div>
      </div>

      <div class="card status-card">
        <div class="status-grid">
          <div class="status-main">
            <div class="label">Side / Price</div>
            <div class="value large" [class.side-buy]="d.order.side === 'BUY'" [class.side-sell]="d.order.side === 'SELL'">
              {{ d.order.side }} @ {{ d.order.requestedPrice | number:'1.3-3' }}
            </div>
          </div>
          <div class="fill-stats">
            <div class="label">Fill Progress</div>
            <div class="progress-info">
              <div class="value large">{{ d.order.filledShares || 0 | number:'1.2-2' }}</div>
              <div class="total muted">of {{ d.order.requestedShares || 0 | number:'1.2-2' }} shares</div>
            </div>
          </div>
          <div class="execution-economics">
            <div class="eco-item">
              <label>Avg Fill</label>
              <span>{{ d.order.avgFillPrice !== null ? (d.order.avgFillPrice | number:'1.3-3') : '-' }}</span>
            </div>
            <div class="eco-item">
              <label>Amount</label>
              <span>$ {{ d.order.filledAmountUsd || 0 | number:'1.2-2' }}</span>
            </div>
            <div class="eco-item">
              <label>Fee</label>
              <span class="negative">$ {{ d.order.realizedFeeUsd || 0 | number:'1.4-4' }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="warning-banner" *ngIf="d.order.status === 'FAILED' || d.order.status === 'REJECTED'">
        <div class="banner-title">Order Attempt Failed</div>
        <div class="banner-body">
          <p>This order attempt failed: {{ d.order.errorMessage || 'Unknown reason' }}. The parent trade may still be open.</p>
          <p *ngIf="d.order.orderType === 'FAK' || d.order.orderType === 'FOK'">
            FAK/FOK attempts can fail when no matching liquidity is available at the protection price.
          </p>
        </div>
      </div>

      <div class="grid mt-4">
        <div class="card">
          <h2>Identifiers</h2>
          <div class="info-grid">
            <div class="info-item">
              <label>Local ID</label>
              <div class="id-wrap">
                <code class="mono">{{ d.order.localOrderId }}</code>
                <button class="copy-btn" (click)="copyToClipboard(d.order.localOrderId)">Copy</button>
              </div>
            </div>
            <div class="info-item">
              <label>Client ID</label>
              <div class="id-wrap">
                <code class="mono">{{ d.order.clientOrderId }}</code>
                <button class="copy-btn" (click)="copyToClipboard(d.order.clientOrderId)">Copy</button>
              </div>
            </div>
            <div class="info-item" *ngIf="d.order.remoteOrderId">
              <label>Remote ID</label>
              <div class="id-wrap">
                <code class="mono">{{ d.order.remoteOrderId }}</code>
                <button class="copy-btn" (click)="copyToClipboard(d.order.remoteOrderId)">Copy</button>
              </div>
            </div>
            <div class="info-item" *ngIf="d.order.exchangeOrderId">
              <label>Exchange ID</label>
              <div class="id-wrap">
                <code class="mono">{{ d.order.exchangeOrderId }}</code>
                <button class="copy-btn" (click)="copyToClipboard(d.order.exchangeOrderId)">Copy</button>
              </div>
            </div>
            <div class="info-item">
              <label>Market / Token</label>
              <div class="id-wrap">
                <code class="mono" [title]="d.order.tokenId">{{ d.order.marketId }} / {{ d.order.tokenId | slice:0:8 }}...</code>
              </div>
            </div>
          </div>
        </div>

        <div class="card">
          <h2>Timing & Latency</h2>
          <div class="info-grid">
            <div class="info-item"><label>Created</label><span>{{ d.order.createdAt | date:'medium' }}</span></div>
            <div class="info-item"><label>Submitted</label><span>{{ d.order.submittedAt ? (d.order.submittedAt | date:'medium') : '-' }}</span></div>
            <div class="info-item"><label>Acknowledged</label><span>{{ d.order.acknowledgedAt ? (d.order.acknowledgedAt | date:'medium') : '-' }}</span></div>
            <div class="info-item"><label>Completed</label><span>{{ d.order.completedAt ? (d.order.completedAt | date:'medium') : '-' }}</span></div>
            <div class="info-item"><label>Last Reconciled</label><span>{{ d.order.lastReconciledAt ? (d.order.lastReconciledAt | date:'medium') : '-' }}</span></div>
            <div class="info-item"><label>Duration</label><span>{{ getDuration(d.order) }}</span></div>
            <div class="info-item" *ngIf="d.order.latencyMs"><label>Latency</label><span class="mono">{{ d.order.latencyMs }}ms</span></div>
          </div>
        </div>

        <div class="card" *ngIf="d.order.errorMessage || d.order.status === 'REJECTED' || d.order.status === 'FAILED'">
          <h2 class="text-red">Failure Details</h2>
          <div class="info-grid">
            <div class="info-item" *ngIf="d.order.errorMessage"><label>Error</label><span>{{ d.order.errorMessage }}</span></div>
          </div>
        </div>
      </div>

      <div class="card mt-4">
        <div class="section-header">
          <h2>Fills</h2>
          <div class="audit-warning" *ngIf="d.order.status === 'FILLED' && d.fills.length === 0">
            No fill rows imported. Order is marked filled from executor status.
          </div>
        </div>
        <div *ngIf="d.fills.length > 0; else noFills" class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Time</th>
                <th>Price</th>
                <th>Shares</th>
                <th>Amount</th>
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
                <td>$ {{ fill.feeUsd || 0 | number:'1.4-4' }}</td>
                <td><span class="role-badge">{{ fill.liquidityRole }}</span></td>
                <td class="id-cell">{{ fill.remoteFillId || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <ng-template #noFills>
          <div class="empty-state">No fill rows imported for this order.</div>
        </ng-template>
      </div>

      <div class="grid mt-4">
        <details class="card raw-data" *ngIf="d.order.rawRequest">
          <summary>
            <h3>Raw Request</h3>
            <button class="copy-btn-small" (click)="copyToClipboard(d.order.rawRequest); $event.stopPropagation()">Copy</button>
          </summary>
          <pre>{{ formatJson(d.order.rawRequest) }}</pre>
        </details>
        <details class="card raw-data" *ngIf="d.order.rawResponse">
          <summary>
            <h3>Raw Response</h3>
            <button class="copy-btn-small" (click)="copyToClipboard(d.order.rawResponse); $event.stopPropagation()">Copy</button>
          </summary>
          <pre>{{ formatJson(d.order.rawResponse) }}</pre>
        </details>
      </div>

      <div class="card mt-4">
        <h2>Order Events</h2>
        <app-execution-events [events]="d.events"></app-execution-events>
      </div>
    </div>
  `,
  styles: `
    .container { padding: 24px; max-width: 1200px; margin: 0 auto; }
    .header { margin-bottom: 24px; }
    .header-main { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 8px; }
    .badges { display: flex; gap: 8px; flex-wrap: wrap; }
    .back-link { text-decoration: none; color: #3b82f6; font-weight: 500; font-size: 14px; }
    h1 { font-size: 28px; font-weight: 800; margin: 0; color: #111827; }
    h2 { font-size: 18px; font-weight: 700; margin: 0 0 16px; color: #374151; }
    h3 { font-size: 14px; font-weight: 700; margin: 0; color: #4b5563; text-transform: uppercase; }

    .btn { padding: 8px 16px; border-radius: 6px; font-size: 14px; font-weight: 600; cursor: pointer; border: 1px solid transparent; transition: all 0.2s; }
    .btn-secondary { background-color: #f3f4f6; color: #374151; border-color: #d1d5db; }
    .btn-secondary:hover { background-color: #e5e7eb; }

    .card { background: #fff; border-radius: 8px; padding: 24px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); border: 1px solid #f3f4f6; }
    .mt-4 { margin-top: 24px; }
    .status-card { border-top: 4px solid #3b82f6; }
    .status-grid { display: grid; grid-template-columns: 1.5fr 1fr 1.5fr; gap: 32px; align-items: center; }
    .status-main .value.large, .progress-info .value { font-size: 22px; font-weight: 800; }
    .progress-info { display: flex; align-items: baseline; gap: 8px; }
    .execution-economics { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
    .eco-item { display: flex; flex-direction: column; gap: 4px; }
    .eco-item label { font-size: 11px; font-weight: 700; color: #6b7280; text-transform: uppercase; }
    .eco-item span { font-size: 14px; font-weight: 700; }

    .warning-banner { background: #fee2e2; border-left: 4px solid #ef4444; padding: 16px 20px; border-radius: 8px; margin-top: 24px; }
    .banner-title { font-weight: 800; color: #991b1b; margin-bottom: 8px; font-size: 16px; }
    .banner-body { color: #b91c1c; font-size: 14px; line-height: 1.5; }
    .banner-body p { margin: 4px 0; }

    .status-badge, .mode-badge, .phase-badge, .type-badge, .role-badge {
      padding: 2px 10px; border-radius: 9999px; font-size: 11px; font-weight: 700; text-transform: uppercase;
    }
    .status-badge { background: #e5e7eb; color: #374151; }
    .status-badge[data-status="FILLED"] { background: #10b981; color: #fff; }
    .status-badge[data-status="PARTIALLY_FILLED"] { background: #3b82f6; color: #fff; }
    .status-badge[data-status="FAILED"], .status-badge[data-status="REJECTED"] { background: #ef4444; color: #fff; }
    .status-badge[data-status="OPEN"] { background: #f59e0b; color: #fff; }
    .mode-badge[data-mode="LIVE"] { background: #fee2e2; color: #991b1b; }
    .mode-badge[data-mode="PAPER"] { background: #d1fae5; color: #065f46; }
    .phase-badge { background: #f3f4f6; color: #4b5563; }
    .type-badge { background: #e0e7ff; color: #4338ca; }
    .role-badge { background: #f3f4f6; color: #6b7280; }

    .id-wrap { display: flex; align-items: center; gap: 8px; min-width: 0; }
    .mono { font-family: monospace; font-size: 12px; background: #f9fafb; padding: 2px 6px; border-radius: 4px; color: #374151; overflow-wrap: anywhere; word-break: break-all; }
    .copy-btn { padding: 4px 8px; font-size: 11px; background: #fff; border: 1px solid #d1d5db; border-radius: 4px; cursor: pointer; font-weight: 600; color: #4b5563; }
    .copy-btn:hover { background: #f9fafb; }
    .copy-btn-small { padding: 2px 6px; font-size: 10px; background: #fff; border: 1px solid #d1d5db; border-radius: 4px; cursor: pointer; }

    .raw-data summary { cursor: pointer; display: flex; align-items: center; justify-content: space-between; list-style: none; }
    .raw-data summary::-webkit-details-marker { display: none; }
    pre { background: #f9fafb; padding: 16px; border-radius: 8px; font-size: 12px; overflow-x: auto; border: 1px solid #e5e7eb; margin-top: 16px; color: #1f2937; }

    .table-wrap { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; }
    th { text-align: left; padding: 12px 16px; background: #f9fafb; border-bottom: 2px solid #e5e7eb; font-size: 11px; color: #6b7280; text-transform: uppercase; }
    td { padding: 12px 16px; border-bottom: 1px solid #f3f4f6; font-size: 14px; }
    .audit-warning { background: #fef3c7; color: #92400e; padding: 4px 12px; border-radius: 9999px; font-size: 12px; font-weight: 600; display: flex; align-items: center; gap: 8px; }
    .empty-state { padding: 24px; text-align: center; color: #9ca3af; font-style: italic; font-size: 14px; }

    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
    .info-grid { display: grid; gap: 12px; }
    .info-item { display: flex; justify-content: space-between; align-items: center; gap: 12px; font-size: 14px; }
    .info-item label { color: #6b7280; font-weight: 500; flex-shrink: 0; }
    .info-item span { font-weight: 600; color: #111827; text-align: right; overflow-wrap: anywhere; }
    .id-cell { font-family: monospace; font-size: 12px; color: #6b7280; }
    .label { font-size: 12px; font-weight: 600; color: #6b7280; text-transform: uppercase; margin-bottom: 4px; }
    .negative { color: #ef4444; }
    .side-buy { color: #10b981; }
    .side-sell { color: #3b82f6; }
    .muted { color: #9ca3af; font-size: 12px; }
    .text-red { color: #ef4444; }
    .text-center { text-align: center; color: #9ca3af; }
    .p-4 { padding: 16px; }
    .section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; gap: 16px; }

    @media (max-width: 768px) {
      .grid, .status-grid { grid-template-columns: 1fr; }
      .execution-economics { grid-template-columns: 1fr 1fr; }
      .header-main { align-items: flex-start; flex-direction: column; }
    }
  `
})
export class OrderDetail implements OnInit {
  private apiService = inject(ApiService);
  private route = inject(ActivatedRoute);
  detail = signal<TradeOrderDetailResponse | null>(null);

  ngOnInit() {
    this.loadDetail();
  }

  loadDetail() {
    this.route.params.subscribe(params => {
      const id = +params['id'];
      if (id) {
        this.apiService.getOrder(id).subscribe(detail => {
          this.detail.set(detail);
        });
      }
    });
  }

  reconcile() {
    const d = this.detail();
    if (d) {
      this.apiService.reconcileOrder(d.order.id).subscribe({
        next: () => {
          this.loadDetail();
        },
        error: (err) => alert('Reconciliation failed: ' + (err.error?.message || err.message))
      });
    }
  }

  getDuration(order: TradeOrderResponse) {
    if (!order.createdAt || !order.completedAt) return '-';
    const start = new Date(order.createdAt).getTime();
    const end = new Date(order.completedAt).getTime();
    const diff = end - start;
    if (diff < 1000) return `${diff}ms`;
    return `${(diff / 1000).toFixed(2)}s`;
  }

  formatJson(json: string | null) {
    if (!json) return '';
    try {
      return JSON.stringify(JSON.parse(json), null, 2);
    } catch {
      return json;
    }
  }

  copyToClipboard(text: string | null) {
    if (!text) return;
    navigator.clipboard.writeText(text);
  }
}
