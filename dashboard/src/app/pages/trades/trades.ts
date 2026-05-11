import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { TradeSummaryResponse } from '../../models/api.models';

@Component({
  selector: 'app-trades',
  imports: [CommonModule],
  template: `
    <div class="header">
      <h1>Trade History</h1>
    </div>

    <div class="card">
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Bot</th>
            <th>Market</th>
            <th>Side</th>
            <th>Status</th>
            <th>Entry</th>
            <th>Exit</th>
            <th>PnL</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let trade of trades()">
            <td>{{ trade.decisionAt | date:'short' }}</td>
            <td>#{{ trade.botId }}</td>
            <td>
              <div class="slug">{{ trade.marketSlug }}</div>
              <div class="question">{{ trade.question }}</div>
            </td>
            <td>
              <span class="side" [class.side-buy]="trade.decisionSide === 'BUY'" [class.side-sell]="trade.decisionSide === 'SELL'">
                {{ trade.decisionSide }}
              </span>
            </td>
            <td>{{ trade.status }}</td>
            <td>$ {{ trade.entryFilledUsd | number:'1.2-2' }}</td>
            <td>$ {{ trade.exitFilledUsd | number:'1.2-2' }}</td>
            <td [class.positive]="trade.finalPnlUsd > 0" [class.negative]="trade.finalPnlUsd < 0">
              $ {{ trade.finalPnlUsd | number:'1.2-2' }}
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

    .slug { font-weight: 600; }
    .question { font-size: 12px; color: #6b7280; }

    .side { font-weight: 700; font-size: 12px; }
    .side-buy { color: #10b981; }
    .side-sell { color: #3b82f6; }

    .positive { color: #10b981; font-weight: 600; }
    .negative { color: #ef4444; font-weight: 600; }
  `
})
export class Trades implements OnInit {
  private apiService = inject(ApiService);
  trades = signal<TradeSummaryResponse[]>([]);

  ngOnInit() {
    this.apiService.getTrades({ limit: 100 }).subscribe(trades => {
      this.trades.set(trades);
    });
  }
}
