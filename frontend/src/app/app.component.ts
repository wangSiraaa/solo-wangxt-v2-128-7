import { Component, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LedgerApi } from './ledger-api.service';
import {
  BusinessEvent, CashEntry, Checkpoint, Cursor, Entitlement, Lot,
  ReconciliationReport
} from './models';
import { TimelineComponent } from './timeline.component';
import { LotsComponent } from './lots.component';
import { CashEntitlementsComponent } from './cash-entitlements.component';
import { ReconciliationComponent } from './reconciliation.component';

type Tab = 'timeline' | 'lots' | 'cash' | 'eod';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule, FormsModule, TimelineComponent, LotsComponent,
    CashEntitlementsComponent, ReconciliationComponent
  ],
  templateUrl: './app.component.html'
})
export class AppComponent implements OnInit {
  constructor(private api: LedgerApi) {}

  account = 'PA1';
  sourceSystem = 'teaching-upload';
  from = '2026-08-01';
  to = '2026-12-31';
  eodDate = '2026-09-30';
  tab = signal<Tab>('timeline');

  events: BusinessEvent[] = [];
  lots: Lot[] = [];
  cash: CashEntry[] = [];
  entitlements: Entitlement[] = [];
  checkpoints: Checkpoint[] = [];
  cursor: Cursor | null = null;
  cashBalance = '0.00';
  report: ReconciliationReport | null = null;
  published = false;
  message = '';
  error = '';
  loading = false;

  // 对账单录入
  stmtInstrument = 'AAA';
  stmtQty = '';
  stmtFractional = '0';
  stmtCost = '';

  selectedFile: File | null = null;

  ngOnInit(): void {
    this.refreshAll();
  }

  setTab(t: Tab): void {
    this.tab.set(t);
  }

  onFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile = input.files?.[0] ?? null;
  }

  refreshAll(): void {
    this.loadTimeline();
    this.loadDerived();
  }

  loadTimeline(): void {
    this.api.timeline(this.account, this.from, this.to).subscribe({
      next: (v) => (this.events = v),
      error: (e) => this.fail(e)
    });
  }

  loadDerived(): void {
    this.api.lots(this.account).subscribe({ next: (v) => (this.lots = v), error: (e) => this.fail(e) });
    this.api.cash(this.account).subscribe({
      next: (v) => { this.cash = v.entries; this.cashBalance = v.balance; },
      error: (e) => this.fail(e)
    });
    this.api.entitlements(this.account).subscribe({
      next: (v) => (this.entitlements = v), error: (e) => this.fail(e)
    });
    this.api.checkpoints(this.account).subscribe({
      next: (v) => (this.checkpoints = v), error: (e) => this.fail(e)
    });
    this.api.cursor(this.account).subscribe({
      next: (v) => (this.cursor = v.lastEventId ? v : null), error: () => (this.cursor = null)
    });
  }

  doImport(): void {
    if (!this.selectedFile) {
      this.error = '请先选择 CSV 文件。';
      return;
    }
    this.loading = true;
    this.api.importFile(this.selectedFile, this.sourceSystem).subscribe({
      next: (r: any) => {
        this.message = `导入批次 ${r.batchId} 状态 ${r.status}`;
        this.loading = false;
        this.refreshAll();
      },
      error: (e) => { this.fail(e); this.loading = false; }
    });
  }

  doReplay(full: boolean): void {
    this.loading = true;
    this.api.replay(this.account, full).subscribe({
      next: () => {
        this.message = full ? '已清空派生表，从事件账本完整重放。'
          : '已从一致游标续放（崩溃恢复）。';
        this.loading = false;
        this.loadDerived();
      },
      error: (e) => { this.fail(e); this.loading = false; }
    });
  }

  doPrepare(): void {
    this.api.prepare(this.account, this.eodDate).subscribe({
      next: () => { this.message = `已建立 ${this.eodDate} 草稿并绑定事件水位。`; this.refreshAll(); },
      error: (e) => this.fail(e)
    });
  }

  doVerify(): void {
    this.published = false;
    this.api.verify(this.account, this.eodDate).subscribe({
      next: (r) => (this.report = r),
      error: (e) => this.fail(e)
    });
  }

  doPublish(): void {
    this.api.publish(this.account, this.eodDate).subscribe({
      next: (r) => { this.report = r; this.published = true; this.message = '正式视图已发布。'; },
      error: (e) => {
        this.published = false;
        this.fail(e);
        this.doVerify();
      }
    });
  }

  uploadStatement(): void {
    this.api.uploadStatement({
      accountId: this.account,
      businessDate: this.eodDate,
      instrument: this.stmtInstrument,
      externalQty: Number(this.stmtQty),
      externalFractionalQty: Number(this.stmtFractional || '0'),
      externalCost: this.stmtCost ? Number(this.stmtCost) : null
    }).subscribe({
      next: () => (this.message = '外部对账单已录入（仅用于账实核对，不反写历史）。'),
      error: (e) => this.fail(e)
    });
  }

  private fail(e: unknown): void {
    const err = e as { error?: { message?: string; earliestMismatchEventId?: number }; message?: string };
    this.error = err?.error?.message || err?.message || String(e);
    this.message = '';
  }
}
