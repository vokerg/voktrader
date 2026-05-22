import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { TradeDetailResponse, TradeEventResponse } from '../../models/api.models';
import { ExecutionEvents } from '../../components/execution-events/execution-events';

@Component({
  selector: 'app-trade-detail',
  standalone: true,
  imports: [CommonModule, RouterModule, ExecutionEvents],
  template: `
    <div *ngIf="trade() as t" class="container">
      <div class="header">
        <a routerLink="/trades" class="back-link">Back to Trades</a>
        <div class="header-main">
          <h1>Trade Details #{{ t.id }}</h1>
          <div class="market-info">
            <a [routerLink]="['/markets', t.marketId]" class="market-link">{{ t.marketSlug }}</a>
          </div>
          <div class="badges">
            <div class="status-badge" [attr.data-status]="t.status">{{ t.status }}</div>
            <div class="mode-badge" [attr.data-mode]="t.mode">{{ t.mode }}</div>
            <div class="strategy-badge">{{ t.strategyId }}</div>
            <div class="bot-badge">Bot #{{ t.botId }}</div>
          </div>
        </div>
      </div>

      <div class="card outcome-card">
        <div class="outcome-grid">
          <div class="outcome-main">
            <div class="label">Outcome</div>
            <div class="value large">{{ t.outcome }}</div>
            <div class="trade-action">
              Bought {{ t.outcome }} @ {{ t.entryAvgPrice !== null ? (t.entryAvgPrice | number:'1.3-3') : '-' }}
            </div>
          </div>
          <div class="pnl-section">
            <div class="label">Final PnL</div>
            <div class="value pnl" [class.positive]="t.finalPnlUsd > 0" [class.negative]="t.finalPnlUsd < 0">
              $ {{ t.finalPnlUsd | number:'1.2-2' }}
              <span class="net-return" *ngIf="t.entryFilledUsd">
                ({{ (t.finalPnlUsd / t.entryFilledUsd) | percent:'1.2-2' }})
              </span>
            </div>
          </div>
          <div class="economics-grid">
            <div class="eco-item">
              <label>Entry</label>
              <span>{{ t.entryAvgPrice !== null ? (t.entryAvgPrice | number:'1.3-3') : '-' }} <small class="muted">avg</small></span>
              <span class="usd">$ {{ t.entryFilledUsd || 0 | number:'1.2-2' }}</span>
            </div>
            <div class="eco-item">
              <label>Exit</label>
              <span>{{ t.exitAvgPrice !== null ? (t.exitAvgPrice | number:'1.3-3') : '-' }} <small class="muted">avg</small></span>
              <span class="usd">$ {{ t.exitFilledUsd || 0 | number:'1.2-2' }}</span>
            </div>
            <div class="eco-item">
              <label>Fees</label>
              <span class="usd negative">$ {{ t.totalFeeUsd || 0 | number:'1.2-4' }}</span>
            </div>
          </div>
        </div>
        <div class="round-trip-strip">
          <div class="round-trip-item primary">
            <label>Entry</label>
            <span>BUY {{ t.outcome }} @ {{ t.entryAvgPrice !== null ? (t.entryAvgPrice | number:'1.3-3') : '-' }}</span>
            <small>{{ t.entryFilledShares || 0 | number:'1.2-2' }} shares / $ {{ t.entryFilledUsd || 0 | number:'1.2-2' }}</small>
          </div>
          <div class="round-trip-item">
            <label>Exit</label>
            <span>SELL {{ t.outcome }} @ {{ t.exitAvgPrice !== null ? (t.exitAvgPrice | number:'1.3-3') : '-' }}</span>
            <small>{{ t.exitFilledShares || 0 | number:'1.2-2' }} shares / $ {{ t.exitFilledUsd || 0 | number:'1.2-2' }}</small>
          </div>
          <div class="round-trip-item">
            <label>Net</label>
            <span [class.positive]="t.finalPnlUsd > 0" [class.negative]="t.finalPnlUsd < 0">$ {{ t.finalPnlUsd | number:'1.2-2' }}</span>
            <small>Fees $ {{ t.totalFeeUsd || 0 | number:'1.2-4' }}</small>
          </div>
        </div>
      </div>

      <div class="card mt-4">
        <h2>Lifecycle Timeline</h2>
        <div class="timeline-horiz">
          <div class="timeline-step completed">
            <div class="step-icon">Decision</div>
            <div class="step-time">{{ t.decisionAt | date:'HH:mm:ss' }}</div>
          </div>
          <div class="timeline-connector"></div>
          <div class="timeline-step" [class.completed]="hasEntryOrder(t)">
            <div class="step-icon">Entry Routed</div>
            <div class="step-time" *ngIf="entryOrder(t) as o">{{ (o.submittedAt || o.createdAt) | date:'HH:mm:ss' }}</div>
          </div>
          <div class="timeline-connector"></div>
          <div class="timeline-step" [class.completed]="(t.entryFilledShares || 0) > 0">
            <div class="step-icon">Entry Filled</div>
            <div class="step-time" *ngIf="t.entryCompletedAt">{{ t.entryCompletedAt | date:'HH:mm:ss' }}</div>
          </div>
          <div class="timeline-connector" *ngIf="hasFailedExit(t)"></div>
          <div class="timeline-step failed" *ngIf="hasFailedExit(t)">
            <div class="step-icon">Exit Failed</div>
            <div class="step-time">{{ firstFailedExit(t)?.completedAt | date:'HH:mm:ss' }}</div>
            <div class="step-hint">Liquidity check failed</div>
          </div>
          <div class="timeline-connector"></div>
          <div class="timeline-step" [class.completed]="t.status === 'CLOSED' || t.status === 'RESOLVED' || (t.exitFilledShares || 0) > 0">
            <div class="step-icon">Exit / Closed</div>
            <div class="step-time" *ngIf="t.exitCompletedAt || t.resolvedAt">{{ (t.exitCompletedAt || t.resolvedAt) | date:'HH:mm:ss' }}</div>
          </div>
        </div>
      </div>

      <details class="card mt-4 decision-snapshot">
        <summary>
          <h2>Decision Snapshot</h2>
          <span class="muted">View details of the strategy decision</span>
        </summary>
        <div class="grid mt-2">
          <div class="info-grid">
            <div class="info-item"><label>Rule ID</label><span>{{ t.ruleId || '-' }}</span></div>
            <div class="info-item"><label>Condition ID</label><span>{{ t.conditionId || '-' }}</span></div>
            <div class="info-item"><label>Decision At</label><span>{{ t.decisionAt | date:'medium' }}</span></div>
            <div class="info-item"><label>Reason</label><span class="reason-text">{{ t.decisionReason }}</span></div>
            <div class="info-item"><label>Expiry At Decision</label><span>{{ t.secondsToExpiryAtDecision !== null ? (t.secondsToExpiryAtDecision | number) + 's' : '-' }}</span></div>
          </div>
          <div class="info-grid">
            <div class="info-item"><label>Observed Bid</label><span>{{ t.observedBid !== null ? (t.observedBid | number:'1.3-3') : '-' }}</span></div>
            <div class="info-item"><label>Observed Ask</label><span>{{ t.observedAsk !== null ? (t.observedAsk | number:'1.3-3') : '-' }}</span></div>
            <div class="info-item"><label>Observed Mid</label><span>{{ t.observedMidpoint !== null ? (t.observedMidpoint | number:'1.3-3') : '-' }}</span></div>
            <div class="info-item"><label>Observed Spread</label><span>{{ t.observedSpread !== null ? (t.observedSpread | number:'1.4-4') : '-' }}</span></div>
            <div class="info-item"><label>Price Age</label><span>{{ t.priceAgeMs !== null ? (t.priceAgeMs | number) + 'ms' : '-' }}</span></div>
          </div>
          <div class="info-grid">
            <div class="info-item"><label>Intended USD</label><span>$ {{ t.intendedAmountUsd | number:'1.2-2' }}</span></div>
            <div class="info-item"><label>Intended Shares</label><span>{{ t.intendedShares !== null ? (t.intendedShares | number:'1.2-2') : '-' }}</span></div>
            <div class="info-item"><label>Intended Entry</label><span>{{ t.intendedEntryPrice !== null ? (t.intendedEntryPrice | number:'1.3-3') : '-' }}</span></div>
            <div class="info-item"><label>Intended Exit</label><span>{{ t.intendedExitPrice !== null ? (t.intendedExitPrice | number:'1.3-3') : '-' }}</span></div>
            <div class="info-item"><label>Entry Fee</label><span>$ {{ t.entryFeeUsd || 0 | number:'1.2-2' }}</span></div>
            <div class="info-item"><label>Exit Fee</label><span>$ {{ t.exitFeeUsd || 0 | number:'1.2-2' }}</span></div>
          </div>
        </div>
      </details>

      <div class="card mt-4" *ngIf="exitAttempts(t).length > 1">
        <h2>Exit Attempts</h2>
        <div class="attempts-list">
          <div *ngFor="let attempt of exitAttempts(t); let i = index" class="attempt-item" [class.attempt-failed]="attempt.status !== 'FILLED'">
            <div class="attempt-header">
              <span class="attempt-num">Attempt #{{ i + 1 }}</span>
              <div class="status-badge" [attr.data-status]="attempt.status">{{ attempt.status }}</div>
              <span class="attempt-time">{{ attempt.createdAt | date:'HH:mm:ss' }}</span>
            </div>
            <div class="attempt-body">
              <span>{{ attempt.orderType }} {{ attempt.side }} @ {{ attempt.requestedPrice | number:'1.3-3' }}</span>
              <span class="muted" *ngIf="attempt.status === 'FILLED'">Filled {{ attempt.filledShares || 0 | number:'1.2-2' }} shares</span>
              <span class="error-msg" *ngIf="attempt.status !== 'FILLED'">{{ attempt.errorMessage || 'No liquidity at price' }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="card mt-4">
        <div class="section-header">
          <h2>Fills</h2>
          <div class="warning-chip" *ngIf="(t.entryFilledShares || 0) > 0 && !hasEntryFills(t)">
            No imported fill rows; fill inferred from order status
          </div>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Time</th>
                <th>Outcome</th>
                <th>Side</th>
                <th>Price</th>
                <th>Shares</th>
                <th>Amount USD</th>
                <th>Fee</th>
                <th>Role</th>
                <th>Venue</th>
                <th>Order</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let fill of t.fills" [routerLink]="fill.orderId ? ['/orders', fill.orderId] : null" [class.clickable-row]="fill.orderId">
                <td>{{ fill.filledAt | date:'medium' }}</td>
                <td><span class="outcome-chip">{{ t.outcome }}</span></td>
                <td><span class="side" [class.side-buy]="fill.side === 'BUY'" [class.side-sell]="fill.side === 'SELL'">{{ fill.side }}</span></td>
                <td>{{ fill.price | number:'1.3-3' }}</td>
                <td>{{ fill.shares | number:'1.2-2' }}</td>
                <td>$ {{ fill.amountUsd | number:'1.2-2' }}</td>
                <td>$ {{ fill.feeUsd || 0 | number:'1.4-4' }}</td>
                <td><span class="role-badge">{{ fill.liquidityRole }}</span></td>
                <td>{{ fill.venue }}</td>
                <td>{{ fill.orderId ? '#' + fill.orderId : 'N/A' }}</td>
              </tr>
              <tr *ngIf="t.fills.length === 0">
                <td colspan="10" class="empty-cell">No fills recorded for this trade.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="card mt-4">
        <h2>Orders</h2>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Created / Latency</th>
                <th>Phase</th>
                <th>Action</th>
                <th>Status</th>
                <th>Price / Shares</th>
                <th>Filled / Avg</th>
                <th>Amount / Fee</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let order of t.orders" [routerLink]="['/orders', order.id]" class="clickable-row">
                <td>
                  <div class="time">{{ order.createdAt | date:'MMM d, HH:mm:ss' }}</div>
                  <div class="latency" *ngIf="order.latencyMs">{{ order.latencyMs }}ms</div>
                </td>
                <td><span class="phase-badge">{{ order.phase }}</span></td>
                <td>
                  <div class="action-line">
                    <span class="side" [class.side-buy]="order.side === 'BUY'" [class.side-sell]="order.side === 'SELL'">{{ order.side }}</span>
                    <span class="outcome-chip">{{ order.outcome }}</span>
                  </div>
                  <div class="type">{{ order.orderType }}</div>
                </td>
                <td>
                  <div class="status-badge" [attr.data-status]="order.status">{{ order.status }}</div>
                  <div class="failed-label" *ngIf="order.status === 'FAILED' || order.status === 'REJECTED'">Failed attempt</div>
                </td>
                <td>
                  <div class="price">{{ order.requestedPrice | number:'1.3-3' }}</div>
                  <div class="shares muted">{{ order.requestedShares || 0 | number:'1.2-2' }} req</div>
                </td>
                <td>
                  <div class="filled">{{ order.filledShares || 0 | number:'1.2-2' }}</div>
                  <div class="avg-price muted">{{ order.avgFillPrice !== null ? (order.avgFillPrice | number:'1.3-3') : '-' }}</div>
                </td>
                <td>
                  <div class="amount">$ {{ order.filledAmountUsd || 0 | number:'1.2-2' }}</div>
                  <div class="fee negative" *ngIf="order.realizedFeeUsd">$ {{ order.realizedFeeUsd | number:'1.4-4' }}</div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="card mt-4">
        <h2>Execution Events</h2>
        <app-execution-events [events]="t.events"></app-execution-events>
      </div>
    </div>
  `,
  styles: `
    .container { padding: 24px; max-width: 1400px; margin: 0 auto; }
    .header { margin-bottom: 24px; }
    .header-main { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 8px; }
    .badges { display: flex; gap: 8px; flex-wrap: wrap; }
    .back-link { text-decoration: none; color: #3b82f6; font-weight: 500; font-size: 14px; }
    h1 { font-size: 28px; font-weight: 800; margin: 0; color: #111827; }
    h2 { font-size: 18px; font-weight: 700; margin: 0 0 16px; color: #374151; }

    .market-info { margin-bottom: 4px; }
    .market-link { font-size: 14px; font-weight: 700; color: #3b82f6; text-decoration: none; }
    .market-link:hover { text-decoration: underline; }

    .card { background: #fff; border-radius: 8px; padding: 24px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); border: 1px solid #f3f4f6; }
    .mt-4 { margin-top: 24px; }
    .mt-2 { margin-top: 16px; }

    .outcome-card { background: linear-gradient(to bottom right, #ffffff, #f9fafb); border-left: 4px solid #3b82f6; }
    .outcome-grid { display: grid; grid-template-columns: 1fr 1fr 1.5fr; gap: 32px; align-items: center; }
    .outcome-main .value.large { font-size: 24px; font-weight: 800; color: #111827; }
    .trade-action { margin-top: 6px; font-size: 13px; font-weight: 700; color: #374151; }
    .pnl-section .pnl { font-size: 24px; font-weight: 800; }
    .pnl-section .net-return { font-size: 14px; font-weight: 600; margin-left: 8px; }
    .economics-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; background: #fff; padding: 16px; border-radius: 8px; border: 1px solid #e5e7eb; }
    .eco-item { display: flex; flex-direction: column; }
    .eco-item label { font-size: 11px; font-weight: 700; color: #6b7280; text-transform: uppercase; margin-bottom: 4px; }
    .eco-item span { font-size: 14px; font-weight: 600; }
    .eco-item .usd { font-size: 12px; color: #4b5563; }
    .label { font-size: 12px; font-weight: 600; color: #6b7280; text-transform: uppercase; margin-bottom: 4px; }
    .round-trip-strip { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px; margin-top: 18px; padding-top: 18px; border-top: 1px solid #e5e7eb; }
    .round-trip-item { min-width: 0; border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px; background: #fff; display: grid; gap: 4px; }
    .round-trip-item.primary { border-color: #bfdbfe; background: #eff6ff; }
    .round-trip-item label { font-size: 11px; color: #6b7280; font-weight: 800; text-transform: uppercase; }
    .round-trip-item span { font-size: 15px; font-weight: 800; color: #111827; overflow-wrap: anywhere; }
    .round-trip-item small { font-size: 12px; color: #6b7280; font-weight: 600; }

    .timeline-horiz { display: flex; align-items: center; justify-content: space-between; padding: 16px 0; overflow-x: auto; }
    .timeline-step { display: flex; flex-direction: column; align-items: center; text-align: center; min-width: 100px; position: relative; }
    .step-icon { width: 44px; height: 44px; border-radius: 50%; background: #f3f4f6; color: #6b7280; display: flex; align-items: center; justify-content: center; font-size: 9px; font-weight: 700; text-transform: uppercase; border: 2px solid #e5e7eb; margin-bottom: 8px; padding: 2px; }
    .timeline-step.completed .step-icon { background: #d1fae5; color: #065f46; border-color: #10b981; }
    .timeline-step.failed .step-icon { background: #fee2e2; color: #991b1b; border-color: #ef4444; }
    .step-time { font-size: 11px; font-weight: 600; color: #4b5563; }
    .step-hint { font-size: 10px; color: #ef4444; font-weight: 500; margin-top: 2px; }
    .timeline-connector { flex-grow: 1; height: 2px; background: #e5e7eb; margin: -24px 8px 0; }
    .timeline-step.completed + .timeline-connector { background: #10b981; }

    .decision-snapshot summary { cursor: pointer; display: flex; align-items: center; gap: 12px; list-style: none; }
    .decision-snapshot summary::-webkit-details-marker { display: none; }
    .reason-text { font-style: italic; color: #4b5563; line-height: 1.4; }

    .attempts-list { display: flex; flex-direction: column; gap: 12px; }
    .attempt-item { padding: 12px; border-radius: 8px; border: 1px solid #e5e7eb; background: #f9fafb; }
    .attempt-item.attempt-failed { border-left: 4px solid #ef4444; }
    .attempt-header { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
    .attempt-num { font-weight: 700; font-size: 12px; color: #374151; }
    .attempt-time { font-size: 11px; color: #6b7280; margin-left: auto; }
    .attempt-body { display: flex; justify-content: space-between; gap: 16px; font-size: 13px; font-weight: 500; }
    .error-msg { color: #ef4444; font-size: 12px; }

    .status-badge, .mode-badge, .strategy-badge, .bot-badge, .role-badge, .phase-badge {
      padding: 2px 10px; border-radius: 9999px; font-size: 11px; font-weight: 700; text-transform: uppercase;
    }
    .status-badge { background: #e5e7eb; color: #374151; }
    .status-badge[data-status="OPEN"] { background: #f59e0b; color: #fff; }
    .status-badge[data-status="CLOSED"] { background: #3b82f6; color: #fff; }
    .status-badge[data-status="RESOLVED"] { background: #10b981; color: #fff; }
    .status-badge[data-status="FILLED"] { background: #10b981; color: #fff; }
    .status-badge[data-status="PARTIALLY_FILLED"] { background: #3b82f6; color: #fff; }
    .status-badge[data-status="CANCELLED"] { background: #f3f4f6; color: #6b7280; }
    .status-badge[data-status="FAILED"], .status-badge[data-status="REJECTED"] { background: #ef4444; color: #fff; }
    .mode-badge[data-mode="LIVE"] { background: #fee2e2; color: #991b1b; }
    .mode-badge[data-mode="PAPER"] { background: #d1fae5; color: #065f46; }
    .strategy-badge { background: #e0e7ff; color: #4338ca; }
    .bot-badge { background: #f3f4f6; color: #374151; }
    .role-badge { background: #f3f4f6; color: #6b7280; }
    .phase-badge { background: #f3f4f6; color: #4b5563; }

    .table-wrap { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; min-width: 800px; }
    th { text-align: left; padding: 12px 16px; background: #f9fafb; border-bottom: 2px solid #e5e7eb; font-size: 11px; color: #6b7280; text-transform: uppercase; }
    td { padding: 12px 16px; border-bottom: 1px solid #f3f4f6; font-size: 13px; vertical-align: middle; }
    .clickable-row { cursor: pointer; transition: background-color 0.2s; }
    .clickable-row:hover { background: #f8fafc; }
    .side { font-weight: 700; }
    .side-buy { color: #10b981; }
    .side-sell { color: #3b82f6; }
    .action-line { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
    .outcome-chip { display: inline-block; max-width: 160px; padding: 2px 8px; border-radius: 9999px; background: #eef2ff; color: #3730a3; font-size: 11px; font-weight: 800; text-transform: uppercase; overflow-wrap: anywhere; }
    .time { font-weight: 600; color: #111827; }
    .latency { font-size: 11px; color: #9ca3af; font-family: monospace; }
    .failed-label { font-size: 10px; font-weight: 700; color: #ef4444; text-transform: uppercase; margin-top: 2px; }
    .empty-cell { text-align: center; color: #9ca3af; padding: 20px; }

    .grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 24px; }
    .info-grid { display: grid; gap: 8px; }
    .info-item { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; border-bottom: 1px solid #f9fafb; padding: 4px 0; }
    .info-item label { color: #6b7280; font-weight: 500; flex-shrink: 0; }
    .info-item span { font-weight: 600; color: #111827; text-align: right; overflow-wrap: anywhere; }
    .positive { color: #10b981; }
    .negative { color: #ef4444; }
    .muted { color: #9ca3af; font-size: 11px; }
    .section-header { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 16px; }
    .warning-chip { background: #fffbeb; color: #92400e; border: 1px solid #fef3c7; padding: 4px 12px; border-radius: 9999px; font-size: 12px; font-weight: 600; display: flex; align-items: center; gap: 8px; }

    @media (max-width: 900px) {
      .outcome-grid, .grid { grid-template-columns: 1fr; }
      .round-trip-strip { grid-template-columns: 1fr; }
      .economics-grid { grid-template-columns: 1fr; }
      .header-main { align-items: flex-start; flex-direction: column; }
    }
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

  hasEntryOrder(t: TradeDetailResponse) {
    return t.orders.some(o => o.phase === 'ENTRY');
  }

  entryOrder(t: TradeDetailResponse) {
    return t.orders.find(o => o.phase === 'ENTRY');
  }

  hasEntryFills(t: TradeDetailResponse) {
    return t.fills.some(f => f.side === t.decisionSide);
  }

  hasFailedExit(t: TradeDetailResponse) {
    return t.orders.some(o => o.phase === 'EXIT' && (o.status === 'FAILED' || o.status === 'REJECTED'));
  }

  firstFailedExit(t: TradeDetailResponse) {
    return t.orders.find(o => o.phase === 'EXIT' && (o.status === 'FAILED' || o.status === 'REJECTED'));
  }

  exitAttempts(t: TradeDetailResponse) {
    return t.orders.filter(o => o.phase === 'EXIT');
  }
}
