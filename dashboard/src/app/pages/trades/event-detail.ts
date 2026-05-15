import { Component, signal, inject, OnInit, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { TradeEventResponse } from '../../models/api.models';

@Component({
  selector: 'app-event-detail',
  standalone: true,
  imports: [CommonModule, RouterModule],
  template: `
    <div *ngIf="event() as e" class="container">
      <div class="header">
        <a [routerLink]="['/trades', e.tradeId]" class="back-link">← Back to Trade #{{ e.tradeId }}</a>
        <h1>Event Details #{{ e.id }}</h1>
      </div>

      <div class="grid">
        <div class="card">
          <h2>Summary</h2>
          <div class="info-grid">
            <div class="info-item"><label>Type</label><span>{{ e.eventType }}</span></div>
            <div class="info-item"><label>Message</label><span>{{ e.message }}</span></div>
            <div class="info-item"><label>Time</label><span>{{ e.createdAt | date:'medium' }}</span></div>
            <div class="info-item"><label>Trade ID</label><span>#{{ e.tradeId }}</span></div>
            <div class="info-item" *ngIf="e.tradeOrderId">
              <label>Order ID</label>
              <a [routerLink]="['/orders', e.tradeOrderId]">#{{ e.tradeOrderId }}</a>
            </div>
            <div class="info-item" *ngIf="e.tradeFillId"><label>Fill ID</label><span>#{{ e.tradeFillId }}</span></div>
          </div>
        </div>
      </div>

      <div class="card mt-4">
        <h2>Payload JSON</h2>
        <pre class="payload-box">{{ formattedPayload() || 'No payload data' }}</pre>
      </div>
    </div>
  `,
  styles: `
    .container { padding: 24px; }
    .header { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; }
    .back-link { text-decoration: none; color: #3b82f6; font-weight: 500; }
    h1 { font-size: 24px; font-weight: 700; margin: 0; }

    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(400px, 1fr)); gap: 24px; }
    
    .card {
      background: #fff;
      border-radius: 8px;
      padding: 20px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.1);
    }
    .mt-4 { margin-top: 24px; }
    h2 { font-size: 18px; font-weight: 600; margin-top: 0; margin-bottom: 16px; color: #374151; }

    .info-grid { display: grid; grid-template-columns: 1fr; gap: 8px; }
    .info-item { display: flex; justify-content: space-between; font-size: 14px; padding: 4px 0; border-bottom: 1px solid #f9fafb; }
    .info-item label { color: #6b7280; font-weight: 500; }
    .info-item span, .info-item a { font-weight: 600; text-align: right; }
    .info-item a { color: #3b82f6; text-decoration: none; }

    .payload-box {
      background: #f9fafb;
      border: 1px solid #e5e7eb;
      border-radius: 4px;
      padding: 16px;
      font-size: 13px;
      font-family: 'JetBrains Mono', 'Fira Code', monospace;
      white-space: pre-wrap;
      word-break: break-all;
      color: #1f2937;
      line-height: 1.5;
      max-height: 800px;
      overflow-y: auto;
    }
  `
})
export class EventDetail implements OnInit {
  private apiService = inject(ApiService);
  private route = inject(ActivatedRoute);
  
  event = signal<TradeEventResponse | null>(null);
  
  formattedPayload = computed(() => {
    const e = this.event();
    if (!e || !e.payloadJson) return null;
    try {
      const parsed = JSON.parse(e.payloadJson);
      return JSON.stringify(parsed, null, 2);
    } catch (err) {
      return e.payloadJson;
    }
  });

  ngOnInit() {
    this.route.params.subscribe(params => {
      const id = +params['id'];
      if (id) {
        this.apiService.getTradeEvent(id).subscribe(event => {
          this.event.set(event);
        });
      }
    });
  }
}
