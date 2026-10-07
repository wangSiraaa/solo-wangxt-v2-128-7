import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SellTrialReport } from './models';

/**
 * 只读卖出试算（与当前持仓并列展示，标明“非正式入账”）：
 *  - 只从截至试算结算点的事件规则在内存中重放，预计 FIFO 批次结转、
 *    剩余整股/零碎股、结转成本与预计损益；
 *  - 不追加事件、不推进游标、不发布快照，连续试算账本数字不变；
 *  - 超卖时给出缺口而不产生负批次；未结算在途买入单独提示。
 */
@Component({
  selector: 'app-sell-trial',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="panel trial-panel">
      <h2>只读卖出试算
        <span class="badge calc" style="margin-left:8px">非正式入账</span>
      </h2>
      <p class="muted" style="margin-top:-4px">
        课堂预演：不追加事件、不推进游标、不发布快照；结果仅按截至结算点的现有规则计算。
      </p>
      <div class="row">
        <div class="field">
          <label>证券</label>
          <input [(ngModel)]="instrument" style="width:90px" placeholder="AAA">
        </div>
        <div class="field">
          <label>业务日</label>
          <input type="date" [(ngModel)]="businessDate">
        </div>
        <div class="field">
          <label>结算日</label>
          <input type="date" [(ngModel)]="settlementDate">
        </div>
        <div class="field">
          <label>股数</label>
          <input [(ngModel)]="qty" style="width:100px" placeholder="150.5">
        </div>
        <div class="field">
          <label>成交价</label>
          <input [(ngModel)]="price" style="width:100px" placeholder="4.00">
        </div>
        <div class="field">
          <label>佣金（可选）</label>
          <input [(ngModel)]="commission" style="width:90px" placeholder="0">
        </div>
        <button class="primary" (click)="run()">试算</button>
      </div>

      <div *ngIf="report">
        <div class="spacer"></div>
        <div class="alert" [class.bad]="report.oversold" [class.ok]="!report.oversold">
          <span class="badge calc">非正式入账 · PROFORMA</span>
          证券 <span class="mono">{{ report.instrument }}</span>
          · 业务日 {{ report.businessDate }} · 结算日 {{ report.settlementDate }}
          <span *ngIf="report.pendingSplitRatio !== '1'" class="muted">
            （在途拆股连乘 {{ report.pendingSplitRatio }}：
            {{ report.requestedQty }} → 结算 {{ report.settledQty }} 股，
            复权价 {{ report.adjustedPrice }}）
          </span>
          <div *ngIf="report.oversold" style="margin-top:6px">
            <strong class="diff-bad">超卖缺口 {{ report.shortfallQty }} 股：</strong>
            可卖 {{ report.sellableQty }} 股，本次只能结转 {{ report.coveredQty }} 股；
            缺口部分不产生收入，也不产生负批次。
          </div>
          <div *ngFor="let p of report.unsettled" style="margin-top:6px" class="muted">
            在途买入 #{{ p.eventId }}（{{ p.businessDate }} 成交、{{ p.settlementDate }} 结算、
            {{ p.quantity }} 股）在试算结算点尚未交割，不可提前卖出。
          </div>
        </div>

        <div class="stat-grid">
          <div class="stat">
            <div class="k">可卖数量（整股+零碎）</div>
            <div class="v">{{ report.sellableQty }}</div>
          </div>
          <div class="stat">
            <div class="k">本次结转</div>
            <div class="v">{{ report.coveredQty }}</div>
          </div>
          <div class="stat">
            <div class="k">结转成本</div>
            <div class="v">{{ report.costReleased }}</div>
          </div>
          <div class="stat">
            <div class="k">预计净收入</div>
            <div class="v">{{ report.netProceeds }}</div>
          </div>
          <div class="stat">
            <div class="k">预计实现损益</div>
            <div class="v" [class.diff-ok]="+report.realizedPnl >= 0"
              [class.diff-bad]="+report.realizedPnl < 0">{{ report.realizedPnl }}</div>
          </div>
          <div class="stat">
            <div class="k">毛收入 / 佣金</div>
            <div class="v" style="font-size:15px">
              {{ report.grossProceeds }} / {{ report.commission }}</div>
          </div>
          <div class="stat">
            <div class="k">剩余整股（试算后）</div>
            <div class="v">{{ report.after.wholeQty }}</div>
          </div>
          <div class="stat">
            <div class="k">剩余零碎股（试算后）</div>
            <div class="v">{{ report.after.fractionalQty }}</div>
          </div>
        </div>

        <div class="spacer"></div>
        <h3 style="font-size:13px; margin:6px 0">试算前 → 试算后持仓（非正式）</h3>
        <table>
          <thead>
            <tr>
              <th>证券</th><th class="num">整股前</th><th class="num">整股后</th>
              <th class="num">零碎股前</th><th class="num">零碎股后</th>
              <th class="num">剩余成本前</th><th class="num">剩余成本后</th>
              <th class="num">单位成本后</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td class="mono">{{ report.after.instrument }}</td>
              <td class="num">{{ report.before.wholeQty }}</td>
              <td class="num">{{ report.after.wholeQty }}</td>
              <td class="num">{{ report.before.fractionalQty }}</td>
              <td class="num">{{ report.after.fractionalQty }}</td>
              <td class="num">{{ report.before.remainingCost }}</td>
              <td class="num">{{ report.after.remainingCost }}</td>
              <td class="num">{{ report.after.avgCost }}</td>
            </tr>
          </tbody>
        </table>

        <div class="spacer"></div>
        <h3 style="font-size:13px; margin:6px 0">预计 FIFO 批次结转</h3>
        <table>
          <thead>
            <tr>
              <th>批次键</th><th>来源</th><th class="num">结转数量</th>
              <th class="num">释放成本</th><th class="num">分摊收入</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let t of report.consumptions">
              <td class="mono">{{ t.lotKey }}</td>
              <td><span class="badge"
                [class.trade]="t.sourceEventType==='TRADE'"
                [class.rights]="t.sourceEventType==='RIGHTS_OFFER'"
                [class.split]="t.sourceEventType==='STOCK_SPLIT'">
                #{{ t.openingEventId }} · {{ sourceLabel(t.sourceEventType) }}
              </span></td>
              <td class="num">{{ t.qty }}</td>
              <td class="num">{{ t.costReleased }}</td>
              <td class="num">{{ t.proceeds }}</td>
            </tr>
            <tr *ngIf="!report.consumptions.length">
              <td colspan="5" class="muted">没有可结转批次（缺口 {{ report.shortfallQty }} 股）。</td>
            </tr>
          </tbody>
        </table>

        <div class="spacer"></div>
        <h3 style="font-size:13px; margin:6px 0">试算后批次（仅内存，账本不变）</h3>
        <table>
          <thead>
            <tr>
              <th>批次键</th><th>状态</th><th>零碎</th>
              <th class="num">剩余数量前</th><th class="num">剩余数量后</th>
              <th class="num">剩余成本前</th><th class="num">剩余成本后</th>
              <th>衍生自</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let l of report.lotChanges">
              <td class="mono">{{ l.lotKey }}</td>
              <td>
                <span *ngIf="l.closed" class="badge bad">试算后结清</span>
                <span *ngIf="l.newLot && !l.closed" class="badge calc">试算拆出</span>
                <span *ngIf="!l.newLot && !l.closed" class="badge ok">部分结转</span>
              </td>
              <td>
                <span *ngIf="l.fractional" class="badge frac">零碎股</span>
                <span *ngIf="!l.fractional" class="muted">整股</span>
              </td>
              <td class="num">{{ l.remainingQtyBefore }}</td>
              <td class="num">{{ l.remainingQtyAfter }}</td>
              <td class="num">{{ l.remainingCostBefore }}</td>
              <td class="num">{{ l.remainingCostAfter }}</td>
              <td class="mono muted">{{ l.derivedFromLotKey || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `
})
export class SellTrialComponent {
  @Input() instrument = '';
  @Input() businessDate = '';
  @Input() settlementDate = '';
  qty = '150.5';
  price = '4.00';
  commission = '0';

  @Input() report: SellTrialReport | null = null;
  @Output() trial = new EventEmitter<{
    instrument: string; businessDate: string; settlementDate: string;
    qty: number; price: number; commission: number;
  }>();

  run(): void {
    const q = Number(this.qty);
    const p = Number(this.price);
    const c = Number(this.commission || '0');
    if (!this.instrument.trim() || !this.businessDate || !this.settlementDate
      || !(q > 0) || !(p >= 0)) {
      // 后端同样会校验；这里避免发出无意义请求
      return;
    }
    this.trial.emit({
      instrument: this.instrument.trim(), businessDate: this.businessDate,
      settlementDate: this.settlementDate, qty: q, price: p, commission: c
    });
  }

  sourceLabel(t: string): string {
    return { TRADE: '成交买入', RIGHTS_OFFER: '配股到账', STOCK_SPLIT: '拆股衍生' }[t] || t;
  }
}
