import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { 
  BotConfigResponse, 
  DashboardOptionsResponse, 
  MarketSummaryResponse, 
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
}
