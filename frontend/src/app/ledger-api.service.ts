import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  BusinessEvent, CashEntry, Checkpoint, Cursor, Entitlement, Lot,
  ReconciliationReport
} from './models';

@Injectable({ providedIn: 'root' })
export class LedgerApi {
  private http = inject(HttpClient);
  private base = 'http://localhost:8080/api';

  timeline(account: string, from: string, to: string): Observable<BusinessEvent[]> {
    return this.http.get<BusinessEvent[]>(
      `${this.base}/accounts/${account}/timeline`, { params: { from, to } });
  }

  lots(account: string): Observable<Lot[]> {
    return this.http.get<Lot[]>(`${this.base}/accounts/${account}/lots`);
  }

  cash(account: string): Observable<{ entries: CashEntry[]; balance: string }> {
    return this.http.get<{ entries: CashEntry[]; balance: string }>(
      `${this.base}/accounts/${account}/cash`);
  }

  entitlements(account: string): Observable<Entitlement[]> {
    return this.http.get<Entitlement[]>(`${this.base}/accounts/${account}/entitlements`);
  }

  checkpoints(account: string): Observable<Checkpoint[]> {
    return this.http.get<Checkpoint[]>(`${this.base}/accounts/${account}/checkpoints`);
  }

  cursor(account: string): Observable<Cursor> {
    return this.http.get<Cursor>(`${this.base}/accounts/${account}/cursor`);
  }

  replay(account: string, fullRebuild = false): Observable<unknown> {
    return this.http.post(`${this.base}/accounts/${account}/replay`, null,
      { params: { fullRebuild } });
  }

  prepare(account: string, date: string): Observable<unknown> {
    return this.http.post(`${this.base}/accounts/${account}/eod/${date}/prepare`, null);
  }

  verify(account: string, date: string): Observable<ReconciliationReport> {
    return this.http.post<ReconciliationReport>(
      `${this.base}/accounts/${account}/eod/${date}/verify`, null);
  }

  publish(account: string, date: string): Observable<ReconciliationReport> {
    return this.http.post<ReconciliationReport>(
      `${this.base}/accounts/${account}/eod/${date}/publish`, null);
  }

  importFile(file: File, sourceSystem: string): Observable<unknown> {
    const form = new FormData();
    form.append('file', file);
    form.append('sourceSystem', sourceSystem);
    return this.http.post(`${this.base}/imports`, form);
  }

  uploadStatement(body: Record<string, unknown>): Observable<string> {
    return this.http.post(`${this.base}/statements`, body, { responseType: 'text' });
  }
}
