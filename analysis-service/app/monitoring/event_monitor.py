from .models import MaterialEvent


OFFICIAL_SOURCES = {"SEC", "COMPANY_IR", "EXCHANGE", "GOVERNMENT"}


def evaluate_events(events: list[MaterialEvent], as_of) -> list[dict]:
    result = []
    for event in events:
        timestamped = event.published_at <= as_of and event.collected_at <= as_of
        material = timestamped and event.official and event.source_type.value in OFFICIAL_SOURCES
        result.append({
            "sourceEventId": event.source_event_id,
            "symbol": event.symbol,
            "material": material,
            "thesisRecheckRequired": material,
            "kind": event.kind.value,
            "evidence": event.evidence,
            "source": event.source_type.value,
            "sourceUrl": event.source_url,
            "publishedAt": event.published_at,
            "collectedAt": event.collected_at,
        })
    return result
