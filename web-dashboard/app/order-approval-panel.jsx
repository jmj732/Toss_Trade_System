// 승인 2단계의 1단계 화면: approval-preview 를 조회해 사용자에게 보여주고,
// 사용자가 확인한 "그 값"만 승인 요청으로 되돌려보낸다(D-01).
// 이 저장소의 점진적 JSX 전환 파일럿이다. 나머지 컴포넌트는 createElement 를 유지한다.
import { useEffect, useState } from "react";

import { loadOrderApprovalPreview } from "../lib/api.js";
import {
  formatAmount,
  formatQuantity,
  formatFreshness,
  UNKNOWN_TEXT
} from "../lib/format.js";

const SIDE_LABELS = { BUY: "매수", SELL: "매도" };

function sideText(side) {
  return SIDE_LABELS[side] ?? (side ?? UNKNOWN_TEXT);
}

// 최대손실은 실시간 호가 × 수량이다(서버 기준). 사용자에게 단가 기준을 투명하게 보여준다.
function maxLossBasisPrice(currency, quantity, maxLoss) {
  const qty = Number(quantity);
  const loss = Number(maxLoss);
  if (!Number.isFinite(qty) || qty <= 0 || !Number.isFinite(loss)) {
    return UNKNOWN_TEXT;
  }
  return formatAmount(currency, loss / qty);
}

function isDisplayMismatch(error) {
  return error?.body?.code === "PAPER_ORDER_DISPLAY_MISMATCH";
}

/**
 * @param order    주문 제안 { id, side, symbol, type, quantity, limitPrice, currency, status }
 * @param busy     이 주문의 승인 요청이 진행 중인지
 * @param error    직전 승인 시도의 오류(step-up 요구 또는 display mismatch 복구용, D-02/D-16)
 * @param onConfirm(displayed) 사용자가 확인한 값으로 승인 실행
 *                 displayed = { quantity, maxLoss, currency, proposalVersion }
 * @param onReject 이 주문을 거부(취소)
 * @param onClose  행동 없이 패널 닫기
 * @param onReauth step-up 재인증 유도(선택)
 * @param fetcher  테스트용 주입(선택)
 */
export function OrderApprovalPanel({
  order,
  busy = false,
  error = null,
  onConfirm,
  onReject,
  onClose,
  onReauth,
  fetcher
}) {
  const [preview, setPreview] = useState(null);
  const [previewedAt, setPreviewedAt] = useState(null);
  const [loadError, setLoadError] = useState(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    setPreview(null);
    setLoadError(null);
    Promise.resolve(loadOrderApprovalPreview(order.id, fetcher))
      .then(result => {
        if (active) {
          setPreview(result);
          setPreviewedAt(new Date());
        }
      })
      .catch(value => {
        if (active) {
          setLoadError(value);
        }
      });
    return () => {
      active = false;
    };
  }, [order.id, fetcher, attempt]);

  const sideKnown = Object.prototype.hasOwnProperty.call(SIDE_LABELS, order.side);
  const priceText = order.limitPrice == null
    ? "시장가"
    : formatAmount(order.currency, order.limitPrice);

  const stepUpRequired = loadError?.stepUpRequired || error?.stepUpRequired;
  const mismatch = isDisplayMismatch(error);

  function confirm() {
    if (!preview) {
      return;
    }
    onConfirm({
      quantity: preview.displayedQuantity,
      maxLoss: preview.displayedMaxLoss,
      currency: preview.displayedCurrency,
      proposalVersion: preview.proposalVersion ?? null
    });
  }

  return (
    <section className="panel order-approval-panel" aria-live="polite">
      <header>
        <p className="eyebrow">주문 승인 확인</p>
        <h2>{sideText(order.side)} {order.symbol ?? UNKNOWN_TEXT}</h2>
      </header>

      {stepUpRequired ? (
        <div className="empty" role="alert">
          <p>재인증이 필요합니다. 본인 확인 후 다시 승인해 주세요.</p>
          {onReauth ? (
            <button type="button" onClick={onReauth}>재인증하기</button>
          ) : null}
        </div>
      ) : null}

      {mismatch ? (
        // D-16: 서버 스냅샷과 사용자가 본 값을 나란히 제시하고 재확인 경로를 제공한다.
        <div className="empty" role="alert">
          <p>표시된 값과 서버 값이 다릅니다. 아래 서버 기준 값을 다시 확인해 주세요.</p>
          <dl className="mismatch">
            <div>
              <dt>서버 수량</dt>
              <dd>{formatQuantity(error.body.serverQuantity)}</dd>
            </div>
            <div>
              <dt>서버 최대 예상 손실</dt>
              <dd>{formatAmount(error.body.currency, error.body.serverMaxLoss)}</dd>
            </div>
          </dl>
          <button type="button" onClick={() => setAttempt(value => value + 1)}>
            최신 값 다시 불러오기
          </button>
        </div>
      ) : null}

      {loadError && !stepUpRequired ? (
        <div className="empty" role="alert">
          <p>{loadError.message ?? "미리보기를 불러오지 못했습니다."}</p>
          <button type="button" onClick={() => setAttempt(value => value + 1)}>다시 시도</button>
        </div>
      ) : null}

      {preview ? (
        <dl className="approval-preview">
          <div>
            <dt>수량</dt>
            <dd>{formatQuantity(preview.displayedQuantity)}</dd>
          </div>
          <div>
            <dt>통화</dt>
            <dd>{preview.displayedCurrency ?? UNKNOWN_TEXT}</dd>
          </div>
          <div>
            <dt>{order.limitPrice == null ? "주문 유형" : "지정가"}</dt>
            <dd>{priceText}</dd>
          </div>
          <div>
            <dt>최대손실 기준 단가</dt>
            <dd>{maxLossBasisPrice(
              preview.displayedCurrency,
              preview.displayedQuantity,
              preview.displayedMaxLoss)}</dd>
          </div>
          <div>
            <dt>최대 예상 손실</dt>
            <dd>{formatAmount(preview.displayedCurrency, preview.displayedMaxLoss)}</dd>
          </div>
          <div>
            <dt>미리보기 조회 시각</dt>
            <dd>{formatFreshness(previewedAt)}</dd>
          </div>
        </dl>
      ) : (
        !loadError ? <p className="busy">미리보기를 불러오는 중…</p> : null
      )}

      <div className="actions">
        <button
          type="button"
          onClick={confirm}
          disabled={!preview || busy || !sideKnown || stepUpRequired}
        >
          확인하고 승인
        </button>
        <button type="button" className="secondary" onClick={onReject} disabled={busy}>
          거부
        </button>
        <button type="button" className="secondary" onClick={onClose} disabled={busy}>
          닫기
        </button>
      </div>
    </section>
  );
}
