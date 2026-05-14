import { Component, signal, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { BotCreateRequest, DashboardOptionsResponse, StrategyCatalogResponse } from '../../models/api.models';

@Component({
  selector: 'app-bot-create',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule],
  template: `
    <div class="container">
      <div class="header">
        <a routerLink="/bots" class="back-link">← Back to Bots</a>
        <h1>Create New Bot</h1>
      </div>

      <div class="card">
        <form (ngSubmit)="onSubmit()" #botForm="ngForm">
          <div class="form-grid">
            <div class="form-group">
              <label for="name">Name (optional)</label>
              <input type="text" id="name" name="name" [(ngModel)]="bot.name" placeholder="e.g. My BTC Bot">
            </div>

            <div class="form-group">
              <label for="marketFamily">Market Family</label>
              <select id="marketFamily" name="marketFamily" [(ngModel)]="bot.marketFamily" required>
                <option [value]="null">Select Family...</option>
                <option *ngFor="let family of options()?.marketFamilies" [value]="family.id">
                  {{ family.id }} ({{ family.asset }} / {{ family.interval }})
                </option>
              </select>
            </div>

            <div class="form-group">
              <label for="strategyId">Strategy</label>
              <select id="strategyId" name="strategyId" [(ngModel)]="bot.strategyId" required>
                <option value="">Select Strategy...</option>
                <option *ngFor="let strategy of options()?.strategies" [value]="strategy.id">
                  {{ strategy.name }} ({{ strategy.id }})
                </option>
              </select>
            </div>

            <div class="form-group">
              <label for="strategySetId">Strategy Set (optional)</label>
              <select id="strategySetId" name="strategySetId" [(ngModel)]="bot.strategySetId">
                <option [value]="null">None</option>
                <option *ngFor="let setId of catalog()?.strategyV2SetIds" [value]="setId">{{ setId }}</option>
              </select>
            </div>

            <div class="form-group">
              <label for="subStrategyId">Sub Strategy (optional)</label>
              <input type="text" id="subStrategyId" name="subStrategyId" [(ngModel)]="bot.subStrategyId" placeholder="e.g. trend-follower">
            </div>

            <div class="form-group checkbox">
              <label>
                <input type="checkbox" name="enabled" [(ngModel)]="bot.enabled">
                Enabled
              </label>
            </div>
          </div>

          <div class="actions">
            <button type="button" routerLink="/bots" class="btn btn-secondary">Cancel</button>
            <button type="submit" [disabled]="!botForm.form.valid || submitting()" class="btn btn-primary">
              {{ submitting() ? 'Creating...' : 'Create Bot' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  `,
  styles: `
    .container { padding: 24px; max-width: 800px; margin: 0 auto; }
    .header { display: flex; align-items: center; gap: 16px; margin-bottom: 24px; }
    .back-link { text-decoration: none; color: #3b82f6; font-weight: 500; }
    h1 { font-size: 24px; font-weight: 700; margin: 0; }

    .card { background: #fff; border-radius: 8px; padding: 32px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }

    .form-grid { display: grid; grid-template-columns: 1fr; gap: 24px; }
    .form-group { display: flex; flex-direction: column; gap: 8px; }
    label { font-size: 14px; font-weight: 600; color: #374151; }
    input, select {
      padding: 10px 12px;
      border: 1px solid #d1d5db;
      border-radius: 6px;
      font-size: 14px;
      outline: none;
    }
    input:focus, select:focus { border-color: #3b82f6; ring: 2px solid #3b82f6; }

    .checkbox { flex-direction: row; align-items: center; gap: 12px; }
    .checkbox label { font-weight: 500; cursor: pointer; display: flex; align-items: center; gap: 8px; }
    .checkbox input { width: 18px; height: 18px; }

    .actions { margin-top: 32px; display: flex; justify-content: flex-end; gap: 12px; }
    .btn {
      padding: 10px 20px;
      border-radius: 6px;
      font-size: 14px;
      font-weight: 600;
      cursor: pointer;
      border: none;
      transition: all 0.2s;
    }
    .btn-primary { background: #3b82f6; color: white; }
    .btn-primary:hover { background: #2563eb; }
    .btn-primary:disabled { background: #93c5fd; cursor: not-allowed; }
    .btn-secondary { background: #f3f4f6; color: #374151; }
    .btn-secondary:hover { background: #e5e7eb; }
  `
})
export class BotCreate implements OnInit {
  private apiService = inject(ApiService);
  private router = inject(Router);

  options = signal<DashboardOptionsResponse | null>(null);
  catalog = signal<StrategyCatalogResponse | null>(null);
  submitting = signal(false);

  bot: BotCreateRequest = {
    name: null,
    marketFamily: null,
    asset: null,
    interval: null,
    strategyId: '',
    strategySetId: null,
    subStrategyId: null,
    enabled: true
  };

  ngOnInit() {
    this.apiService.getDashboardOptions().subscribe(options => this.options.set(options));
    this.apiService.getStrategies().subscribe(catalog => this.catalog.set(catalog));
  }

  onSubmit() {
    this.submitting.set(true);
    this.apiService.createBot(this.bot).subscribe({
      next: () => {
        this.router.navigate(['/bots']);
      },
      error: (err) => {
        console.error('Failed to create bot', err);
        this.submitting.set(false);
      }
    });
  }
}
