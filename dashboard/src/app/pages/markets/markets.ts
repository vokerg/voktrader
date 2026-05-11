import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { MarketSummaryResponse } from '../../models/api.models';

@Component({
  selector: 'app-markets',
  imports: [CommonModule],
  template: `
    <div class="header">
      <h1>Markets</h1>
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
          <tr *ngFor="let market of markets()">
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
                {{ market.active ? 'ACTIVE' : 'INACTIVE' }}
              </span>
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

  ngOnInit() {
    this.apiService.getMarkets({ active: true, limit: 50 }).subscribe(markets => {
      this.markets.set(markets);
    });
  }
}
