/** 不可变业务事件（账本唯一事实源）。 */
export interface BusinessEvent {
  id: number;
  eventType: 'TRADE' | 'STOCK_SPLIT' | 'CASH_DIVIDEND' | 'RIGHTS_OFFER';
  accountId: string;
  instrument: string;
  businessDate: string;
  settlementDate: string | null;
  recordDate: string | null;
  paymentDate: string | null;
  allotmentDate: string | null;
  payload: Record<string, unknown>;
  sourceSystem: string;
  sourceKey: string;
  idempotencyKey: string;
  late: boolean;
  ingestedAt: string;
}

/** 成本批次（FIFO），含来源链与零碎股标记。 */
export interface Lot {
  id: number;
  lotKey: string;
  instrument: string;
  openingEventId: number;
  sourceEventType: string;
  acquiredDate: string;
  openQty: string;
  remainingQty: string;
  unitCost: string;
  totalCost: string;
  remainingCost: string;
  fractional: boolean;
  closed: boolean;
  derivedFromLotKey: string | null;
  adjustedByEventId: number | null;
}

export interface CashEntry {
  eventId: number;
  businessDate: string;
  valueDate: string;
  effectKey: string;
  direction: 'IN' | 'OUT';
  amount: string;
  category: string;
  idemKey: string;
}

export interface Entitlement {
  eventId: number;
  instrument: string;
  kind: string;
  recordDate: string;
  paymentDate: string | null;
  eligibleQty: string;
  amountPerShare: string | null;
  grossAmount: string | null;
  status: string;
  subscribedQty: string;
}

export interface Checkpoint {
  eventId: number;
  effectKey: string;
  businessDate: string;
  stage: string;
  sharesHash: string;
  cashBalance: string;
  openCost: string;
  realizedPnl: string;
}

export interface Cursor {
  lastEventId: number;
  lastBusinessDate: string | null;
  lastStage: string | null;
  lastEffectKey: string | null;
}

export interface ReconciliationLine {
  instrument: string;
  projectedQty: string;
  projectedFractionalQty: string;
  projectedOpenCost: string;
  externalQty: string;
  externalFractionalQty: string;
  externalCost: string;
  externalCash: string | null;
  qtyMatch: boolean;
  costMatch: boolean;
}

export interface ReconciliationReport {
  accountId: string;
  businessDate: string;
  watermarkEventId: number;
  qtyBalanced: boolean;
  cashBalanced: boolean;
  costBalanced: boolean;
  bookVsExternalBalanced: boolean;
  earliestMismatchEventId: number | null;
  mismatchDetail: string | null;
  allBalanced: boolean;
  positions: ReconciliationLine[];
  projectedCash: string;
  externalCash: string | null;
}

/** 只读卖出试算（非正式入账）。 */
export interface SellTrialTake {
  lotKey: string;
  openingEventId: number;
  sourceEventType: string;
  qty: string;
  costReleased: string;
  proceeds: string;
}

export interface SellTrialLot {
  lotKey: string;
  openingEventId: number;
  sourceEventType: string;
  acquiredDate: string;
  remainingQtyBefore: string;
  remainingCostBefore: string;
  remainingQtyAfter: string;
  remainingCostAfter: string;
  fractional: boolean;
  closed: boolean;
  newLot: boolean;
  derivedFromLotKey: string | null;
}

export interface SellTrialPending {
  eventId: number;
  businessDate: string;
  settlementDate: string;
  quantity: string;
}

export interface SellTrialPosition {
  instrument: string;
  wholeQty: string;
  fractionalQty: string;
  remainingCost: string;
  avgCost: string;
}

export interface SellTrialReport {
  accountId: string;
  instrument: string;
  businessDate: string;
  settlementDate: string;
  requestedQty: string;
  price: string;
  commission: string;
  pendingSplitRatio: string;
  settledQty: string;
  adjustedPrice: string;
  sellableQty: string;
  coveredQty: string;
  shortfallQty: string;
  oversold: boolean;
  grossProceeds: string;
  netProceeds: string;
  costReleased: string;
  realizedPnl: string;
  before: SellTrialPosition;
  after: SellTrialPosition;
  consumptions: SellTrialTake[];
  lotChanges: SellTrialLot[];
  unsettled: SellTrialPending[];
  booked: boolean;
  entryKind: 'PROFORMA_NOT_BOOKED';
}
