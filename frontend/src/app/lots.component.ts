import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Lot } from './models';

/**
 * 成本批次与来源链：
 *  - 每个批次标明来源事件（成交买入 / 配股到账 / 拆股衍生）；
 *  - 拆股衍生批次通过 derivedFromLotKey 指向父批次，回答“这批股从哪来”；
 *  - 零碎股(fractional)单列并高亮。
 */
@Component({
  selector: 'app-lots',
  standalone: true,
  imports: [CommonModule],
  template: `
    <table>
      <thead>
        <tr>
          <th>批次键</th><th>证券</th><th>来源</th><th>来源事件</th>
          <th>到账日</th><th class="num">原始数量</th><th class="num">剩余数量</th>
          <th class="num">单位成本</th><th class="num">剩余成本</th>
          <th>零碎股</th><th>衍生自</th>
        </tr>
      </thead>
      <tbody>
        <ng-container *ngFor="let l of lots">
          <tr [class.muted]="l.closed">
            <td class="mono">{{ l.lotKey }}</td>
            <td class="mono">{{ l.instrument }}</td>
            <td>
              <span class="badge" [class.trade]="l.sourceEventType==='TRADE'"
                [class.rights]="l.sourceEventType==='RIGHTS_OFFER'"
                [class.split]="l.sourceEventType==='STOCK_SPLIT'">
                {{ sourceLabel(l.sourceEventType) }}
              </span>
              <span *ngIf="l.closed" class="badge calc" style="margin-left:6px">已结清</span>
            </td>
            <td class="num mono">#{{ l.openingEventId }}</td>
            <td>{{ l.acquiredDate }}</td>
            <td class="num">{{ l.openQty }}</td>
            <td class="num">{{ l.remainingQty }}</td>
            <td class="num">{{ l.unitCost }}</td>
            <td class="num">{{ l.remainingCost }}</td>
            <td>
              <span *ngIf="l.fractional" class="badge frac">零碎股</span>
              <span *ngIf="!l.fractional" class="muted">整股</span>
            </td>
            <td class="mono muted">{{ l.derivedFromLotKey || '—' }}</td>
          </tr>
        </ng-container>
        <tr *ngIf="!lots.length"><td colspan="11" class="muted">还没有批次。先导入成交并执行重放。</td></tr>
      </tbody>
    </table>
  `
})
export class LotsComponent {
  @Input() lots: Lot[] = [];

  sourceLabel(t: string): string {
    return { TRADE: '成交买入', RIGHTS_OFFER: '配股到账', STOCK_SPLIT: '拆股衍生' }[t] || t;
  }
}
