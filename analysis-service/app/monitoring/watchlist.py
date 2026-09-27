from datetime import timedelta

from .models import WatchState, WatchlistItem


MAX_AGE = timedelta(minutes=30)
PREPARE_VOLUME = 1
CONFIRM_VOLUME = 1.25


def evaluate_watchlist(items: list[WatchlistItem], as_of) -> list[dict]:
    result = []
    for item in items:
        previous = item.status.value
        missing = [
            name for name, value in (
                ("price", item.price), ("volumeMultiple", item.volume_multiple),
                ("relativeStrength", item.relative_strength), ("source", item.source),
                ("asOf", item.as_of), ("collectedAt", item.collected_at),
            ) if value is None or value == ""
        ]
        fresh = (
            item.as_of is not None
            and item.collected_at is not None
            and item.as_of <= as_of
            and item.collected_at <= as_of
            and as_of - item.as_of <= MAX_AGE
        )
        if not fresh and not missing:
            missing.append("price.stale")
        state = previous
        evidence = []
        if not missing:
            if previous != WatchState.INVALIDATED.value:
                if item.price <= item.levels.invalidate.max:
                    state = WatchState.INVALIDATED.value
                    evidence.append({
                        "condition": "price_at_or_below_invalidation_band",
                        "price": item.price,
                        "invalidationMax": item.levels.invalidate.max,
                    })
                elif (
                    item.levels.confirm.min <= item.price <= item.levels.confirm.max
                    and item.volume_multiple >= CONFIRM_VOLUME
                    and item.relative_strength > 0
                ):
                    state = WatchState.ACTION_CANDIDATE.value
                    evidence.append({"condition": "confirm_price_volume_relative_strength"})
                elif (
                    (item.levels.prepare.min <= item.price <= item.levels.prepare.max
                     or item.levels.pullback.min <= item.price <= item.levels.pullback.max)
                    and item.volume_multiple >= PREPARE_VOLUME
                    and item.relative_strength > 0
                ):
                    state = WatchState.PREPARE.value
                    evidence.append({"condition": "prepare_or_pullback_price_volume_relative_strength"})
                else:
                    state = WatchState.WATCH.value
                    evidence.append({"condition": "joint_gate_not_met"})
            if item.source and item.as_of:
                evidence.append({
                    "source": item.source,
                    "asOf": item.as_of,
                    "collectedAt": item.collected_at,
                    "price": item.price,
                    "volumeMultiple": item.volume_multiple,
                    "relativeStrength": item.relative_strength,
                })
        result.append({
            "symbol": item.symbol,
            "previousState": previous,
            "state": state,
            "changed": state != previous,
            "evidence": evidence,
            "unknownFields": missing,
            "asOf": item.as_of,
        })
    return result
