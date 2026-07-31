---
name: polymarket
description: "Query Polymarket: markets, prices, orderbooks, history."
source: BUNDLED
platforms: jvm
---

# Polymarket — Prediction Market Data

Query prediction market data from Polymarket using their public REST APIs.
All endpoints are read-only and require zero authentication.

## When to Use

- User asks about prediction markets, betting odds, or event probabilities
- User wants to know "what are the odds of X happening?"
- User asks about Polymarket specifically
- User wants market prices, orderbook data, or price history
- User asks to monitor or track prediction market movements

## Key Concepts

- **Events** contain one or more **Markets** (1:many relationship)
- **Markets** are binary outcomes with Yes/No prices between 0.00 and 1.00
- Prices ARE probabilities: price 0.65 means the market thinks 65% likely
- `outcomePrices` field: JSON-encoded array like `["0.80", "0.20"]`
- `clobTokenIds` field: JSON-encoded array of two token IDs [Yes, No] for price/book queries
- `conditionId` field: hex string used for price history queries
- Volume is in USDC (US dollars)

## Three Public APIs

1. **Gamma API** at `gamma-api.polymarket.com` — Discovery, search, browsing
2. **CLOB API** at `clob.polymarket.com` — Real-time prices and orderbooks
3. **Data API** at `data-api.polymarket.com` — Historical price data

## Common Queries

All queries use `http_fetch(url=..., method="GET")`.

### Search for markets by keyword

```
http_fetch(url="https://gamma-api.polymarket.com/events?title_contains=KEYWORD&closed=false&limit=5", method="GET")
```

### Get a specific event

```
http_fetch(url="https://gamma-api.polymarket.com/events?id=EVENT_ID", method="GET")
```

### Get current price for a market

Extract the `clobTokenIds` from the market data, then:

```
http_fetch(url="https://clob.polymarket.com/price?token_id=TOKEN_ID&side=buy", method="GET")
```

### Get orderbook

```
http_fetch(url="https://clob.polymarket.com/book?token_id=TOKEN_ID", method="GET")
```

### Get price history

Extract the `conditionId` from the market, then:

```
http_fetch(url="https://clob.polymarket.com/prices-history?market=CONDITION_ID&interval=1d&fidelity=60", method="GET")
```

Interval options: `1d`, `1w`, `1m`, `3m`, `all`

### Browse active markets

```
http_fetch(url="https://gamma-api.polymarket.com/events?closed=false&order=volume24hr&ascending=false&limit=10", method="GET")
```

## Workflow

1. Search for markets using the Gamma API with relevant keywords
2. Parse the response to find matching events and their markets
3. Extract `clobTokenIds` for price queries or `conditionId` for history
4. Fetch current prices or historical data via CLOB API
5. Present probabilities and volumes in a clear summary to the user

## Notes

- All prices are probabilities (0.00 to 1.00). Multiply by 100 for percentage display.
- Markets can have 2+ outcomes. Each outcome has its own token ID.
- Volume figures are cumulative USDC traded.
- Rate limits are generous for read-only queries but avoid excessive polling.
