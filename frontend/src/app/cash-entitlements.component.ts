import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { CashEntry, Entitlement } from './models';

/** 现金权益：现金行只在结算日/支付日入账；权益展示登记日资格与支付状态。 */
@Component({
  selector: 'app-cash-entitlements',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="panel">
      <h2>权益（登记日资格 → 支付日入账）</h2>
      <table>
        <thead>
          <tr>
            <th>事件</th><th>证券</th><th>类型</th><th>登记日</th>
            <th class="num">资格/权数</th><th class="num">每股/认购价</th>
            <th class="num">金额</th><th>状态</th><th class="num">已认购</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let e of entitlements">
            <td class="num mono">#{{ e.eventId }}</td>
            <td class="mono">{{ e.instrument }}</td>
            <td><span class="badge" [class.dividend]="e.kind==='CASH_DIVIDEND'"
              [class.rights]="e.kind==='RIGHTS'">
              {{ e.kind === 'CASH_DIVIDEND' ? '现金分红' : '配股' }}</span></td>
            <td>{{ e.recordDate }}</td>
            <td class="num">{{ e.eligibleQty }}</td>
            <td class="num">{{ e.amountPerShare }}</td>
            <td class="num">{{ e.grossAmount }}</td>
            <td><span class="badge" [ngClass]="statusClass(e.status)">{{ statusLabel(e.status) }}</span></td>
            <td class="num">{{ e.subscribedQty }}</td>
          </tr>
          <tr *ngIf="!entitlements.length"><td colspan="9" class="muted">暂无权益。</td></tr>
        </tbody>
      </table>
    </div>

    <div class="panel">
      <h2>现金账（结算日 / 支付日才入账）<span class="muted" style="font-weight:400">
        余额 {{ balance }}</span></h2>
      <table>
        <thead>
          <tr>
            <th>事件</th><th>交易日</th><th>值日(结算/支付)</th>
            <th>类别</th><th>方向</th><th class="num">金额</th><th>幂等键</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let c of cash">
            <td class="num mono">#{{ c.eventId }}</td>
            <td>{{ c.businessDate }}</td>
            <td>{{ c.valueDate }}</td>
            <td>{{ categoryLabel(c.category) }}</td>
            <td><span class="badge" [class.ok]="c.direction==='IN'"
              [class.bad]="c.direction==='OUT'">{{ c.direction === 'IN' ? '流入' : '流出' }}</span></td>
            <td class="num" [class.diff-ok]="c.direction==='IN'"
              [class.diff-bad]="c.direction==='OUT'">
              {{ c.direction === 'IN' ? '+' : '−' }}{{ c.amount }}
            </td>
            <td class="mono muted">{{ c.idemKey }}</td>
          </tr>
          <tr *ngIf="!cash.length"><td colspan="7" class="muted">暂无现金行。</td></tr>
        </tbody>
      </table>
    </div>
  `
})
export class CashEntitlementsComponent {
  @Input() cash: CashEntry[] = [];
  @Input() entitlements: Entitlement[] = [];
  @Input() balance = '0.00';

  categoryLabel(c: string): string {
    return ({
      TRADE_BUY: '买券价款', TRADE_SELL: '卖券收入', COMMISSION: '佣金',
      DIVIDEND: '现金分红', RIGHTS_PAYMENT: '配股缴款'
    } as Record<string, string>)[c] || c;
  }

  statusLabel(s: string): string {
    return ({
      CALCULATED: '已计算未入账', PAID: '已支付', EXPIRED: '过期未认购',
      SUBSCRIBED: '已认购', PARTIALLY_SUBSCRIBED: '部分认购'
    } as Record<string, string>)[s] || s;
  }

  statusClass(s: string): string {
    if (s === 'PAID' || s === 'SUBSCRIBED') return 'ok';
    if (s === 'EXPIRED') return 'bad';
    return 'calc';
  }
}
