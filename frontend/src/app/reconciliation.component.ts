import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReconciliationReport } from './models';

/**
 * 日终复核：
 *  - 数量、现金、批次成本三方守恒 + 账实核对结果；
 *  - 任一不平显示最早失配事件并禁用发布；
 *  - 复核确认后才允许生成正式视图。
 */
@Component({
  selector: 'app-reconciliation',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div *ngIf="report" class="panel">
      <h2>日终复核 · {{ report.businessDate }}
        <span class="muted" style="font-weight:400">水位事件 #{{ report.watermarkEventId }}</span>
      </h2>

      <div class="stat-grid">
        <div class="stat">
          <div class="k">数量守恒</div>
          <div class="v" [class.diff-ok]="report.qtyBalanced" [class.diff-bad]="!report.qtyBalanced">
            {{ report.qtyBalanced ? '平' : '不平' }}</div>
        </div>
        <div class="stat">
          <div class="k">现金守恒</div>
          <div class="v" [class.diff-ok]="report.cashBalanced" [class.diff-bad]="!report.cashBalanced">
            {{ report.cashBalanced ? '平' : '不平' }}</div>
        </div>
        <div class="stat">
          <div class="k">批次成本守恒</div>
          <div class="v" [class.diff-ok]="report.costBalanced" [class.diff-bad]="!report.costBalanced">
            {{ report.costBalanced ? '平' : '不平' }}</div>
        </div>
        <div class="stat">
          <div class="k">账实核对</div>
          <div class="v"
            [class.diff-ok]="report.bookVsExternalBalanced"
            [class.diff-bad]="!report.bookVsExternalBalanced">
            {{ report.bookVsExternalBalanced ? '相符' : '差异' }}</div>
        </div>
      </div>

      <div class="spacer"></div>

      <div *ngIf="!report.allBalanced" class="alert bad">
        <strong>发布已阻止。</strong>
        最早失配事件：<span class="mono">#{{ report.earliestMismatchEventId }}</span>
        <div class="mono" style="margin-top:6px">{{ report.mismatchDetail }}</div>
      </div>
      <div *ngIf="report.allBalanced" class="alert ok">
        三方守恒且账实相符，复核人员可确认发布正式视图。
      </div>

      <table>
        <thead>
          <tr>
            <th>证券</th>
            <th class="num">账面整股</th><th class="num">账面零碎股</th><th class="num">账面成本</th>
            <th class="num">外部整股</th><th class="num">外部零碎股</th><th class="num">外部成本</th>
            <th>数量</th><th>成本</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let l of report.positions">
            <td class="mono">{{ l.instrument }}</td>
            <td class="num">{{ l.projectedQty }}</td>
            <td class="num">{{ l.projectedFractionalQty }}</td>
            <td class="num">{{ l.projectedOpenCost }}</td>
            <td class="num">{{ l.externalQty }}</td>
            <td class="num">{{ l.externalFractionalQty }}</td>
            <td class="num">{{ l.externalCost }}</td>
            <td><span class="badge" [class.ok]="l.qtyMatch" [class.bad]="!l.qtyMatch">
              {{ l.qtyMatch ? '一致' : '差异' }}</span></td>
            <td><span class="badge" [class.ok]="l.costMatch" [class.bad]="!l.costMatch">
              {{ l.costMatch ? '一致' : '差异' }}</span></td>
          </tr>
          <tr *ngIf="!report.positions.length"><td colspan="9" class="muted">
            无对账单（仅内部三方守恒）。</td></tr>
        </tbody>
      </table>

      <div class="spacer"></div>
      <div class="row">
        <button class="primary" [disabled]="!report.allBalanced || published"
          (click)="publish.emit()">确认数量/现金/批次成本三方守恒并发布</button>
        <span *ngIf="published" class="diff-ok">正式视图已生成（快照只加速，历史仍以事件账本为准）</span>
      </div>
    </div>
  `
})
export class ReconciliationComponent {
  @Input() report: ReconciliationReport | null = null;
  @Input() published = false;
  @Output() publish = new EventEmitter<void>();
}
