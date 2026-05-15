import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { 
  BotConfigResponse, 
  BotCreateRequest,
  DashboardOptionsResponse, 
  MarketDetailResponse,
  MarketSummaryResponse, 
  RuntimeStatusResponse,
  StrategyCatalogResponse,
  TradeDetailResponse,
  TradeSummaryResponse,
  TradeOrderResponse,
  TradeOrderDetailResponse
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

  createBot(request: BotCreateRequest): Observable<BotConfigResponse> {
    return this.http.post<BotConfigResponse>(`${this.baseUrl}/bots`, request);
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

  getMarket(id: string): Observable<MarketDetailResponse> {
    return this.http.get<MarketDetailResponse>(`${this.baseUrl}/markets/${id}`);
  }

  getTrades(params: any = {}): Observable<TradeSummaryResponse[]> {
    return this.http.get<TradeSummaryResponse[]>(`${this.baseUrl}/trades`, { params });
  }

  getTrade(id: number): Observable<TradeDetailResponse> {
    return this.http.get<TradeDetailResponse>(`${this.baseUrl}/trades/${id}`);
  }

  getOrders(params: any = {}): Observable<TradeOrderResponse[]> {
    return this.http.get<TradeOrderResponse[]>(`${this.baseUrl}/orders`, { params });
  }

  getOrder(id: number): Observable<TradeOrderDetailResponse> {
    return this.http.get<TradeOrderDetailResponse>(`${this.baseUrl}/orders/${id}`);
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
