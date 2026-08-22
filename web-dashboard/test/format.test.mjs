import test from "node:test";
import assert from "node:assert/strict";
import {
  UNKNOWN_TEXT,
  formatAmount,
  formatSignedAmount,
  formatSignedAmountWithRate,
  directionOf,
  formatQuantity,
  formatRatio,
  formatDecimal,
  formatInstant,
  formatRelativeTime,
  formatFreshness,
  classifyProposalExpiry,
  proposalExpiryBadge,
  localInputToInstant
} from "../lib/format.js";

test("formatAmount는 KRW를 소수 없이, USD를 두 자리로 표기한다", () => {
  assert.equal(formatAmount("KRW", 1234567.89), "KRW 1,234,568");
  assert.equal(formatAmount("USD", 1234.5), "USD 1,234.50");
});

test("formatAmount는 값이 없으면 확인 필요로 표기한다", () => {
  assert.equal(formatAmount("KRW", null), UNKNOWN_TEXT);
  assert.equal(formatAmount("KRW", undefined), UNKNOWN_TEXT);
  assert.equal(formatAmount("KRW", "not-a-number"), UNKNOWN_TEXT);
});

test("formatAmount는 문자열로 직렬화된 금액도 처리한다", () => {
  assert.equal(formatAmount("USD", "0.005"), "USD 0.01");
});

test("formatSignedAmount는 이익에만 부호를 덧붙인다", () => {
  assert.equal(formatSignedAmount("KRW", 1000), "KRW +1,000");
  assert.equal(formatSignedAmount("KRW", -1000), "KRW -1,000");
  assert.equal(formatSignedAmount("KRW", 0), "KRW 0");
});

test("formatQuantity는 천단위를 구분하고 값이 없으면 확인 필요로 표기한다", () => {
  assert.equal(formatQuantity(1500), "1,500");
  assert.equal(formatQuantity(null), UNKNOWN_TEXT);
});

test("formatRatio는 0~1 소수를 백분율로 환산한다", () => {
  assert.equal(formatRatio(0.3421), "34.2%");
  assert.equal(formatRatio(null), UNKNOWN_TEXT);
});

test("formatDecimal은 순수 배수를 기본 소수 2자리로 고정한다", () => {
  assert.equal(formatDecimal("1.7778268638"), "1.78");
  assert.equal(formatDecimal(2), "2.00");
  assert.equal(formatDecimal(1.5, 1), "1.5");
  assert.equal(formatDecimal(null), UNKNOWN_TEXT);
  assert.equal(formatDecimal(undefined), UNKNOWN_TEXT);
  assert.equal(formatDecimal("not-a-number"), UNKNOWN_TEXT);
});

test("formatInstant는 표시 타임존과 라벨을 함께 노출한다", () => {
  assert.equal(formatInstant("2026-08-05T00:00:00Z"), "2026-08-05 09:00 KST");
});

test("formatInstant는 자정을 24시가 아닌 00시로 표기한다", () => {
  assert.equal(formatInstant("2026-08-04T15:00:00Z"), "2026-08-05 00:00 KST");
});

test("formatInstant는 잘못된 값에 Invalid Date를 노출하지 않는다", () => {
  assert.equal(formatInstant("nonsense"), UNKNOWN_TEXT);
  assert.equal(formatInstant(null), UNKNOWN_TEXT);
});

test("formatRelativeTime은 경과 구간별 문구를 반환한다", () => {
  const now = Date.parse("2026-08-05T12:00:00Z");
  assert.equal(formatRelativeTime("2026-08-05T11:59:30Z", now), "방금 전");
  assert.equal(formatRelativeTime("2026-08-05T11:30:00Z", now), "30분 전");
  assert.equal(formatRelativeTime("2026-08-05T09:00:00Z", now), "3시간 전");
  assert.equal(formatRelativeTime("2026-08-03T12:00:00Z", now), "2일 전");
});

test("formatFreshness는 절대 시각과 상대 시각을 함께 반환한다", () => {
  const now = Date.parse("2026-08-05T12:00:00Z");
  assert.equal(
    formatFreshness("2026-08-05T11:30:00Z", now),
    "2026-08-05 20:30 KST · 30분 전");
});

test("classifyProposalExpiry는 expiresAt이 null이면 unknown으로 분류한다", () => {
  const now = Date.parse("2026-08-05T12:00:00Z");
  assert.equal(classifyProposalExpiry({ createdAt: "2026-08-05T11:00:00Z", expiresAt: null }, now), "unknown");
  assert.equal(classifyProposalExpiry({}, now), "unknown");
});

test("classifyProposalExpiry는 now가 expiresAt 이상이면 expired로 분류한다", () => {
  const now = Date.parse("2026-08-05T12:00:00Z");
  assert.equal(classifyProposalExpiry({ expiresAt: "2026-08-05T12:00:00Z" }, now), "expired");
  assert.equal(classifyProposalExpiry({ expiresAt: "2026-08-05T11:59:59Z" }, now), "expired");
});

test("classifyProposalExpiry는 남은 시간이 2분 이하이면 expiring으로 분류한다", () => {
  const now = Date.parse("2026-08-05T12:00:00Z");
  // createdAt 없음 → 임계값은 2분. 남은 1분 → expiring, 남은 3분 → fresh.
  assert.equal(classifyProposalExpiry({ expiresAt: "2026-08-05T12:01:00Z" }, now), "expiring");
  assert.equal(classifyProposalExpiry({ expiresAt: "2026-08-05T12:03:00Z" }, now), "fresh");
});

test("classifyProposalExpiry는 창의 마지막 20% 이내이면 expiring으로 분류한다", () => {
  const now = Date.parse("2026-08-05T12:00:00Z");
  // 창 95분, 20%=19분. 남은 5분 → expiring(2분 초과여도 창 기준).
  assert.equal(
    classifyProposalExpiry({ createdAt: "2026-08-05T10:30:00Z", expiresAt: "2026-08-05T12:05:00Z" }, now),
    "expiring");
  // 창 110분, 20%=22분. 남은 100분 → fresh.
  assert.equal(
    classifyProposalExpiry({ createdAt: "2026-08-05T11:50:00Z", expiresAt: "2026-08-05T13:40:00Z" }, now),
    "fresh");
});

test("proposalExpiryBadge는 상태별 한국어 문구를 반환하고 fresh는 배지가 없다", () => {
  assert.equal(proposalExpiryBadge("expired"), "만료됨");
  assert.equal(proposalExpiryBadge("expiring"), "곧 만료");
  assert.equal(proposalExpiryBadge("unknown"), "만료 없음");
  assert.equal(proposalExpiryBadge("fresh"), null);
});

test("localInputToInstant는 잘못된 입력에 null을 반환한다", () => {
  assert.equal(localInputToInstant(""), null);
  assert.equal(localInputToInstant("nonsense"), null);
  assert.equal(localInputToInstant("2026-08-05T09:00:00Z"), "2026-08-05T09:00:00.000Z");
});

test("formatSignedAmountWithRate는 부호+금액+비율을 한 덩어리로 만든다", () => {
  assert.equal(formatSignedAmountWithRate("USD", 31.19, 0.0295), "USD +31.19 (2.95%)");
  // 괄호 안 비율에는 부호를 반복하지 않는다 — 방향은 금액 부호가 전달한다.
  assert.equal(formatSignedAmountWithRate("USD", -28.58, -0.0381), "USD -28.58 (3.81%)");
  assert.equal(formatSignedAmountWithRate("KRW", 0, 0), "KRW 0 (0.00%)");
});

test("formatSignedAmountWithRate는 비율이 없으면 금액만, 금액이 없으면 UNKNOWN_TEXT 를 반환한다", () => {
  assert.equal(formatSignedAmountWithRate("USD", 31.19, null), "USD +31.19");
  assert.equal(formatSignedAmountWithRate("USD", null, 0.0295), UNKNOWN_TEXT);
  assert.equal(formatSignedAmountWithRate("USD", 12, "nonsense"), "USD +12.00");
});

test("directionOf는 부호에서 방향 낱말과 클래스를 뽑고 0/null 에는 색을 주지 않는다", () => {
  assert.deepEqual(directionOf(5), { className: "positive", word: "상승" });
  assert.deepEqual(directionOf(-5), { className: "negative", word: "하락" });
  assert.deepEqual(directionOf(0), { className: "", word: "보합" });
  assert.deepEqual(directionOf(null), { className: "", word: null });
});
