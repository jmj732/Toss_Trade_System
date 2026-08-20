# UI Audit — Raw Findings

Generated: 2026-08-20T13:55:42.801Z

## Summary

- State-matrix combinations recorded: **488**
- Combinations with assertion failures: **0**
- Combinations with console errors / page exceptions: **96**
- Total axe violations (all combos): **0**
- Journeys recorded: **5**

## Light vs dark

| scheme | combos | assertion failures | horizontal overflow | axe violations |
|---|---|---|---|---|
| light | 244 | 0 | 0 | 0 |
| dark | 244 | 0 | 0 | 0 |

## State matrix

| route | state | viewport | scheme | overflow(px) | consoleErr | pageErr | assertFail | forbidden |
|---|---|---|---|---|---|---|---|---|
| events | degraded | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| events | degraded | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| events | degraded | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| events | degraded | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| events | degraded | vp-360 | light | 0 | 0 | 0 | 0 | - |
| events | degraded | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| events | degraded | vp-768 | light | 0 | 0 | 0 | 0 | - |
| events | degraded | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| events | empty | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| events | empty | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| events | empty | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| events | empty | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| events | empty | vp-360 | light | 0 | 0 | 0 | 0 | - |
| events | empty | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| events | empty | vp-768 | light | 0 | 0 | 0 | 0 | - |
| events | empty | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| events | error | vp-1280 | light | 0 | 4 | 0 | 0 | - |
| events | error | vp-1280-dark | dark | 0 | 4 | 0 | 0 | - |
| events | error | vp-1440 | light | 0 | 4 | 0 | 0 | - |
| events | error | vp-1440-dark | dark | 0 | 4 | 0 | 0 | - |
| events | error | vp-360 | light | 0 | 4 | 0 | 0 | - |
| events | error | vp-360-dark | dark | 0 | 4 | 0 | 0 | - |
| events | error | vp-768 | light | 0 | 4 | 0 | 0 | - |
| events | error | vp-768-dark | dark | 0 | 4 | 0 | 0 | - |
| events | loading | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| events | loading | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| events | loading | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| events | loading | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| events | loading | vp-360 | light | 0 | 0 | 0 | 0 | - |
| events | loading | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| events | loading | vp-768 | light | 0 | 0 | 0 | 0 | - |
| events | loading | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| events | partial | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| events | partial | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| events | partial | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| events | partial | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| events | partial | vp-360 | light | 0 | 0 | 0 | 0 | - |
| events | partial | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| events | partial | vp-768 | light | 0 | 0 | 0 | 0 | - |
| events | partial | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| events | refreshing | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| events | refreshing | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| events | refreshing | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| events | refreshing | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| events | refreshing | vp-360 | light | 0 | 0 | 0 | 0 | - |
| events | refreshing | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| events | refreshing | vp-768 | light | 0 | 0 | 0 | 0 | - |
| events | refreshing | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| events | stale | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| events | stale | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| events | stale | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| events | stale | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| events | stale | vp-360 | light | 0 | 0 | 0 | 0 | - |
| events | stale | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| events | stale | vp-768 | light | 0 | 0 | 0 | 0 | - |
| events | stale | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| events | unauthorized | vp-1280 | light | 0 | 2 | 0 | 0 | - |
| events | unauthorized | vp-1280-dark | dark | 0 | 2 | 0 | 0 | - |
| events | unauthorized | vp-1440 | light | 0 | 2 | 0 | 0 | - |
| events | unauthorized | vp-1440-dark | dark | 0 | 2 | 0 | 0 | - |
| events | unauthorized | vp-360 | light | 0 | 2 | 0 | 0 | - |
| events | unauthorized | vp-360-dark | dark | 0 | 2 | 0 | 0 | - |
| events | unauthorized | vp-768 | light | 0 | 2 | 0 | 0 | - |
| events | unauthorized | vp-768-dark | dark | 0 | 2 | 0 | 0 | - |
| home | decision-active | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| home | decision-active | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-active | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| home | decision-active | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-active | vp-360 | light | 0 | 0 | 0 | 0 | - |
| home | decision-active | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-active | vp-768 | light | 0 | 0 | 0 | 0 | - |
| home | decision-active | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-blocked | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| home | decision-blocked | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-blocked | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| home | decision-blocked | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-blocked | vp-360 | light | 0 | 0 | 0 | 0 | - |
| home | decision-blocked | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-blocked | vp-768 | light | 0 | 0 | 0 | 0 | - |
| home | decision-blocked | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-calm | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| home | decision-calm | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-calm | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| home | decision-calm | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-calm | vp-360 | light | 0 | 0 | 0 | 0 | - |
| home | decision-calm | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-calm | vp-768 | light | 0 | 0 | 0 | 0 | - |
| home | decision-calm | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-critical | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| home | decision-critical | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-critical | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| home | decision-critical | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-critical | vp-360 | light | 0 | 0 | 0 | 0 | - |
| home | decision-critical | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-critical | vp-768 | light | 0 | 0 | 0 | 0 | - |
| home | decision-critical | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-risk | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| home | decision-risk | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-risk | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| home | decision-risk | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-risk | vp-360 | light | 0 | 0 | 0 | 0 | - |
| home | decision-risk | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| home | decision-risk | vp-768 | light | 0 | 0 | 0 | 0 | - |
| home | decision-risk | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| home | degraded | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| home | degraded | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| home | degraded | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| home | degraded | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| home | degraded | vp-360 | light | 0 | 0 | 0 | 0 | - |
| home | degraded | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| home | degraded | vp-768 | light | 0 | 0 | 0 | 0 | - |
| home | degraded | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| home | empty | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| home | empty | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| home | empty | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| home | empty | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| home | empty | vp-360 | light | 0 | 0 | 0 | 0 | - |
| home | empty | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| home | empty | vp-768 | light | 0 | 0 | 0 | 0 | - |
| home | empty | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| home | error | vp-1280 | light | 0 | 5 | 0 | 0 | - |
| home | error | vp-1280-dark | dark | 0 | 5 | 0 | 0 | - |
| home | error | vp-1440 | light | 0 | 5 | 0 | 0 | - |
| home | error | vp-1440-dark | dark | 0 | 5 | 0 | 0 | - |
| home | error | vp-360 | light | 0 | 5 | 0 | 0 | - |
| home | error | vp-360-dark | dark | 0 | 5 | 0 | 0 | - |
| home | error | vp-768 | light | 0 | 5 | 0 | 0 | - |
| home | error | vp-768-dark | dark | 0 | 5 | 0 | 0 | - |
| home | loading | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| home | loading | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| home | loading | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| home | loading | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| home | loading | vp-360 | light | 0 | 0 | 0 | 0 | - |
| home | loading | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| home | loading | vp-768 | light | 0 | 0 | 0 | 0 | - |
| home | loading | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| home | partial | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| home | partial | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| home | partial | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| home | partial | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| home | partial | vp-360 | light | 0 | 0 | 0 | 0 | - |
| home | partial | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| home | partial | vp-768 | light | 0 | 0 | 0 | 0 | - |
| home | partial | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| home | refreshing | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| home | refreshing | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| home | refreshing | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| home | refreshing | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| home | refreshing | vp-360 | light | 0 | 0 | 0 | 0 | - |
| home | refreshing | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| home | refreshing | vp-768 | light | 0 | 0 | 0 | 0 | - |
| home | refreshing | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| home | stale | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| home | stale | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| home | stale | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| home | stale | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| home | stale | vp-360 | light | 0 | 0 | 0 | 0 | - |
| home | stale | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| home | stale | vp-768 | light | 0 | 0 | 0 | 0 | - |
| home | stale | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| home | unauthorized | vp-1280 | light | 0 | 2 | 0 | 0 | - |
| home | unauthorized | vp-1280-dark | dark | 0 | 2 | 0 | 0 | - |
| home | unauthorized | vp-1440 | light | 0 | 2 | 0 | 0 | - |
| home | unauthorized | vp-1440-dark | dark | 0 | 2 | 0 | 0 | - |
| home | unauthorized | vp-360 | light | 0 | 2 | 0 | 0 | - |
| home | unauthorized | vp-360-dark | dark | 0 | 2 | 0 | 0 | - |
| home | unauthorized | vp-768 | light | 0 | 2 | 0 | 0 | - |
| home | unauthorized | vp-768-dark | dark | 0 | 2 | 0 | 0 | - |
| login | degraded | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| login | degraded | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| login | degraded | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| login | degraded | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| login | degraded | vp-360 | light | 0 | 0 | 0 | 0 | - |
| login | degraded | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| login | degraded | vp-768 | light | 0 | 0 | 0 | 0 | - |
| login | degraded | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| login | empty | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| login | empty | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| login | empty | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| login | empty | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| login | empty | vp-360 | light | 0 | 0 | 0 | 0 | - |
| login | empty | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| login | empty | vp-768 | light | 0 | 0 | 0 | 0 | - |
| login | empty | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| login | error | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| login | error | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| login | error | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| login | error | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| login | error | vp-360 | light | 0 | 0 | 0 | 0 | - |
| login | error | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| login | error | vp-768 | light | 0 | 0 | 0 | 0 | - |
| login | error | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| login | loading | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| login | loading | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| login | loading | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| login | loading | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| login | loading | vp-360 | light | 0 | 0 | 0 | 0 | - |
| login | loading | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| login | loading | vp-768 | light | 0 | 0 | 0 | 0 | - |
| login | loading | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| login | partial | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| login | partial | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| login | partial | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| login | partial | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| login | partial | vp-360 | light | 0 | 0 | 0 | 0 | - |
| login | partial | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| login | partial | vp-768 | light | 0 | 0 | 0 | 0 | - |
| login | partial | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| login | refreshing | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| login | refreshing | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| login | refreshing | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| login | refreshing | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| login | refreshing | vp-360 | light | 0 | 0 | 0 | 0 | - |
| login | refreshing | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| login | refreshing | vp-768 | light | 0 | 0 | 0 | 0 | - |
| login | refreshing | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| login | stale | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| login | stale | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| login | stale | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| login | stale | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| login | stale | vp-360 | light | 0 | 0 | 0 | 0 | - |
| login | stale | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| login | stale | vp-768 | light | 0 | 0 | 0 | 0 | - |
| login | stale | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| login | unauthorized | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| login | unauthorized | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| login | unauthorized | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| login | unauthorized | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| login | unauthorized | vp-360 | light | 0 | 0 | 0 | 0 | - |
| login | unauthorized | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| login | unauthorized | vp-768 | light | 0 | 0 | 0 | 0 | - |
| login | unauthorized | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | degraded | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| orders | degraded | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | degraded | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| orders | degraded | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | degraded | vp-360 | light | 0 | 0 | 0 | 0 | - |
| orders | degraded | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | degraded | vp-768 | light | 0 | 0 | 0 | 0 | - |
| orders | degraded | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | empty | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| orders | empty | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | empty | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| orders | empty | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | empty | vp-360 | light | 0 | 0 | 0 | 0 | - |
| orders | empty | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | empty | vp-768 | light | 0 | 0 | 0 | 0 | - |
| orders | empty | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | error | vp-1280 | light | 0 | 4 | 0 | 0 | - |
| orders | error | vp-1280-dark | dark | 0 | 4 | 0 | 0 | - |
| orders | error | vp-1440 | light | 0 | 4 | 0 | 0 | - |
| orders | error | vp-1440-dark | dark | 0 | 4 | 0 | 0 | - |
| orders | error | vp-360 | light | 0 | 4 | 0 | 0 | - |
| orders | error | vp-360-dark | dark | 0 | 4 | 0 | 0 | - |
| orders | error | vp-768 | light | 0 | 4 | 0 | 0 | - |
| orders | error | vp-768-dark | dark | 0 | 4 | 0 | 0 | - |
| orders | loading | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| orders | loading | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | loading | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| orders | loading | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | loading | vp-360 | light | 0 | 0 | 0 | 0 | - |
| orders | loading | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | loading | vp-768 | light | 0 | 0 | 0 | 0 | - |
| orders | loading | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | partial | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| orders | partial | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | partial | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| orders | partial | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | partial | vp-360 | light | 0 | 0 | 0 | 0 | - |
| orders | partial | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | partial | vp-768 | light | 0 | 0 | 0 | 0 | - |
| orders | partial | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | refreshing | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| orders | refreshing | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | refreshing | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| orders | refreshing | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | refreshing | vp-360 | light | 0 | 0 | 0 | 0 | - |
| orders | refreshing | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | refreshing | vp-768 | light | 0 | 0 | 0 | 0 | - |
| orders | refreshing | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | stale | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| orders | stale | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | stale | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| orders | stale | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | stale | vp-360 | light | 0 | 0 | 0 | 0 | - |
| orders | stale | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | stale | vp-768 | light | 0 | 0 | 0 | 0 | - |
| orders | stale | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| orders | unauthorized | vp-1280 | light | 0 | 2 | 0 | 0 | - |
| orders | unauthorized | vp-1280-dark | dark | 0 | 2 | 0 | 0 | - |
| orders | unauthorized | vp-1440 | light | 0 | 2 | 0 | 0 | - |
| orders | unauthorized | vp-1440-dark | dark | 0 | 2 | 0 | 0 | - |
| orders | unauthorized | vp-360 | light | 0 | 2 | 0 | 0 | - |
| orders | unauthorized | vp-360-dark | dark | 0 | 2 | 0 | 0 | - |
| orders | unauthorized | vp-768 | light | 0 | 2 | 0 | 0 | - |
| orders | unauthorized | vp-768-dark | dark | 0 | 2 | 0 | 0 | - |
| portfolio | degraded | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| portfolio | degraded | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | degraded | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| portfolio | degraded | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | degraded | vp-360 | light | 0 | 0 | 0 | 0 | - |
| portfolio | degraded | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | degraded | vp-768 | light | 0 | 0 | 0 | 0 | - |
| portfolio | degraded | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | empty | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| portfolio | empty | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | empty | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| portfolio | empty | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | empty | vp-360 | light | 0 | 0 | 0 | 0 | - |
| portfolio | empty | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | empty | vp-768 | light | 0 | 0 | 0 | 0 | - |
| portfolio | empty | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | error | vp-1280 | light | 0 | 4 | 0 | 0 | - |
| portfolio | error | vp-1280-dark | dark | 0 | 4 | 0 | 0 | - |
| portfolio | error | vp-1440 | light | 0 | 4 | 0 | 0 | - |
| portfolio | error | vp-1440-dark | dark | 0 | 4 | 0 | 0 | - |
| portfolio | error | vp-360 | light | 0 | 4 | 0 | 0 | - |
| portfolio | error | vp-360-dark | dark | 0 | 4 | 0 | 0 | - |
| portfolio | error | vp-768 | light | 0 | 4 | 0 | 0 | - |
| portfolio | error | vp-768-dark | dark | 0 | 4 | 0 | 0 | - |
| portfolio | loading | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| portfolio | loading | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | loading | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| portfolio | loading | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | loading | vp-360 | light | 0 | 0 | 0 | 0 | - |
| portfolio | loading | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | loading | vp-768 | light | 0 | 0 | 0 | 0 | - |
| portfolio | loading | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | partial | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| portfolio | partial | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | partial | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| portfolio | partial | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | partial | vp-360 | light | 0 | 0 | 0 | 0 | - |
| portfolio | partial | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | partial | vp-768 | light | 0 | 0 | 0 | 0 | - |
| portfolio | partial | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | refreshing | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| portfolio | refreshing | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | refreshing | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| portfolio | refreshing | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | refreshing | vp-360 | light | 0 | 0 | 0 | 0 | - |
| portfolio | refreshing | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | refreshing | vp-768 | light | 0 | 0 | 0 | 0 | - |
| portfolio | refreshing | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | stale | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| portfolio | stale | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | stale | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| portfolio | stale | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | stale | vp-360 | light | 0 | 0 | 0 | 0 | - |
| portfolio | stale | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | stale | vp-768 | light | 0 | 0 | 0 | 0 | - |
| portfolio | stale | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| portfolio | unauthorized | vp-1280 | light | 0 | 2 | 0 | 0 | - |
| portfolio | unauthorized | vp-1280-dark | dark | 0 | 2 | 0 | 0 | - |
| portfolio | unauthorized | vp-1440 | light | 0 | 2 | 0 | 0 | - |
| portfolio | unauthorized | vp-1440-dark | dark | 0 | 2 | 0 | 0 | - |
| portfolio | unauthorized | vp-360 | light | 0 | 2 | 0 | 0 | - |
| portfolio | unauthorized | vp-360-dark | dark | 0 | 2 | 0 | 0 | - |
| portfolio | unauthorized | vp-768 | light | 0 | 2 | 0 | 0 | - |
| portfolio | unauthorized | vp-768-dark | dark | 0 | 2 | 0 | 0 | - |
| settings | degraded | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| settings | degraded | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | degraded | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| settings | degraded | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | degraded | vp-360 | light | 0 | 0 | 0 | 0 | - |
| settings | degraded | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | degraded | vp-768 | light | 0 | 0 | 0 | 0 | - |
| settings | degraded | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | empty | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| settings | empty | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | empty | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| settings | empty | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | empty | vp-360 | light | 0 | 0 | 0 | 0 | - |
| settings | empty | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | empty | vp-768 | light | 0 | 0 | 0 | 0 | - |
| settings | empty | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | error | vp-1280 | light | 0 | 4 | 0 | 0 | - |
| settings | error | vp-1280-dark | dark | 0 | 4 | 0 | 0 | - |
| settings | error | vp-1440 | light | 0 | 4 | 0 | 0 | - |
| settings | error | vp-1440-dark | dark | 0 | 4 | 0 | 0 | - |
| settings | error | vp-360 | light | 0 | 4 | 0 | 0 | - |
| settings | error | vp-360-dark | dark | 0 | 4 | 0 | 0 | - |
| settings | error | vp-768 | light | 0 | 4 | 0 | 0 | - |
| settings | error | vp-768-dark | dark | 0 | 4 | 0 | 0 | - |
| settings | loading | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| settings | loading | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | loading | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| settings | loading | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | loading | vp-360 | light | 0 | 0 | 0 | 0 | - |
| settings | loading | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | loading | vp-768 | light | 0 | 0 | 0 | 0 | - |
| settings | loading | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | partial | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| settings | partial | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | partial | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| settings | partial | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | partial | vp-360 | light | 0 | 0 | 0 | 0 | - |
| settings | partial | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | partial | vp-768 | light | 0 | 0 | 0 | 0 | - |
| settings | partial | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | refreshing | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| settings | refreshing | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | refreshing | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| settings | refreshing | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | refreshing | vp-360 | light | 0 | 0 | 0 | 0 | - |
| settings | refreshing | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | refreshing | vp-768 | light | 0 | 0 | 0 | 0 | - |
| settings | refreshing | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | stale | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| settings | stale | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | stale | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| settings | stale | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | stale | vp-360 | light | 0 | 0 | 0 | 0 | - |
| settings | stale | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | stale | vp-768 | light | 0 | 0 | 0 | 0 | - |
| settings | stale | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| settings | unauthorized | vp-1280 | light | 0 | 2 | 0 | 0 | - |
| settings | unauthorized | vp-1280-dark | dark | 0 | 2 | 0 | 0 | - |
| settings | unauthorized | vp-1440 | light | 0 | 2 | 0 | 0 | - |
| settings | unauthorized | vp-1440-dark | dark | 0 | 2 | 0 | 0 | - |
| settings | unauthorized | vp-360 | light | 0 | 2 | 0 | 0 | - |
| settings | unauthorized | vp-360-dark | dark | 0 | 2 | 0 | 0 | - |
| settings | unauthorized | vp-768 | light | 0 | 2 | 0 | 0 | - |
| settings | unauthorized | vp-768-dark | dark | 0 | 2 | 0 | 0 | - |
| stocks-AAPL | degraded | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | degraded | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | degraded | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | degraded | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | degraded | vp-360 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | degraded | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | degraded | vp-768 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | degraded | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | empty | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | empty | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | empty | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | empty | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | empty | vp-360 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | empty | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | empty | vp-768 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | empty | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | error | vp-1280 | light | 0 | 4 | 0 | 0 | - |
| stocks-AAPL | error | vp-1280-dark | dark | 0 | 4 | 0 | 0 | - |
| stocks-AAPL | error | vp-1440 | light | 0 | 4 | 0 | 0 | - |
| stocks-AAPL | error | vp-1440-dark | dark | 0 | 4 | 0 | 0 | - |
| stocks-AAPL | error | vp-360 | light | 0 | 4 | 0 | 0 | - |
| stocks-AAPL | error | vp-360-dark | dark | 0 | 4 | 0 | 0 | - |
| stocks-AAPL | error | vp-768 | light | 0 | 4 | 0 | 0 | - |
| stocks-AAPL | error | vp-768-dark | dark | 0 | 4 | 0 | 0 | - |
| stocks-AAPL | loading | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | loading | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | loading | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | loading | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | loading | vp-360 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | loading | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | loading | vp-768 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | loading | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | partial | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | partial | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | partial | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | partial | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | partial | vp-360 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | partial | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | partial | vp-768 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | partial | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | refreshing | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | refreshing | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | refreshing | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | refreshing | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | refreshing | vp-360 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | refreshing | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | refreshing | vp-768 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | refreshing | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | stale | vp-1280 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | stale | vp-1280-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | stale | vp-1440 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | stale | vp-1440-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | stale | vp-360 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | stale | vp-360-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | stale | vp-768 | light | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | stale | vp-768-dark | dark | 0 | 0 | 0 | 0 | - |
| stocks-AAPL | unauthorized | vp-1280 | light | 0 | 2 | 0 | 0 | - |
| stocks-AAPL | unauthorized | vp-1280-dark | dark | 0 | 2 | 0 | 0 | - |
| stocks-AAPL | unauthorized | vp-1440 | light | 0 | 2 | 0 | 0 | - |
| stocks-AAPL | unauthorized | vp-1440-dark | dark | 0 | 2 | 0 | 0 | - |
| stocks-AAPL | unauthorized | vp-360 | light | 0 | 2 | 0 | 0 | - |
| stocks-AAPL | unauthorized | vp-360-dark | dark | 0 | 2 | 0 | 0 | - |
| stocks-AAPL | unauthorized | vp-768 | light | 0 | 2 | 0 | 0 | - |
| stocks-AAPL | unauthorized | vp-768-dark | dark | 0 | 2 | 0 | 0 | - |

## Assertion failures

_No assertion failures recorded._

## Console errors & page exceptions

| route | state | viewport | message |
|---|---|---|---|
| events | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| events | unauthorized | vp-1280 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-1280 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-1280-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-1280-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-1440 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-1440 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-1440-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-1440-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-360 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-360 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-360-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-360-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-768 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-768 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-768-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| events | unauthorized | vp-768-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| home | unauthorized | vp-1280 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-1280 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-1280-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-1280-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-1440 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-1440 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-1440-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-1440-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-360 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-360 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-360-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-360-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-768 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-768 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-768-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| home | unauthorized | vp-768-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| orders | unauthorized | vp-1280 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-1280 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-1280-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-1280-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-1440 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-1440 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-1440-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-1440-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-360 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-360 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-360-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-360-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-768 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-768 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-768-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| orders | unauthorized | vp-768-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| portfolio | unauthorized | vp-1280 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-1280 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-1280-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-1280-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-1440 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-1440 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-1440-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-1440-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-360 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-360 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-360-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-360-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-768 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-768 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-768-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| portfolio | unauthorized | vp-768-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| settings | unauthorized | vp-1280 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-1280 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-1280-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-1280-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-1440 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-1440 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-1440-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-1440-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-360 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-360 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-360-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-360-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-768 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-768 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-768-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| settings | unauthorized | vp-768-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1280 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1280-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1440 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-1440-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-360 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-360-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-768 | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | error | vp-768-dark | Failed to load resource: the server responded with a status of 500 (Internal Server Error) |
| stocks-AAPL | unauthorized | vp-1280 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-1280 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-1280-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-1280-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-1440 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-1440 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-1440-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-1440-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-360 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-360 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-360-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-360-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-768 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-768 | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-768-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |
| stocks-AAPL | unauthorized | vp-768-dark | Failed to load resource: the server responded with a status of 401 (Unauthorized) |

## Accessibility (axe) — violations by rule

_No axe violations recorded (or axe did not run)._

## User journeys

### analysis
- blockedAt: (none)
- analysisCreated=true
  - PASS analyze-button-present
  - PASS result-rendered

### error-recovery
- blockedAt: (none)
- dashboardAttempts=2
- consoleErrors: 1
  - PASS error-visible
  - PASS retry-control-present
  - PASS recovered-after-retry

### home-to-approval
- blockedAt: (none)
  - PASS home-review-action-actionable
  - PASS routes-to-orders-deeplink (http://localhost:3107/orders?order=order-active-1)
  - PASS approval-panel-auto-opens
  - PASS approval-panel-shows-requested-order

### login
- blockedAt: (none)
- consoleErrors: 3
  - PASS home-shows-login-prompt
  - PASS redirects-to-oauth (http://localhost:3107/oauth2/authorization/oidc?returnTo=%2F)

### orders
- blockedAt: (none)
- approvePostCount=1
  - PASS approve-button-present
  - PASS approval-figures-rendered
- PASS single-approve-post (actual: 1)
- PASS approve-body-matches-displayed
