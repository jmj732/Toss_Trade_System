// 화면에 노출되는 모든 금액·비중·시각 표기의 단일 진입점.
// 서버 승인 게이트가 KRW 0자리 / USD 2자리 HALF_UP 로 정규화하므로
// 표시 자릿수를 그 판정 기준과 일치시킨다.

const FRACTION_DIGITS = { KRW: 0, JPY: 0, USD: 2, EUR: 2 };
const DEFAULT_FRACTION_DIGITS = 2;
const DISPLAY_TIME_ZONE = "Asia/Seoul";
const DISPLAY_TIME_ZONE_LABEL = "KST";

export const UNKNOWN_TEXT = "확인 필요";

function fractionDigits(currency) {
  return FRACTION_DIGITS[currency] ?? DEFAULT_FRACTION_DIGITS;
}

function toFiniteNumber(value) {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

// 통화 단위는 항상 접두로 붙인다. 통화를 모르면 금액을 단독으로 노출하지 않는다.
export function formatAmount(currency, value) {
  const amount = toFiniteNumber(value);
  if (amount === null) {
    return UNKNOWN_TEXT;
  }
  const digits = fractionDigits(currency);
  const formatted = new Intl.NumberFormat("ko-KR", {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits
  }).format(amount);
  return currency ? `${currency} ${formatted}` : formatted;
}

// 부호를 유지해야 하는 손익 표기.
export function formatSignedAmount(currency, value) {
  const amount = toFiniteNumber(value);
  if (amount === null) {
    return UNKNOWN_TEXT;
  }
  const sign = amount > 0 ? "+" : amount < 0 ? "-" : "";
  const magnitude = formatAmount(currency, Math.abs(amount));
  return currency ? magnitude.replace(`${currency} `, `${currency} ${sign}`) : `${sign}${magnitude}`;
}

export function formatQuantity(value) {
  const quantity = toFiniteNumber(value);
  if (quantity === null) {
    return UNKNOWN_TEXT;
  }
  return new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 8 }).format(quantity);
}

// 비중·집중도는 0~1 소수로 내려온다. 금액 열 옆에서 오독되지 않도록 항상 % 로 환산한다.
export function formatRatio(value, digits = 1) {
  const ratio = toFiniteNumber(value);
  if (ratio === null) {
    return UNKNOWN_TEXT;
  }
  return `${(ratio * 100).toFixed(digits)}%`;
}

const INSTANT_PARTS = new Intl.DateTimeFormat("ko-KR", {
  timeZone: DISPLAY_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false
});

// 모든 시각은 단일 표시 타임존으로 고정하고 타임존 라벨을 반드시 함께 노출한다.
export function formatInstant(value) {
  if (!value) {
    return UNKNOWN_TEXT;
  }
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return UNKNOWN_TEXT;
  }
  const parts = Object.fromEntries(
    INSTANT_PARTS.formatToParts(date).map(part => [part.type, part.value]));
  const hour = parts.hour === "24" ? "00" : parts.hour;
  return `${parts.year}-${parts.month}-${parts.day} ${hour}:${parts.minute} ${DISPLAY_TIME_ZONE_LABEL}`;
}

// "언제 기준 데이터인가"를 한눈에 보이게 하는 보조 표기.
export function formatRelativeTime(value, now = Date.now()) {
  if (!value) {
    return UNKNOWN_TEXT;
  }
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return UNKNOWN_TEXT;
  }
  const seconds = Math.round((now - date.getTime()) / 1000);
  if (seconds < 0) {
    return "잠시 후";
  }
  if (seconds < 60) {
    return "방금 전";
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}분 전`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}시간 전`;
  }
  return `${Math.floor(hours / 24)}일 전`;
}

export function formatFreshness(value, now = Date.now()) {
  if (!value) {
    return UNKNOWN_TEXT;
  }
  const absolute = formatInstant(value);
  if (absolute === UNKNOWN_TEXT) {
    return UNKNOWN_TEXT;
  }
  return `${absolute} · ${formatRelativeTime(value, now)}`;
}

// datetime-local 입력은 타임존이 없는 로컬 시각이다.
// 라벨과 실제 전송값이 어긋나지 않도록 변환을 한 곳에서만 수행한다.
export function localInputToInstant(value) {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}
