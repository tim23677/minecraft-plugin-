# Read-only REST interface

Off by default. It gives web pages and bots the market data, nothing can be changed through it.

```yaml
rest:
  enabled: true
  bind: "127.0.0.1"
  port: 8765
  token-env: "ORDERHUB_REST_TOKEN"
  requests-per-minute: 60
```

The token is read from the environment variable (at least 16 characters), it is never stored in the configuration. Without a valid token the interface stays off and the console says why. Every request needs `Authorization: Bearer <token>`.

Bind to a public address only behind a reverse proxy with TLS. The server answers `GET` only.

| Route | Content |
|---|---|
| `/v1/health` | `{"status":"ok","server":"<server-id>"}` |
| `/v1/market/orders?item=diamond&min=1&max=5k&sort=lowest-price&limit=50` | open orders without owner data, at most 100 |
| `/v1/market/price?item=diamond&days=7` | demand (open orders) and completed trades |
| `/v1/top?stat=earned&n=10` | anonymous ranking values |

Errors: 400 invalid parameter, 401 missing or wrong token, 404 unknown route, 405 other method, 429 too many requests or failed attempts (10 failed attempts per minute and client), 503 database not available.
