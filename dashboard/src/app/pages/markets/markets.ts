import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { MarketSummaryResponse } from '../../models/api.models';

@Component({
  selector: 'app-markets',
  imports: [CommonModule, RouterModule],
  template: `
    <div class="header">
      <h1>Markets</h1>
      <div class="header-actions">
        <label class="filter-toggle">
          <input
            type="checkbox"
            [checked]="showResolved()"
            (change)="toggleShowResolved($event)"
          />
          Include resolved
        </label>
      </div>
    </div>

    <div class="card">
      <table>
        <thead>
          <tr>
            <th>Question</th>
            <th>End Date</th>
            <th>Tracking</th>
            <th>Resolution</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let market of markets()" [routerLink]="['/markets', market.polymarketMarketId]" class="clickable-row">
            <td>
              <div class="question">{{ market.question }}</div>
              <div class="slug">{{ market.slug }}</div>
            </td>
            <td>{{ market.endDate | date:'short' }}</td>
            <td>
              <span class="badge badge-outline">{{ market.trackingStatus }}</span>
            </td>
            <td>
              <span class="badge badge-outline">{{ market.resolutionStatus }}</span>
            </td>
            <td>
              <span class="badge" [class.badge-success]="market.active" [class.badge-gray]="!market.active">
                {{ market.active ? 'ACTIVE' : (market.resolved ? 'RESOLVED' : 'INACTIVE') }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  `,
  styles: `
    .header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 24px;
      gap: 16px;
    }

    h1 { font-size: 24px; font-weight: 700; }

    .header-actions {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
      justify-content: flex-end;
    }

    .filter-toggle {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      color: #374151;
      user-select: none;
    }

    .filter-toggle input {
      width: 16px;
      height: 16px;
      margin: 0;
    }

    .card {
      background-color: #fff;
      border-radius: 8px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.1);
      overflow: hidden;
    }

    table { width: 100%; border-collapse: collapse; }
    th {
      text-align: left;
      padding: 12px 16px;
      background-color: #f9fafb;
      border-bottom: 1px solid #e5e7eb;
      font-size: 12px;
      font-weight: 600;
      color: #6b7280;
      text-transform: uppercase;
    }

    td { padding: 16px; border-bottom: 1px solid #f3f4f6; font-size: 14px; }

    .clickable-row { cursor: pointer; transition: background-color 0.2s; }
    .clickable-row:hover { background-color: #f9fafb; }

    .question { font-weight: 600; color: #111827; }
    .slug { font-size: 12px; color: #6b7280; }

    .badge {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 9999px;
      font-size: 11px;
      font-weight: 600;
    }

    .badge-success { background-color: #def7ec; color: #03543f; }
    .badge-gray { background-color: #f3f4f6; color: #374151; }
    .badge-outline { border: 1px solid #e5e7eb; color: #6b7280; background: transparent; }
  `
})
export class Markets implements OnInit {
  private apiService = inject(ApiService);
  markets = signal<MarketSummaryResponse[]>([]);
  showResolved = signal(false);

  ngOnInit() {
    this.fetchMarkets();
  }

  toggleShowResolved(event: Event) {
    const input = event.target as HTMLInputElement;
    this.showResolved.set(input.checked);
    this.fetchMarkets();
  }

  private fetchMarkets() {
    const params: any = { limit: 50 };
    if (!this.showResolved()) {
      params.active = true;
    }
    this.apiService.getMarkets(params).subscribe(markets => {
      this.markets.set(markets);
    });
  }
}
