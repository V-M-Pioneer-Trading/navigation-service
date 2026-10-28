# Nothing expires by default; only markets carry a TTL

Waypoints and shipyards are kept until a caller explicitly asks for a refresh. Markets are the exception: prices move, so they carry a short TTL (`navigation.market.cache-ttl`, default 60 s).

Code: `src/main/java/de/vnm/navigation/service/ShipyardService.java:25`, `src/main/java/de/vnm/navigation/service/MarketService.java:28-29`

Source: `README.md:17-19`
