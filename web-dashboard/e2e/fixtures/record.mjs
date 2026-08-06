// Shared audit-record sink. Each test writes one JSON file into
// e2e/__reports__/records/ so report.mjs can aggregate them without workers
// racing on a single shared file.

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
export const REPORTS_DIR = resolve(HERE, "..", "__reports__");
export const RECORDS_DIR = resolve(REPORTS_DIR, "records");
export const SCREENSHOTS_DIR = resolve(HERE, "..", "__screenshots__");

export const FORBIDDEN_STRINGS = [
  "undefined",
  "null",
  "NaN",
  "[object Object]",
  "Invalid Date"
];

function slug(value) {
  return String(value).replace(/[^a-zA-Z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
}

export function writeRecord(kind, key, data) {
  mkdirSync(RECORDS_DIR, { recursive: true });
  const file = resolve(RECORDS_DIR, `${slug(kind)}__${slug(key)}.json`);
  writeFileSync(file, JSON.stringify({ kind, key, ...data }, null, 2));
}

/**
 * Attach console + pageerror listeners, returning arrays that fill as the page runs.
 */
export function collectDiagnostics(page) {
  const consoleErrors = [];
  const consoleWarnings = [];
  const pageErrors = [];
  page.on("console", msg => {
    const type = msg.type();
    if (type === "error") {
      consoleErrors.push(msg.text());
    } else if (type === "warning") {
      consoleWarnings.push(msg.text());
    }
  });
  page.on("pageerror", err => {
    pageErrors.push(err.message ?? String(err));
  });
  return { consoleErrors, consoleWarnings, pageErrors };
}

/**
 * Scan the visible body text for the forbidden leak strings.
 */
export async function scanForbidden(page) {
  const text = await page.evaluate(() => document.body?.innerText ?? "");
  const hits = FORBIDDEN_STRINGS.filter(token => text.includes(token));
  return { text, hits };
}

/**
 * Measure horizontal overflow (scrollWidth beyond the viewport).
 */
export async function measureOverflow(page) {
  return page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
    overflow: document.documentElement.scrollWidth - window.innerWidth
  }));
}
