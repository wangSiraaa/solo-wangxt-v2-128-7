import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BusinessEvent } from './models';

/**
 * 事件时间轴：不可变事实流。每一行展示该事件参与资格/入账的业务日：
 * 交易日、结算日（交割）、除权日、登记日（资格）、支付日（现金）、到账日（配股）。
 */
@Component({
  selector: 'app-timeline',
  standalone: true,
  imports: [CommonModule],
  template: `
    <table>
      <thead>
        <tr>
          <th>#</th><th>类型</th><th>证券</th><th>交易日/除权日</th>
          <th>结算日</th><th>登记日</th><th>支付日</th><th>到账日</th>
          <th>关键数据</th><th class="num">来源</th>
        </tr>
      </thead>
      <tbody>
        <tr *ngFor="let e of events">
          <td class="num mono">{{ e.id }}</td>
          <td>
            <span class="badge" [class.trade]="e.eventType==='TRADE'"
              [class.split]="e.eventType==='STOCK_SPLIT'"
              [class.dividend]="e.eventType==='CASH_DIVIDEND'"
              [class.rights]="e.eventType==='RIGHTS_OFFER'">{{ label(e.eventType) }}</span>
            <span *ngIf="e.late" class="badge late" style="margin-left:6px">迟到</span>
          </td>
          <td class="mono">{{ e.instrument }}</td>
          <td>{{ e.businessDate }}</td>
          <td>{{ e.settlementDate || '—' }}</td>
          <td>{{ e.recordDate || '—' }}</td>
          <td>{{ e.paymentDate || '—' }}</td>
          <td>{{ e.allotmentDate || '—' }}</td>
          <td class="mono">{{ detail(e) }}</td>
          <td class="num muted" style="white-space:nowrap">
            {{ e.sourceSystem }}<br><span class="mono">{{ e.sourceKey }}</span>
          </td>
        </tr>
        <tr *ngIf="!events.length"><td colspan="10" class="muted">该区间没有事件。</td></tr>
      </tbody>
    </table>
  `
})
export class TimelineComponent {
  @Input() events: BusinessEvent[] = [];

  label(t: string): string {
    return { TRADE: '成交', STOCK_SPLIT: '拆股', CASH_DIVIDEND: '分红', RIGHTS_OFFER: '配股' }[t] || t;
  }

  detail(e: BusinessEvent): string {
    const p = e.payload as any;
    switch (e.eventType) {
      case 'TRADE':
        return `${p.side === 'BUY' ? '买' : '卖'} ${p.quantity} @ ${p.price}`
          + (p.commission && p.commission !== '0' ? ` 佣 ${p.commission}` : '');
      case 'STOCK_SPLIT':
        return `比例 ×${p.ratio}`;
      case 'CASH_DIVIDEND':
        return `每股 ${p.amountPerShare}`;
      case 'RIGHTS_OFFER':
        return `每股权 ${p.rightsPerShare} @ ${p.subscriptionPrice} 认购 ${p.subscribedQty ?? 0}`;
      default:
        return '';
    }
  }
}
