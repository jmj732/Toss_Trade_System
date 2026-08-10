import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { PredictionOperationsView } from "../app/prediction-operations-view.js";

test("renders owned evaluation operations and safe API key management", () => {
  const html = renderToStaticMarkup(createElement(PredictionOperationsView, {
    operations: {
      evaluationEnabled: true,
      backlog: 2,
      maxLagMs: 3723000,
      longUngradedCount: 1,
      oldestLongUngradedDueAt: "2026-07-29T00:00:00Z",
      measuredAt: "2026-07-31T00:00:00Z"
    },
    keys: [
      {
        id: "active-key",
        modelVersion: "model-v1",
        contractVersion: "contract-v1",
        prefix: "tpik_12345678",
        status: "ACTIVE",
        createdAt: "2026-07-31T00:00:00Z",
        lastUsedAt: null,
        revokedAt: null,
        expiresAt: "2099-01-01T00:00:00Z"
      },
      {
        id: "expired-key",
        modelVersion: "model-v0",
        contractVersion: "contract-v0",
        prefix: "tpik_87654321",
        status: "EXPIRED",
        createdAt: "2026-01-01T00:00:00Z",
        lastUsedAt: null,
        revokedAt: null,
        expiresAt: "2026-02-01T00:00:00Z"
      }
    ],
    issuedKey: {
      apiKey: "tpik_once_only_secret",
      prefix: "tpik_once_on"
    },
    busy: false,
    error: "",
    onIssue() {},
    onRotate() {},
    onRevoke() {},
    onRefresh() {},
    onDismissKey() {}
  }));

  assert.match(html, /예측 운영/);
  assert.match(html, /평가 활성/);
  assert.match(html, />2</);
  assert.match(html, /1h 2m 3s/);
  assert.match(html, /장기 미채점/);
  assert.match(html, /2026-07-29 09:00 KST/);
  assert.doesNotMatch(html, /2026-07-29T00:00:00Z/);
  assert.match(html, /tpik_once_only_secret/);
  assert.match(html, /이 키는 한 번만 표시됩니다/);
  assert.match(html, /tpik_12345678/);
  assert.match(html, /EXPIRED/);
  assert.doesNotMatch(html, /key_hash|payload/i);
  assert.equal((html.match(/disabled=""/g) ?? []).length, 3);
});

test("leads with run status before API key operations and keeps raw uncertain statuses visible", () => {
  const html = renderToStaticMarkup(createElement(PredictionOperationsView, {
    operations: {
      status: "DEGRADED",
      evaluationEnabled: false,
      backlog: 0,
      maxLagMs: 0,
      longUngradedCount: 0,
      oldestLongUngradedDueAt: null,
      measuredAt: "2026-07-31T00:00:00Z"
    },
    keys: [{
      id: "unknown-key",
      modelVersion: "long-model-version-value-that-wraps",
      contractVersion: "long-contract-version-value-that-wraps",
      prefix: "tpik_unknown",
      status: "UNKNOWN",
      createdAt: "2026-07-31T00:00:00Z",
      lastUsedAt: null,
      revokedAt: null,
      expiresAt: null
    }, {
      id: "manual-key",
      modelVersion: "manual-model",
      contractVersion: "manual-contract",
      prefix: "tpik_manual",
      status: "MANUAL_REVIEW_REQUIRED",
      createdAt: "2026-07-31T00:00:00Z",
      lastUsedAt: null,
      revokedAt: null,
      expiresAt: null
    }],
    issuedKey: null,
    busy: true,
    error: "",
    onIssue() {},
    onRotate() {},
    onRevoke() {},
    onRefresh() {},
    onDismissKey() {}
  }));

  assert.ok(html.indexOf("운영 상태") < html.indexOf("API 키 발급"));
  assert.match(html, /부분 데이터/);
  assert.match(html, /새로고침 중/);
  assert.match(html, /기준 2026-07-31 09:00 KST/);
  assert.match(html, /UNKNOWN/);
  assert.match(html, /MANUAL_REVIEW_REQUIRED/);
});

test("keeps refresh/loading state separate from API key action busy", () => {
  const loadingHtml = renderToStaticMarkup(createElement(PredictionOperationsView, {
    operations: null,
    keys: [{
      id: "active-key",
      modelVersion: "model-v1",
      contractVersion: "contract-v1",
      prefix: "tpik_12345678",
      status: "ACTIVE",
      expiresAt: null,
      lastUsedAt: null
    }],
    issuedKey: null,
    busy: true,
    actionBusy: false,
    error: "",
    onIssue() {},
    onRotate() {},
    onRevoke() {},
    onRefresh() {},
    onDismissKey() {}
  }));

  assert.match(loadingHtml, /불러오는 중/);
  assert.match(loadingHtml, /aria-busy="true"/);
  assert.match(loadingHtml, /disabled="">새로고침/);
  assert.doesNotMatch(loadingHtml, /disabled="">API 키 발급/);
  assert.doesNotMatch(loadingHtml, /disabled="">교체/);
  assert.doesNotMatch(loadingHtml, /disabled="">해지/);

  const actionHtml = renderToStaticMarkup(createElement(PredictionOperationsView, {
    operations: { evaluationEnabled: true, backlog: 0, maxLagMs: 0, longUngradedCount: 0 },
    keys: [{
      id: "active-key",
      modelVersion: "model-v1",
      contractVersion: "contract-v1",
      prefix: "tpik_12345678",
      status: "ACTIVE",
      expiresAt: null,
      lastUsedAt: null
    }],
    issuedKey: null,
    busy: false,
    actionBusy: true,
    error: "",
    onIssue() {},
    onRotate() {},
    onRevoke() {},
    onRefresh() {},
    onDismissKey() {}
  }));

  assert.doesNotMatch(actionHtml, /aria-busy="true"/);
  assert.doesNotMatch(actionHtml, /disabled="">새로고침/);
  assert.match(actionHtml, /disabled="">API 키 발급/);
  assert.match(actionHtml, /disabled="">교체/);
  assert.match(actionHtml, /disabled="">해지/);
});
