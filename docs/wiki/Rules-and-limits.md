# Rules and limits

All rules are optional and off by default. Values are read by `/orderadmin reload`, invalid entries are skipped with a warning in the console.

## Price range per item

```yaml
items:
  price-limits:
    diamond:
      min: "5"
      max: "500"
```

The range is checked when an order is created, in the menu and again in the service, so other plugins using the API cannot bypass it. The general limits `orders.min-unit-price` and `orders.max-unit-price` still apply.

## Fee tiers per permission

```yaml
fees:
  create-fee-bp: 100
  tiers:
    vip:
      permission: orderhub.fee.vip
      create-fee-bp: 50
```

A player with the permission pays the lower creation fee. The lowest fee that applies wins. A tier can only lower the fee, a tier with a higher rate is ignored. The fee shown in the confirmation is the fee that is charged.

## Time windows

```yaml
events:
  timezone: Europe/Berlin
  windows:
    weekend:
      days: [SATURDAY, SUNDAY]
      from: "00:00"
      to: "23:59"
      create-fee-bp: 0
      sales-tax-bp: 100
```

In a window the creation fee or the sales tax is replaced by the given value. A window with `from` after `to` runs over midnight. Without `days` it applies every day. If the window opens or closes between the confirmation and the charge, the confirmation is refused and nothing is charged.

## Daily limits

`limits.daily-orders` limits new orders per player and 24 hours, `limits.daily-delivery-items` limits delivered items. 0 switches the limit off. The limit is checked before any money or items move.

## Price plausibility

`anti-exploit.price-deviation-factor: 5` refuses a new order whose unit price is more than 5 times above or below the median of the recent trades of the item. The check needs at least `market.min-trades-for-recommendation` trades. Players with `orderhub.bypass.price-check` skip it.

## Suspicious pairs

`anti-exploit.pair-trades-per-day: 10` writes an audit entry (`suspicious.pair`), tells online administrators with `orderhub.admin.view` and fires the API event `SuspiciousTradeEvent` when one player delivers that many times to orders of the same player within 24 hours. Nothing is blocked.

## Command limit

`orders.query-interval-ms` is the minimum time between two lookups of one player (market, sell, info, stats, history, analytics, favorites, mine, claims, returns).
