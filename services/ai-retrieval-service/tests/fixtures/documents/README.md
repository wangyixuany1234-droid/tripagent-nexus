# Redacted document fixtures

These synthetic files contain no real customer or supplier data.

| File | Scenario |
| --- | --- |
| `hotel-confirmation.png` | Complete hotel confirmation |
| `flight-confirmation.jpg` | Complete flight confirmation |
| `attraction-ticket.webp` | Attraction ticket |
| `missing-date.png` | Hotel screenshot with dates intentionally omitted |
| `duplicate-orders.pdf` | Two copies of the same confirmation number |

The integration suite runs these files through strict MIME and structural
validation. `DOCUMENT_PARSER_MODE=mock` is explicit in tests, so fixture semantics
remain deterministic without presenting Mock output as a live model result.
