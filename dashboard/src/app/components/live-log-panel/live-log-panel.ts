import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TelemetryConnectionState, TradingEvent } from '../../models/api.models';

@Component({
  selector: 'app-live-log-panel',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="card live-log-panel">
      <div class="panel-header">
        <h3>{{ title }}</h3>
        <span class="connection-state" [attr.data-state]="connectionState">{{ connectionState }}</span>
      </div>

      <div class="event-list" *ngIf="events.length > 0; else emptyState">
        <div *ngFor="let event of events" class="event-row">
          <div class="event-line">
            <span class="event-time">{{ event.timestamp | date:'HH:mm:ss' }}</span>
            <span class="event-type">{{ truncate(event.type, 34) }}</span>
            <span *ngIf="event.botId !== null" class="event-meta">Bot #{{ event.botId }}</span>
            <span *ngIf="marketLabel(event)" class="event-meta">{{ marketLabel(event) }}</span>
            <span *ngIf="event.outcome || event.tokenId" class="event-meta">{{ truncate(event.outcome || event.tokenId || '', 24) }}</span>
          </div>

          <div *ngIf="isPriceEvent(event)" class="snapshot-indicators">
            <!-- Dual outcome snapshot (UP/DOWN) -->
            <ng-container *ngIf="event.type.includes('SNAPSHOT') && event.data['upBid'] !== undefined; else singleOutcome">
              <div class="indicator-group">
                <span class="label">UP</span>
                <span class="value">{{ event.data['upBid'] }} / {{ event.data['upAsk'] }}</span>
                <span class="spread">({{ event.data['upSpread'] }})</span>
              </div>
              <div class="indicator-group">
                <span class="label">DOWN</span>
                <span class="value">{{ event.data['downBid'] }} / {{ event.data['downAsk'] }}</span>
                <span class="spread">({{ event.data['downSpread'] }})</span>
              </div>
            </ng-container>

            <!-- Single outcome update -->
            <ng-template #singleOutcome>
              <div class="indicator-group" *ngIf="event.data['bid'] !== undefined">
                <span class="label" *ngIf="event.data['outcome']">{{ event.data['outcome'] }}</span>
                <span class="value">{{ event.data['bid'] }} / {{ event.data['ask'] }}</span>
                <span class="spread">({{ event.data['spread'] }})</span>
              </div>
            </ng-template>

            <div class="indicator-group" *ngIf="event.data['remaining']">
              <span class="label">TIME</span>
              <span class="value">{{ event.data['remaining'] }}</span>
            </div>
          </div>

          <div *ngIf="event.reason && !isPriceEvent(event)" class="event-reason">{{ truncate(event.reason, 180) }}</div>

          <details *ngIf="hasData(event)" class="event-details">
            <summary>Details</summary>
            <pre>{{ formatData(event.data) }}</pre>
          </details>
        </div>
      </div>

      <ng-template #emptyState>
        <div class="empty-state">{{ emptyText || 'No telemetry yet' }}</div>
      </ng-template>
    </div>
  `,
  styles: `
    .card {
      background-color: #fff;
      border-radius: 8px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.1);
      overflow: hidden;
    }

    .live-log-panel {
      min-width: 0;
    }

    .panel-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 14px 16px;
      border-bottom: 1px solid #e5e7eb;
      background: #f9fafb;
    }

    .panel-header h3 {
      margin: 0;
      font-size: 13px;
      text-transform: uppercase;
      color: #374151;
      overflow-wrap: anywhere;
    }

    .connection-state {
      flex-shrink: 0;
      padding: 2px 8px;
      border-radius: 9999px;
      font-size: 11px;
      font-weight: 700;
      text-transform: uppercase;
      background: #f3f4f6;
      color: #4b5563;
    }

    .connection-state[data-state="live"] {
      background: #d1fae5;
      color: #065f46;
    }

    .connection-state[data-state="connecting"] {
      background: #fef3c7;
      color: #92400e;
    }

    .connection-state[data-state="error"] {
      background: #fee2e2;
      color: #991b1b;
    }

    .connection-state[data-state="disconnected"] {
      background: #e5e7eb;
      color: #374151;
    }

    .event-list {
      max-height: 420px;
      overflow-y: auto;
    }

    .event-row {
      padding: 12px 16px;
      border-bottom: 1px solid #f3f4f6;
    }

    .event-row:last-child {
      border-bottom: none;
    }

    .event-line {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 6px;
      min-width: 0;
    }

    .event-time {
      font-family: monospace;
      font-size: 11px;
      color: #6b7280;
      font-weight: 700;
    }

    .event-type {
      max-width: 100%;
      padding: 2px 6px;
      border-radius: 4px;
      background: #e0f2fe;
      color: #075985;
      font-size: 10px;
      font-weight: 800;
      text-transform: uppercase;
      overflow-wrap: anywhere;
    }

    .event-meta {
      max-width: 100%;
      padding: 2px 6px;
      border-radius: 4px;
      background: #f3f4f6;
      color: #4b5563;
      font-size: 11px;
      font-weight: 600;
      overflow-wrap: anywhere;
    }

    .event-reason {
      margin-top: 6px;
      color: #374151;
      font-size: 13px;
      line-height: 1.35;
      overflow-wrap: anywhere;
    }

    .snapshot-indicators {
      margin-top: 8px;
      display: flex;
      flex-wrap: wrap;
      column-gap: 16px;
      row-gap: 8px;
      background: #f8fafc;
      padding: 8px 12px;
      border-radius: 6px;
      border: 1px solid #e2e8f0;
    }

    .indicator-group {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
      font-family: monospace;
    }

    .indicator-group .label {
      font-weight: 800;
      color: #64748b;
      font-size: 10px;
      text-transform: uppercase;
    }

    .indicator-group .value {
      font-weight: 700;
      color: #1e293b;
    }

    .indicator-group .spread {
      color: #94a3b8;
      font-size: 11px;
    }

    .event-details {
      margin-top: 8px;
    }

    .event-details summary {
      color: #3b82f6;
      cursor: pointer;
      font-size: 11px;
      font-weight: 700;
    }

    pre {
      margin: 6px 0 0;
      padding: 10px;
      max-height: 220px;
      overflow: auto;
      border: 1px solid #e5e7eb;
      border-radius: 6px;
      background: #f9fafb;
      color: #1f2937;
      font-size: 11px;
      white-space: pre-wrap;
      word-break: break-word;
    }

    .empty-state {
      padding: 28px 16px;
      color: #9ca3af;
      text-align: center;
      font-size: 13px;
    }
  `
})
export class LiveLogPanel {
  @Input({ required: true }) title = '';
  @Input({ required: true }) events: TradingEvent[] = [];
  @Input({ required: true }) connectionState: TelemetryConnectionState = 'disconnected';
  @Input() emptyText = 'No telemetry yet';

  isPriceEvent(event: TradingEvent): boolean {
    return event.type.startsWith('PRICE_');
  }

  marketLabel(event: TradingEvent): string {
    return this.truncate(event.marketSlug || event.marketId || '', 36);
  }

  hasData(event: TradingEvent): boolean {
    return !!event.data && Object.keys(event.data).length > 0;
  }

  formatData(data: Record<string, unknown>): string {
    const formatted = JSON.stringify(data, (_key, value) => {
      if (typeof value === 'string') {
        return this.truncate(value, 500);
      }
      return value;
    }, 2);

    return this.truncate(formatted || '{}', 5000);
  }

  truncate(value: unknown, maxLength: number): string {
    const text = value === null || value === undefined ? '' : String(value);
    if (text.length <= maxLength) {
      return text;
    }
    return `${text.slice(0, Math.max(0, maxLength - 3))}...`;
  }
}
