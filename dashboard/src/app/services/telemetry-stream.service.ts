import { Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { TradingEvent, TelemetryConnectionState } from '../models/api.models';

@Injectable({ providedIn: 'root' })
export class TelemetryStreamService {
  readonly connectionState = signal<TelemetryConnectionState>('disconnected');

  stream(): Observable<TradingEvent> {
    return new Observable<TradingEvent>((observer) => {
      this.connectionState.set('connecting');

      const source = new EventSource('/api/telemetry/events/stream?replay=50');

      source.onopen = () => {
        this.connectionState.set('live');
      };

      source.addEventListener('trading-event', (message) => {
        try {
          const event = JSON.parse((message as MessageEvent).data) as TradingEvent;
          observer.next(event);
        } catch (err) {
          console.warn('Could not parse telemetry event', err);
        }
      });

      source.onerror = () => {
        this.connectionState.set('error');
      };

      return () => {
        source.close();
        this.connectionState.set('disconnected');
      };
    });
  }
}
