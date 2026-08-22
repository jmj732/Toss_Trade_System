# UI Audit — Raw Findings

Generated: 2026-08-19T11:56:00.032Z

## Summary

- State-matrix combinations recorded: **336**
- Combinations with assertion failures: **0**
- Combinations with console errors / page exceptions: **80**
- Total axe violations (all combos): **112**
- Journeys recorded: **5**

## Light vs dark

| scheme | combos | assertion failures | horizontal overflow | axe violations |
|---|---|---|---|---|
| light | 168 | 0 | 0 | 56 |
| dark | 168 | 0 | 0 | 56 |

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

| rule id | impact | combos | total nodes | sample selector |
|---|---|---|---|---|
| document-title | serious | 56 | 56 | #__next_error__ |
| html-has-lang | serious | 56 | 56 | #__next_error__ |

## User journeys

### analysis
- blockedAt: (none)
- analysisCreated=true
  - PASS analyze-button-present
  - PASS result-rendered

### error-recovery
- blockedAt: error-not-shown
- dashboardAttempts=0
  - FAIL error-visible
  - FAIL retry-control-present

### home-to-approval
- blockedAt: approval-panel-not-opened
  - PASS home-review-action-actionable
  - PASS routes-to-orders-deeplink (http://localhost:3311/orders?order=order-active-1)
  - FAIL approval-panel-auto-opens

### login
- blockedAt: (none)
- consoleErrors: 3
  - PASS home-shows-login-prompt
  - PASS redirects-to-oauth (http://localhost:3311/oauth2/authorization/oidc?returnTo=%2F)

### orders
- blockedAt: (none)
- approvePostCount=1
  - PASS approve-button-present
  - PASS approval-figures-rendered
  - PASS single-approve-post (actual: 1)
  - PASS approve-body-matches-displayed
