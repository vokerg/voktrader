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
      <div class="header-actions">
        <label class="filter-toggle">
          <input
            type="checkbox"
            [checked]="activeOnly()"
            (change)="setActiveOnly($event)"
          />
          Active only
        </label>
        <button class="btn btn-outline-danger" (click)="killAllBots()">Kill All</button>
        <button class="btn btn-primary">Create Bot</button>
      </div>
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
          <tr *ngFor="let bot of filteredBots()">
            <td>
              <div class="bot-name">{{ bot.name }}</div>
              <div class="bot-id">ID: {{ bot.id }}</div>
            </td>
            <td>{{ bot.marketFamily }}</td>
            <td>
              <div class="strategy-id">{{ bot.strategyId }}</div>
              <div class="strategy-meta">Set: {{ bot.strategySetId || '-' }}</div>
              <div class="strategy-meta" *ngIf="bot.subStrategyId">Sub: {{ bot.subStrategyId }}</div>
            </td>
            <td>
              <span class="badge" [class.badge-success]="bot.enabled" [class.badge-gray]="!bot.enabled">
                {{ bot.enabled ? 'ENABLED' : 'DISABLED' }}
              </span>
            </td>
            <td>
              <span class="status-dot" [class.status-active]="bot.runtimeActive"></span>
              {{ bot.status }}
              <div class="strategy-meta">
                Live set:
                <span [class.positive]="bot.runtimeIncluded" [class.negative]="!bot.runtimeIncluded">
                  {{ bot.runtimeIncludeGuardActive ? (bot.runtimeIncluded ? 'included' : 'excluded') : 'all included' }}
                </span>
              </div>
            </td>
            <td class="actions">
              <button *ngIf="bot.enabled" (click)="toggleBot(bot)" class="btn btn-sm btn-outline-danger">Pause</button>
              <button *ngIf="!bot.enabled" (click)="toggleBot(bot)" class="btn btn-sm btn-outline-success">Resume</button>
              <button
                *ngIf="bot.runtimeIncludeGuardActive && !bot.runtimeIncluded"
                (click)="includeRuntime(bot)"
                class="btn btn-sm btn-outline-success"
              >
                Include
              </button>
              <button
                *ngIf="bot.runtimeIncludeGuardActive && bot.runtimeIncluded"
                (click)="excludeRuntime(bot)"
                class="btn btn-sm btn-outline-danger"
              >
                Exclude
              </button>
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
      gap: 16px;
    }

    h1 {
      font-size: 24px;
      font-weight: 700;
    }

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

    .strategy-id {
      font-weight: 600;
      color: #111827;
    }

    .strategy-meta {
      margin-top: 2px;
      font-size: 12px;
      color: #6b7280;
      overflow-wrap: anywhere;
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
    .positive { color: #10b981; }
    .negative { color: #ef4444; }

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
  activeOnly = signal(false);

  ngOnInit() {
    this.loadBots();
  }

  loadBots() {
    this.apiService.getBots().subscribe(bots => {
      this.bots.set(bots);
    });
  }

  filteredBots() {
    return this.activeOnly()
      ? this.bots().filter(bot => bot.enabled || bot.runtimeActive)
      : this.bots();
  }

  setActiveOnly(event: Event) {
    const input = event.target as HTMLInputElement;
    this.activeOnly.set(input.checked);
  }

  toggleBot(bot: BotConfigResponse) {
    if (bot.enabled) {
      this.apiService.pauseBot(bot.id).subscribe(() => this.loadBots());
    } else {
      this.apiService.resumeBot(bot.id).subscribe(() => this.loadBots());
    }
  }

  includeRuntime(bot: BotConfigResponse) {
    this.apiService.includeBotRuntime(bot.id).subscribe(() => this.loadBots());
  }

  excludeRuntime(bot: BotConfigResponse) {
    const confirmed = window.confirm(`Remove bot ${bot.id} from the live runtime include set?`);
    if (!confirmed) {
      return;
    }

    this.apiService.excludeBotRuntime(bot.id).subscribe(() => this.loadBots());
  }

  killAllBots() {
    const confirmed = window.confirm('Kill all bots? This pauses every bot and stops running bot runtimes.');
    if (!confirmed) {
      return;
    }

    this.apiService.killAllBots().subscribe(bots => {
      this.bots.set(bots);
    });
  }
}
