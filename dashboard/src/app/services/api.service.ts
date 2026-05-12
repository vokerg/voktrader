import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { 
  BotConfigResponse, 
  DashboardOptionsResponse, 
  MarketSummaryResponse, 
  RuntimeStatusResponse,
  StrategyCatalogResponse,
  TradeSummaryResponse 
} from '../models/api.models';

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  private http = inject(HttpClient);
  private baseUrl = '/api';

  getBots(): Observable<BotConfigResponse[]> {
    return this.http.get<BotConfigResponse[]>(`${this.baseUrl}/bots`);
  }

  getDashboardOptions(): Observable<DashboardOptionsResponse> {
    return this.http.get<DashboardOptionsResponse>(`${this.baseUrl}/dashboard/options`);
  }

  getRuntimeStatus(): Observable<RuntimeStatusResponse> {
    return this.http.get<RuntimeStatusResponse>(`${this.baseUrl}/runtime/status`);
  }

  getStrategies(): Observable<StrategyCatalogResponse> {
    return this.http.get<StrategyCatalogResponse>(`${this.baseUrl}/strategies`);
  }

  getMarkets(params: any = {}): Observable<MarketSummaryResponse[]> {
    return this.http.get<MarketSummaryResponse[]>(`${this.baseUrl}/markets`, { params });
  }

  getTrades(params: any = {}): Observable<TradeSummaryResponse[]> {
    return this.http.get<TradeSummaryResponse[]>(`${this.baseUrl}/trades`, { params });
  }

  pauseBot(id: number): Observable<BotConfigResponse> {
    return this.http.post<BotConfigResponse>(`${this.baseUrl}/bots/${id}/pause`, {});
  }

  resumeBot(id: number): Observable<BotConfigResponse> {
    return this.http.post<BotConfigResponse>(`${this.baseUrl}/bots/${id}/resume`, {});
  }

  includeBotRuntime(id: number): Observable<BotConfigResponse> {
    return this.http.post<BotConfigResponse>(`${this.baseUrl}/bots/${id}/runtime-include`, {});
  }

  excludeBotRuntime(id: number): Observable<BotConfigResponse> {
    return this.http.post<BotConfigResponse>(`${this.baseUrl}/bots/${id}/runtime-exclude`, {});
  }

  killAllBots(): Observable<BotConfigResponse[]> {
    return this.http.post<BotConfigResponse[]>(`${this.baseUrl}/bots/kill-all`, {});
  }
}
