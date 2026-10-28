# Waypoints and shipyards never expire; markets have a short TTL

Cached waypoints and shipyards are served forever until a caller explicitly refreshes them; only markets carry a short TTL. Waypoints and shipyards change rarely and every live fetch spends the shared rate budget, so staleness is accepted there and refresh is an explicit, scoped action.

Source: `README.md#Nothing expires by default`
