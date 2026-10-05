# ဖီးနစ် VPN (Phoenix-VPN)

Lightweight WireGuard VPN for Android. No in-app scanner — endpoints are
auto-fetched from a published top-10 list, handshake-verified on the user's
own line, and the best 3 are kept.

- Package: `com.phoenix.phoenixvpn`
- Display name: **ဖီးနစ် VPN**
- Min SDK 24, target SDK 36, single universal APK

## Endpoint flow

1. `GET https://raw.githubusercontent.com/myokooo2004/Warp-IP-Scanner/main/endpoints.json`
   (schema: `{"v":1,"updated_at":"<ISO8601>","isp":"MPT","endpoints":[{"ip","port","ms","jitter_ms"}]}`)
2. Top-10 handshake-verified with the user's own `.conf` (tunnel-based, no scanner engine)
3. Best 3 kept by measured ms; #1 used, rest are failover
4. Fallback: fetched → cached → bundled → single manual endpoint slot

## VPN logic (ported from Tugyi-Wireguard)

- Master switch is purely user-controlled, never flipped programmatically
- Validated internet → auto-connect; no usable internet → auto-stop
- Manual VPN-off (incl. notification "ရပ်ရန်") sets a sticky user override
- rx-stall watchdog with bounded re-handshakes, then endpoint failover

## Build

Release APKs are built by CI (`.github/workflows/release.yml`) on every push
to `main`, signed with the release keystore from repo secrets.
