import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { BrokerOnboarding } from "../app/broker-onboarding.js";

test("renders secret-free credential and lifecycle controls", () => {
  const html = renderToStaticMarkup(createElement(BrokerOnboarding, {
    connection: {
      id: "connection-1",
      brokerType: "TOSS_INVEST",
      status: "ACTIVE",
      credentialRevision: 2,
      lastValidatedAt: "2026-07-28T00:00:00Z"
    },
    connectionId: "connection-1",
    busyAction: null,
    onCredentials() {},
    onCommand() {}
  }));

  assert.match(html, /계좌 연결됨/);
  assert.match(html, /연결 정보 변경/);
  for (const label of ["연결 확인", "포트폴리오 동기화", "분석 실행", "연결 삭제"]) {
    assert.match(html, new RegExp(label));
  }
  assert.equal((html.match(/type="password"/g) ?? []).length, 2);
  assert.equal((html.match(/autoComplete="off"/g) ?? []).length, 3);
  assert.doesNotMatch(html, /value="[^"]+"/);
  assert.doesNotMatch(html, /client-secret-canary|client-id-canary/);
});

test("first visit shows one connection action only", () => {
  const html = renderToStaticMarkup(createElement(BrokerOnboarding, {
    connection: null,
    connectionId: "",
    busyAction: null,
    onCredentials() {},
    onCommand() {}
  }));

  assert.match(html, /토스증권 계좌 연결/);
  assert.match(html, /계좌를 연결하고 시작하세요/);
  assert.doesNotMatch(html, /연결 정보 변경|연결 확인|포트폴리오 동기화|분석 실행|연결 삭제/);
  assert.equal((html.match(/type="password"/g) ?? []).length, 2);
});

test("disables every onboarding command during one mutation", () => {
  const html = renderToStaticMarkup(createElement(BrokerOnboarding, {
    connection: null,
    connectionId: "connection-1",
    busyAction: "sync",
    onCredentials() {},
    onCommand() {}
  }));

  assert.equal((html.match(/disabled=""/g) ?? []).length, 5);
  assert.match(html, /sync…/);
});

test("keeps sync and analysis disabled until the connection is active", () => {
  const html = renderToStaticMarkup(createElement(BrokerOnboarding, {
    connection: { status: "UNVERIFIED" },
    connectionId: "connection-1",
    busyAction: null,
    onCredentials() {},
    onCommand() {}
  }));

  assert.match(html, /disabled=""[^>]*>포트폴리오 동기화/);
  assert.match(html, /disabled=""[^>]*>분석 실행/);
  assert.doesNotMatch(html, /disabled=""[^>]*>연결 확인/);
});

test("does not load a dashboard before a new connection is verified", async () => {
  const source = await readFile(new URL("../app/page.js", import.meta.url), "utf8");
  assert.doesNotMatch(source, /setDashboard\(await loadDashboard\(created\.id\)\)/);
});
