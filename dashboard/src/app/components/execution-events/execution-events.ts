import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { TradeEventResponse } from '../../models/api.models';

@Component({
  selector: 'app-execution-events',
  standalone: true,
  imports: [CommonModule, RouterModule],
  template: `
    <div class="events-timeline">
      <div *ngFor="let event of events" class="event-row">
        <div class="event-time">{{ event.createdAt | date:'HH:mm:ss.SSS' }}</div>
        <div class="event-content">
          <div class="event-header">
            <span class="event-type-chip" [attr.data-type]="eventGroup(event)">{{ event.eventType }}</span>
            <span class="event-msg">{{ event.message }}</span>
            <a [routerLink]="['/events', event.id]" class="event-link" title="Open event details page">
              <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"></path><polyline points="15 3 21 3 21 9"></polyline><line x1="10" y1="14" x2="21" y2="3"></line></svg>
            </a>
          </div>
          <details *ngIf="event.payloadJson" class="event-payload">
            <summary>View Payload</summary>
            <pre>{{ formatJson(event.payloadJson) }}</pre>
          </details>
        </div>
      </div>
      <div *ngIf="events.length === 0" class="empty-cell">No events recorded.</div>
    </div>
  `,
  styles: `
    .events-timeline { border-left: 2px solid #e5e7eb; margin-left: 80px; display: flex; flex-direction: column; gap: 16px; padding: 16px 0; }
    .event-row { display: flex; position: relative; }
    .event-time { position: absolute; left: -90px; width: 70px; text-align: right; font-size: 11px; font-weight: 700; color: #9ca3af; font-family: monospace; top: 4px; }
    .event-row::before { content: ""; position: absolute; width: 10px; height: 10px; border-radius: 50%; background: #e5e7eb; border: 2px solid #fff; left: -6px; top: 6px; }
    .event-content { flex-grow: 1; padding-left: 24px; }
    .event-header { display: flex; align-items: baseline; gap: 12px; }
    .event-type-chip { font-size: 10px; font-weight: 800; padding: 1px 6px; border-radius: 4px; text-transform: uppercase; }
    .event-type-chip[data-type="strategy"] { background: #ede9fe; color: #5b21b6; }
    .event-type-chip[data-type="order"] { background: #e0e7ff; color: #3730a3; }
    .event-type-chip[data-type="fill"] { background: #d1fae5; color: #065f46; }
    .event-type-chip[data-type="failed"] { background: #fee2e2; color: #991b1b; }
    .event-type-chip[data-type="system"] { background: #f3f4f6; color: #4b5563; }
    .event-msg { font-size: 14px; color: #374151; }
    .event-link { color: #9ca3af; transition: color 0.2s; display: flex; align-items: center; }
    .event-link:hover { color: #3b82f6; }
    
    .event-payload { margin-top: 8px; }
    .event-payload summary { font-size: 11px; color: #3b82f6; cursor: pointer; font-weight: 600; }
    pre { background: #f9fafb; padding: 12px; border-radius: 6px; font-size: 11px; overflow-x: auto; border: 1px solid #e5e7eb; margin-top: 4px; color: #1f2937; white-space: pre-wrap; word-break: break-all; }
    .empty-cell { text-align: center; color: #9ca3af; padding: 20px; }

    @media (max-width: 900px) {
      .events-timeline { margin-left: 0; padding-left: 12px; }
      .event-time { position: static; width: auto; text-align: left; margin-right: 12px; }
      .event-row::before { display: none; }
    }
  `
})
export class ExecutionEvents {
  @Input() events: TradeEventResponse[] = [];

  eventGroup(event: TradeEventResponse) {
    const type = event.eventType.toLowerCase();
    if (type.includes('decision')) return 'strategy';
    if (type.includes('submit') || type.includes('create') || type.includes('cancel')) return 'order';
    if (type.includes('fill')) return 'fill';
    if (type.includes('fail') || type.includes('reject')) return 'failed';
    return 'system';
  }

  formatJson(json: string | null) {
    if (!json) return '';
    try {
      return JSON.stringify(JSON.parse(json), null, 2);
    } catch {
      return json;
    }
  }
}
