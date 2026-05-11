import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { BotConfigResponse } from '../../models/api.models';

@Component({
  selector: 'app-bots',
  imports: [CommonModule],
  template: `
    <div class="header">
      <h1>Trading Bots</h1>
      <button class="btn btn-primary">Create Bot</button>
    </div>

    <div class="card">
      <table>
        <thead>
          <tr>
            <th>Name</th>
            <th>Market Family</th>
            <th>Strategy</th>
            <th>Status</th>
            <th>Runtime</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let bot of bots()">
            <td>
              <div class="bot-name">{{ bot.name }}</div>
              <div class="bot-id">ID: {{ bot.id }}</div>
            </td>
            <td>{{ bot.marketFamily }}</td>
            <td>{{ bot.strategyId }}</td>
            <td>
              <span class="badge" [class.badge-success]="bot.enabled" [class.badge-gray]="!bot.enabled">
                {{ bot.enabled ? 'ENABLED' : 'DISABLED' }}
              </span>
            </td>
            <td>
              <span class="status-dot" [class.status-active]="bot.runtimeActive"></span>
              {{ bot.status }}
            </td>
            <td class="actions">
              <button *ngIf="bot.enabled" (click)="toggleBot(bot)" class="btn btn-sm btn-outline-danger">Pause</button>
              <button *ngIf="!bot.enabled" (click)="toggleBot(bot)" class="btn btn-sm btn-outline-success">Resume</button>
              <button class="btn btn-sm btn-outline">Edit</button>
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
    }

    h1 {
      font-size: 24px;
      font-weight: 700;
    }

    .card {
      background-color: #fff;
      border-radius: 8px;
      box-shadow: 0 1px 3px rgba(0,0,0,0.1);
      overflow: hidden;
    }

    table {
      width: 100%;
      border-collapse: collapse;
    }

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

    td {
      padding: 16px;
      border-bottom: 1px solid #f3f4f6;
      font-size: 14px;
    }

    .bot-name {
      font-weight: 600;
      color: #111827;
    }

    .bot-id {
      font-size: 12px;
      color: #6b7280;
    }

    .badge {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 9999px;
      font-size: 11px;
      font-weight: 600;
    }

    .badge-success { background-color: #def7ec; color: #03543f; }
    .badge-gray { background-color: #f3f4f6; color: #374151; }

    .status-dot {
      display: inline-block;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background-color: #d1d5db;
      margin-right: 4px;
    }

    .status-active { background-color: #10b981; }

    .btn {
      padding: 8px 16px;
      border-radius: 6px;
      font-size: 14px;
      font-weight: 500;
      cursor: pointer;
      border: 1px solid transparent;
      transition: all 0.2s;
    }

    .btn-sm {
      padding: 4px 8px;
      font-size: 12px;
    }

    .btn-primary {
      background-color: #3b82f6;
      color: #fff;
    }

    .btn-outline {
      background-color: transparent;
      border-color: #e5e7eb;
      color: #374151;
    }

    .btn-outline-success {
      background-color: transparent;
      border-color: #10b981;
      color: #10b981;
    }

    .btn-outline-danger {
      background-color: transparent;
      border-color: #ef4444;
      color: #ef4444;
    }

    .actions {
      display: flex;
      gap: 8px;
    }
  `
})
export class Bots implements OnInit {
  private apiService = inject(ApiService);
  bots = signal<BotConfigResponse[]>([]);

  ngOnInit() {
    this.loadBots();
  }

  loadBots() {
    this.apiService.getBots().subscribe(bots => {
      this.bots.set(bots);
    });
  }

  toggleBot(bot: BotConfigResponse) {
    if (bot.enabled) {
      this.apiService.pauseBot(bot.id).subscribe(() => this.loadBots());
    } else {
      this.apiService.resumeBot(bot.id).subscribe(() => this.loadBots());
    }
  }
}
