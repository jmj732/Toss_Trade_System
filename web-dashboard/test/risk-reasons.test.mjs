import assert from "node:assert/strict";
import test from "node:test";

import { describeRiskReason } from "../lib/risk-reasons.js";

const REASONS = [
  "STALE_SNAPSHOT", "PARTIAL_SNAPSHOT", "CASH_UNKNOWN", "BUYING_POWER_EXCEEDED",
  "MAX_ORDER_AMOUNT_EXCEEDED", "MAX_QUANTITY_EXCEEDED", "CONCENTRATION_EXCEEDED",
  "ACCOUNT_OWNERSHIP_MISMATCH", "SELLABLE_QUANTITY_UNKNOWN", "SELLABLE_QUANTITY_INSUFFICIENT",
  "OPEN_ORDER_EXISTS", "KILL_SWITCH_ENGAGED", "KILL_SWITCH_STATE_UNAVAILABLE"
];

test("maps every PreTradeRiskEngine.Reason to a distinct Korean label", () => {
  const labels = REASONS.map(describeRiskReason);
  for (const [index, label] of labels.entries()) {
    // 원문 코드를 그대로 노출하지 않고 한국어 라벨을 준다.
    assert.notEqual(label, REASONS[index]);
    assert.doesNotMatch(label, /^[A-Z_]+$/);
    assert.doesNotMatch(label, /알 수 없는 사유/);
  }
  // 모든 라벨이 서로 다르다.
  assert.equal(new Set(labels).size, REASONS.length);
});

test("exposes the raw code for an unregistered reason instead of hiding it", () => {
  assert.equal(describeRiskReason("SOME_NEW_REASON"), "알 수 없는 사유: SOME_NEW_REASON");
});

test("passes through falsy input unchanged", () => {
  assert.equal(describeRiskReason(""), "");
  assert.equal(describeRiskReason(undefined), undefined);
});
