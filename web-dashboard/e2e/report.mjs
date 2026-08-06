// Aggregates the per-test audit records into a single human-readable markdown
// report at claudedocs/ui-audit-raw.md. Run after `npm run e2e`.

import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const RECORDS_DIR = resolve(HERE, "__reports__", "records");
const OUT_DIR = resolve(HERE, "..", "claudedocs");
const OUT_FILE = resolve(OUT_DIR, "ui-audit-raw.md");

function loadRecords(prefix) {
  if (!existsSync(RECORDS_DIR)) {
    return [];
  }
  return readdirSync(RECORDS_DIR)
    .filter(f => f.startsWith(`${prefix}__`) && f.endsWith(".json"))
    .map(f => JSON.parse(readFileSync(resolve(RECORDS_DIR, f), "utf8")));
}

function esc(value) {
  return String(value ?? "").replace(/\|/g, "\\|").replace(/\n/g, " ");
}

function stateMatrixTable(records) {
  const header =
    "| route | state | viewport | overflow(px) | consoleErr | pageErr | assertFail | forbidden |\n" +
    "|---|---|---|---|---|---|---|---|";
  const rows = records
    .sort((a, b) =>
      (a.route + a.state + a.viewport).localeCompare(b.route + b.state + b.viewport))
    .map(r => {
      const overflow = r.overflow ? Math.max(0, r.overflow.overflow) : "?";
      return `| ${esc(r.route)} | ${esc(r.state)} | ${esc(r.viewport)} | ${overflow} | ` +
        `${r.consoleErrors?.length ?? 0} | ${r.pageErrors?.length ?? 0} | ` +
        `${r.failures?.length ?? 0} | ${esc((r.forbiddenHits ?? []).join(",")) || "-"} |`;
    });
  return [header, ...rows].join("\n");
}

function failureDetail(records) {
  const lines = [];
  for (const r of records) {
    for (const f of r.failures ?? []) {
      lines.push(
        `| ${esc(r.route)} | ${esc(r.state)} | ${esc(r.viewport)} | ${esc(f.assertion)} | ${esc(f.actual)} |`
      );
    }
  }
  if (lines.length === 0) {
    return "_No assertion failures recorded._";
  }
  return [
    "| route | state | viewport | assertion | actual |",
    "|---|---|---|---|---|",
    ...lines
  ].join("\n");
}

function consoleDetail(records) {
  const lines = [];
  for (const r of records) {
    for (const msg of r.consoleErrors ?? []) {
      lines.push(`| ${esc(r.route)} | ${esc(r.state)} | ${esc(r.viewport)} | ${esc(msg)} |`);
    }
    for (const msg of r.pageErrors ?? []) {
      lines.push(`| ${esc(r.route)} | ${esc(r.state)} | ${esc(r.viewport)} | (pageerror) ${esc(msg)} |`);
    }
  }
  if (lines.length === 0) {
    return "_No console errors or page exceptions recorded._";
  }
  return [
    "| route | state | viewport | message |",
    "|---|---|---|---|",
    ...lines
  ].join("\n");
}

function axeSummary(records) {
  const byRule = new Map();
  for (const r of records) {
    for (const v of r.violations ?? []) {
      const cur = byRule.get(v.id) ?? {
        id: v.id,
        impact: v.impact,
        combos: 0,
        nodes: 0,
        sample: v.targets?.[0] ?? ""
      };
      cur.combos += 1;
      cur.nodes += v.nodes ?? 0;
      if (!cur.sample && v.targets?.[0]) {
        cur.sample = v.targets[0];
      }
      byRule.set(v.id, cur);
    }
  }
  const rows = [...byRule.values()]
    .sort((a, b) => b.combos - a.combos || b.nodes - a.nodes)
    .map(v =>
      `| ${esc(v.id)} | ${esc(v.impact)} | ${v.combos} | ${v.nodes} | ${esc(v.sample)} |`);
  if (rows.length === 0) {
    return "_No axe violations recorded (or axe did not run)._";
  }
  return [
    "| rule id | impact | combos | total nodes | sample selector |",
    "|---|---|---|---|---|",
    ...rows
  ].join("\n");
}

function journeyDetail(records) {
  if (records.length === 0) {
    return "_No journey records._";
  }
  const blocks = records
    .sort((a, b) => String(a.key).localeCompare(String(b.key)))
    .map(r => {
      const steps = (r.steps ?? [])
        .map(s => `  - ${s.ok ? "PASS" : "FAIL"} ${esc(s.step)}` +
          (s.actual !== undefined ? ` (actual: ${esc(s.actual)})` : "") +
          (s.url ? ` (${esc(s.url)})` : ""))
        .join("\n");
      const extras = [];
      if (r.approvePostCount !== undefined) extras.push(`approvePostCount=${r.approvePostCount}`);
      if (r.dashboardAttempts !== undefined) extras.push(`dashboardAttempts=${r.dashboardAttempts}`);
      if (r.analysisCreated !== undefined) extras.push(`analysisCreated=${r.analysisCreated}`);
      return `### ${esc(r.key)}\n` +
        `- blockedAt: ${r.blockedAt ? esc(r.blockedAt) : "(none)"}\n` +
        (extras.length ? `- ${extras.join(", ")}\n` : "") +
        (r.consoleErrors?.length ? `- consoleErrors: ${r.consoleErrors.length}\n` : "") +
        (steps ? `${steps}\n` : "");
    });
  return blocks.join("\n");
}

const stateRecords = loadRecords("state");
const axeRecords = loadRecords("axe");
const journeyRecords = loadRecords("journey");

const totalCombos = stateRecords.length;
const withFailures = stateRecords.filter(r => (r.failures?.length ?? 0) > 0).length;
const withConsole = stateRecords.filter(
  r => (r.consoleErrors?.length ?? 0) > 0 || (r.pageErrors?.length ?? 0) > 0
).length;
const totalAxe = axeRecords.reduce((sum, r) => sum + (r.violationCount ?? 0), 0);

const md = `# UI Audit — Raw Findings

Generated: ${new Date().toISOString()}

## Summary

- State-matrix combinations recorded: **${totalCombos}**
- Combinations with assertion failures: **${withFailures}**
- Combinations with console errors / page exceptions: **${withConsole}**
- Total axe violations (all combos): **${totalAxe}**
- Journeys recorded: **${journeyRecords.length}**

## State matrix

${stateMatrixTable(stateRecords)}

## Assertion failures

${failureDetail(stateRecords)}

## Console errors & page exceptions

${consoleDetail(stateRecords)}

## Accessibility (axe) — violations by rule

${axeSummary(axeRecords)}

## User journeys

${journeyDetail(journeyRecords)}
`;

mkdirSync(OUT_DIR, { recursive: true });
writeFileSync(OUT_FILE, md);
process.stdout.write(`Wrote ${OUT_FILE}\n`);
process.stdout.write(
  `combos=${totalCombos} failures=${withFailures} consoleCombos=${withConsole} axeViolations=${totalAxe}\n`
);
