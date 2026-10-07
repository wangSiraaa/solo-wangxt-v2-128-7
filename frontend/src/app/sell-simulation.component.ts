import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LedgerApi } from './ledger-api.service';
import { Lot, SellSimulation } from './models';

interface CurrentPosition {
  instrument: string;
  qty: number;
  frac: number;
  cost: number;
}

/**
 * 卖出试算（只读 what-if）：
 *  - 输入账户/证券/业务日/结算日/股数/成交价，后端按现有事件规则折叠到试算结算点，
 *    返回预计批次消耗、剩余整股/零碎股、结转成本与预计损益；
 *  - 结果与当前持仓并列展示，并标明“非正式入账”；
 *  - 试算不追加事件、不推进游标、不发布快照，可反复试算。
 */
@Component({
  selector: 'app-sell-simulation',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="row">
      <div class="field"><label>证券</label>
        <input [(ngModel)]="instrument" style="width:90px"></div>
      <div class="field"><label>业务日（交易日）</label>
        <input type="date" [(ngModel)]="tradeDate"></div>
      <div class="field"><label>结算日</label>
        <input type="date" [(ngModel)]="settlementDate"></div>
      <div class="field"><label>股数</label>
        <input [(ngModel)]="quantity" type="number" min="0" step="any" style="width:110px"></div>
      <div class="field"><label>成交价</label>
        <input [(ngModel)]="price" type="number" min="0" step="any" style="width:110px"></div>
      <button class="primary" [disabled]="loading" (click)="run()">试算</button>
      <span class="badge calc">非正式入账</span>
      <span class="muted" style="font-size:12px">
        只读试算：不追加事件、不推进游标、不发布快照，可反复试算。</span>
    </div>

    <div *ngIf="error" class="alert bad" style="margin-top:12px">{{ error }}</div>

    <ng-container *ngIf="result as r">
      <div class="alert" [class.ok]="r.feasible" [class.bad]="!r.feasible"
           style="margin-top:12px">
        <span class="badge calc">非正式入账 · 试算结果</span>
        <ng-container *ngIf="r.feasible">
          足额可卖：结算数量 {{ r.settledQty }} 股（在册可卖 {{ r.availableQty }}）。
        </ng-container>
        <ng-container *ngIf="!r.feasible">
          超出可卖数量：缺口 <b>{{ r.shortfall }}</b> 股
          （请求 {{ r.settledQty }}，在册可卖 {{ r.availableQty }}，
          以下仅按 {{ r.simulatedQty }} 股试算，不产生负批次）。
        </ng-container>
      </div>

      <div class="stat-grid" style="margin-top:12px">
        <div class="stat"><div class="k">结算数量（折算后）</div>
          <div class="v">{{ r.settledQty }}</div></div>
        <div class="stat"><div class="k">复权价（在途拆股比例 {{ r.pendingSplitRatio }}）</div>
          <div class="v">{{ r.adjustedPrice }}</div></div>
        <div class="stat"><div class="k">结转成本</div>
          <div class="v">{{ r.costReleased }}</div></div>
        <div class="stat"><div class="k">预计净收入</div>
          <div class="v">{{ r.proceeds }}</div></div>
        <div class="stat"><div class="k">预计损益</div>
          <div class="v" [class.diff-ok]="pos(r.expectedPnl)"
               [class.diff-bad]="!pos(r.expectedPnl)">{{ r.expectedPnl }}</div></div>
      </div>

      <h3>预计批次消耗（FIFO）</h3>
      <table>
        <thead>
          <tr><th>批次键</th><th class="num">消耗数量</th>
            <th class="num">结转成本</th><th class="num">分摊收入</th></tr>
        </thead>
        <tbody>
          <tr *ngFor="let t of r.takes">
            <td class="mono">{{ t.lotKey }}</td>
            <td class="num">{{ t.qty }}</td>
            <td class="num">{{ t.costReleased }}</td>
            <td class="num">{{ t.proceeds }}</td>
          </tr>
          <tr *ngIf="!r.takes.length">
            <td colspan="4" class="muted">无可消耗批次（可卖数量为 0）。</td>
          </tr>
        </tbody>
      </table>

      <div class="compare">
        <div>
          <h3>当前持仓（正式投影）</h3>
          <table>
            <thead>
              <tr><th>证券</th><th class="num">整股</th>
                <th class="num">零碎股</th><th class="num">持仓成本</th></tr>
            </thead>
            <tbody>
              <tr *ngFor="let p of currentPositions">
                <td class="mono">{{ p.instrument }}</td>
                <td class="num">{{ p.qty }}</td>
                <td class="num">{{ p.frac }}</td>
                <td class="num">{{ p.cost.toFixed(2) }}</td>
              </tr>
              <tr *ngIf="!currentPositions.length">
                <td colspan="4" class="muted">暂无持仓。</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div>
          <h3>试算后持仓 <span class="badge calc">非正式入账</span></h3>
          <table>
            <thead>
              <tr><th>证券</th><th class="num">整股</th>
                <th class="num">零碎股</th><th class="num">持仓成本</th></tr>
            </thead>
            <tbody>
              <tr *ngFor="let p of r.positionsAfter">
                <td class="mono">{{ p.instrument }}</td>
                <td class="num">{{ p.qty }}</td>
                <td class="num">{{ p.fractionalQty }}</td>
                <td class="num">{{ p.openCost }}</td>
              </tr>
              <tr *ngIf="!r.positionsAfter.length">
                <td colspan="4" class="muted">试算后无持仓。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <h3>试算后批次（{{ r.instrument }}）<span class="badge calc">非正式入账</span></h3>
      <table>
        <thead>
          <tr><th>批次键</th><th class="num">剩余数量</th><th class="num">单位成本</th>
            <th class="num">剩余成本</th><th>零碎股</th><th>状态</th><th>衍生自</th></tr>
        </thead>
        <tbody>
          <tr *ngFor="let l of r.lotsAfter" [class.muted]="l.closed">
            <td class="mono">{{ l.lotKey }}</td>
            <td class="num">{{ l.remainingQty }}</td>
            <td class="num">{{ l.unitCost }}</td>
            <td class="num">{{ l.remainingCost }}</td>
            <td>
              <span *ngIf="l.fractional" class="badge frac">零碎股</span>
              <span *ngIf="!l.fractional" class="muted">整股</span>
            </td>
            <td><span *ngIf="l.closed" class="badge calc">已结清</span></td>
            <td class="mono muted">{{ l.derivedFromLotKey || '—' }}</td>
          </tr>
          <tr *ngIf="!r.lotsAfter.length">
            <td colspan="7" class="muted">该证券在试算结算点没有批次。</td>
          </tr>
        </tbody>
      </table>
    </ng-container>
  `,
  styles: [`
    h3 { font-size: 13px; margin: 16px 0 8px; font-weight: 600; }
    .compare { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    @media (max-width: 900px) { .compare { grid-template-columns: 1fr; } }
  `]
})
export class SellSimulationComponent {
  @Input() account = '';
  @Input() lots: Lot[] = [];

  instrument = 'AAA';
  tradeDate = '2026-08-11';
  settlementDate = '2026-08-12';
  quantity: number | null = null;
  price: number | null = null;

  result: SellSimulation | null = null;
  error = '';
  loading = false;

  constructor(private api: LedgerApi) {}

  /** 当前持仓：由正式投影批次聚合（与上方批次表同源），与试算后持仓并列。 */
  get currentPositions(): CurrentPosition[] {
    const byInstrument = new Map<string, CurrentPosition>();
    for (const l of this.lots) {
      if (l.closed) {
        continue;
      }
      let p = byInstrument.get(l.instrument);
      if (!p) {
        p = { instrument: l.instrument, qty: 0, frac: 0, cost: 0 };
        byInstrument.set(l.instrument, p);
      }
      if (l.fractional) {
        p.frac += Number(l.remainingQty);
      } else {
        p.qty += Number(l.remainingQty);
      }
      p.cost += Number(l.remainingCost);
    }
    return [...byInstrument.values()];
  }

  pos(v: string): boolean {
    return Number(v) >= 0;
  }

  run(): void {
    this.error = '';
    if (!this.instrument || !this.tradeDate || !this.settlementDate
        || !this.quantity || this.price === null) {
      this.error = '请完整填写证券、业务日、结算日、股数与成交价。';
      return;
    }
    this.loading = true;
    this.api.simulateSell(this.account, {
      instrument: this.instrument,
      tradeDate: this.tradeDate,
      settlementDate: this.settlementDate,
      quantity: Number(this.quantity),
      price: Number(this.price)
    }).subscribe({
      next: (r) => { this.result = r; this.loading = false; },
      error: (e) => {
        this.error = e?.error?.message || e?.message || String(e);
        this.loading = false;
      }
    });
  }
}
