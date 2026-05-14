import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { MarketDetailResponse } from '../../models/api.models';

@Component({
  selector: 'app-market-detail',
  standalone: true,
  imports: [CommonModule, RouterModule],
  template: `
    <div *ngIf="market() as m" class="container">
      <div class="header">
        <a routerLink="/markets" class="back-link">← Back to Markets</a>
        <h1>Market Details</h1>
      </div>

      <div class="card">
        <div class="market-header">
          <div class="slug">{{ m.slug }}</div>
          <div class="question">{{ m.question }}</div>
        </div>
        
        <div class="info-grid mt-4">
          <div class="info-item"><label>Polymarket ID</label><span>{{ m.polymarketMarketId }}</span></div>
          <div class="info-item"><label>Condition ID</label><span>{{ m.conditionId }}</span></div>
          <div class="info-item"><label>End Date</label><span>{{ m.endDate | date:'medium' }}</span></div>
          <div class="info-item"><label>Status</label>
            <span class="status-badge" [class.active]="m.active">{{ m.active ? 'ACTIVE' : 'INACTIVE' }}</span>
          </div>
          <div class="info-item"><label>Tracking</label><span>{{ m.trackingStatus }}</span></div>
          <div class="info-item"><label>Resolution</label><span>{{ m.resolutionStatus }}</span></div>
          <div *ngIf="m.resolved" class="info-item"><label>Winning Outcome</label><span class="winner">{{ m.winningOutcome }}</span></div>
        </div>
      </div>

      <div class="grid mt-4">
        <div class="card">
          <h2>Latest Price (Outcome UP)</h2>
          <div *ngIf="m.latestPrice" class="info-grid">
            <div class="info-item"><label>Bid</label><span>$ {{ m.latestPrice.upBid | number:'1.3-3' }}</span></div>
            <div class="info-item"><label>Ask</label><span>$ {{ m.latestPrice.upAsk | number:'1.3-3' }}</span></div>
            <div class="info-item"><label>Spread</label><span>$ {{ m.latestPrice.upSpread | number:'1.3-3' }}</span></div>
            <div class="info-item"><label>Captured At</label><span>{{ m.latestPrice.capturedAt | date:'mediumTime' }}</span></div>
          </div>
          <div *ngIf="!m.latestPrice">No price data available</div>
        </div>

        <div class="card">
          <h2>Latest Price (Outcome DOWN)</h2>
          <div *ngIf="m.latestPrice" class="info-grid">
            <div class="info-item"><label>Bid</label><span>$ {{ m.latestPrice.downBid | number:'1.3-3' }}</span></div>
            <div class="info-item"><label>Ask</label><span>$ {{ m.latestPrice.downAsk | number:'1.3-3' }}</span></div>
            <div class="info-item"><label>Spread</label><span>$ {{ m.latestPrice.downSpread | number:'1.3-3' }}</span></div>
            <div class="info-item"><label>Captured At</label><span>{{ m.latestPrice.capturedAt | date:'mediumTime' }}</span></div>
          </div>
          <div *ngIf="!m.latestPrice">No price data available</div>
        </div>
      </div>

      <div class="card mt-4">
        <h2>Order Book</h2>
        <div *ngIf="m.latestOrderBook" class="info-grid">
           <div class="info-item"><label>Outcome</label><span>{{ m.latestOrderBook.outcome }}</span></div>
           <div class="info-item"><label>Best Bid</label><span>$ {{ m.latestOrderBook.bestBid | number:'1.3-3' }}</span></div>
           <div class="info-item"><label>Best Ask</label><span>$ {{ m.latestOrderBook.bestAsk | number:'1.3-3' }}</span></div>
           <div class="info-item"><label>Spread</label><span>$ {{ m.latestOrderBook.spread | number:'1.3-3' }}</span></div>
           <div class="info-item"><label>Bid Depth</label><span>{{ m.latestOrderBook.bidDepth | number:'1.0-0' }}</span></div>
           <div class="info-item"><label>Ask Depth</label><span>{{ m.latestOrderBook.askDepth | number:'1.0-0' }}</span></div>
           <div class="info-item"><label>Stale</label><span>{{ m.latestOrderBook.stale }}</span></div>
           <div class="info-item"><label>Captured At</label><span>{{ m.latestOrderBook.capturedAt | date:'mediumTime' }}</span></div>
        </div>
        <div *ngIf="!m.latestOrderBook">No order book data available</div>
      </div>

      <div class="card mt-4">
        <h2>Recent Price History (UP)</h2>
        <table>
          <thead>
            <tr>
              <th>Time</th>
              <th>Bid</th>
              <th>Ask</th>
              <th>Spread</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let p of m.recentPrices">
              <td>{{ p.capturedAt | date:'mediumTime' }}</td>
              <td>$ {{ p.upBid | number:'1.3-3' }}</td>
              <td>$ {{ p.upAsk | number:'1.3-3' }}</td>
              <td>$ {{ p.upSpread | number:'1.3-3' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `,
  styles: `
    .container { padding: 24px; }
    .header { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; }
    .back-link { text-decoration: none; color: #3b82f6; font-weight: 500; }
    h1 { font-size: 24px; font-weight: 700; margin: 0; }

    .card { background: #fff; border-radius: 8px; padding: 20px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
    .mt-4 { margin-top: 24px; }
    
    .slug { font-size: 14px; font-weight: 600; color: #6b7280; text-transform: uppercase; letter-spacing: 0.05em; }
    .question { font-size: 20px; font-weight: 700; color: #111827; margin-top: 4px; }

    .info-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
    .info-item { display: flex; justify-content: space-between; font-size: 14px; padding: 4px 0; border-bottom: 1px solid #f3f4f6; }
    .info-item label { color: #6b7280; }
    .info-item span { font-weight: 500; }

    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
    h2 { font-size: 18px; font-weight: 600; margin-top: 0; margin-bottom: 16px; }

    .status-badge { padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 700; background: #fee2e2; color: #991b1b; }
    .status-badge.active { background: #d1fae5; color: #065f46; }

    .winner { color: #059669; font-weight: 700; }

    table { width: 100%; border-collapse: collapse; }
    th { text-align: left; padding: 12px 8px; border-bottom: 2px solid #f3f4f6; color: #6b7280; font-size: 12px; }
    td { padding: 12px 8px; border-bottom: 1px solid #f3f4f6; font-size: 14px; }
  `
})
export class MarketDetail implements OnInit {
  private apiService = inject(ApiService);
  private route = inject(ActivatedRoute);
  market = signal<MarketDetailResponse | null>(null);

  ngOnInit() {
    this.route.params.subscribe(params => {
      const id = params['id'];
      if (id) {
        this.apiService.getMarket(id).subscribe(market => {
          this.market.set(market);
        });
      }
    });
  }
}
