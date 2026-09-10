# agentpulse-android

Native Android client for AgentPulse observation, Codex interactions, and common remote controls.

[中文详细使用手册](../docs/USER_GUIDE.zh-CN.md) covers installation, pairing, phone controls, reconnects, and troubleshooting.

The app securely pairs with an `agentpulse` Host only by scanning its terminal QR code. The QR bootstrap uses either the authenticated public Relay or explicitly configured direct access, without USB, ADB, Bluetooth, or a shared LAN. Android stores only encrypted Host credentials in Android Keystore-backed storage and automatically selects the QR-supplied route. The Relay sees only authenticated routes and opaque, end-to-end Host TLS bytes. Session/Event, interaction, command, and prompt-queue views are process-only. The app supports exact approval options, atomic Plan/user-input forms including Other and secret fields, ordinary queued prompts, explicit steer, and the bounded common Slash Command set.

## Requirements and build

- Android 8.0 / API 26 or newer
- A camera for the only supported first-pairing path
- Android SDK Platform 37.0 and Build Tools 37.0.0
- JDK 21, matching the repository CI

```bash
./gradlew test lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

The debug APK is written under `app/build/outputs/apk/debug/`. CI validates the wrapper, tests the pure Kotlin protocol/reducer module, runs Android lint, builds the APK, and launches a smoke test on an emulator.

## Pair and connect

1. Configure the desktop Host Relay or direct endpoints, start the Host, then run `agentpulse pair` in another terminal.
2. Wait for the QR (Relay pairing first publishes its ephemeral route), then choose **Scan QR code** in the app and scan the single terminal QR.
3. Confirm the device name and UUID on the Host terminal.
4. Successful approval stores the Host credentials and starts connecting over the QR-selected route. Relay profiles can explicitly select LAN afterward when the Host listens on a reachable private address; allow local-network permission if the system requests it.

The initial route is an explicit property of the scanned QR. Route changes are explicit user actions; automatic reconnects stay on the selected route without silent fallback. The connected-device foreground service keeps the chosen route alive and retries it with bounded jittered backoff. LAN mode can rediscover a changed private endpoint with mDNS. Disconnect stops the service. Reconnect resumes each Session from its last in-memory cursor and fetches missing Events in 128-Event pages. Historical catch-up stays silent; a still-pending approval is notified once when the connection becomes live. Warning, failure, later live approvals, completion, and connection-loss notifications remain grouped; ordinary event traffic stays in the app.

Phone layouts use list/detail navigation; expanded windows use a two-pane timeline. Approval cards show exact targets and Provider options. Form cards preserve field order and submit all answers atomically; sensitive fields use password presentation. The composer suggests `/model`, `/resume`, `/clear`, `/plan`, and related common controls while ordinary text enters the Provider FIFO. English and Simplified Chinese resources, light/dark themes, dynamic color, and screen-reader descriptions for actions are included.

## Security and data boundary

- Initial WSS pairing trusts only the exact leaf SHA-256 in the QR bundle, either directly or inside a public Relay tunnel authenticated from the QR bootstrap Token.
- The persistent Native connection validates the stable Host DNS name against the app-scoped CA returned after local approval.
- Every Native upgrade sends the stable installation UUIDv7 and its per-device bearer token; the following Client Hello repeats the same identity.
- Relay route IDs and proofs are domain-separated HMAC values derived from the existing device credential and canonical Relay endpoint. The outer connection uses publicly trusted TLS with hostname validation; the inner Native connection still validates the Host CA and never exposes its bearer token or Session/Event plaintext to Relay.
- Host profiles and tokens are encrypted with AES-256-GCM using a non-exportable Android Keystore key. Backup is disabled.
- Forgetting a Host removes the local credential. Use `agentpulse devices revoke` to invalidate it on the Host as well.
- Complete Event history for the current Host run, Session snapshots, pending interactions, cursors, and submission correlation exist only in process memory. They survive connection loss for incremental repair and disappear on Android process death; a new Host run ID explicitly resets the matching cache. Form answers are held only long enough to create the outbound frame and are not stored in Reducer state. No Session/Event database is used.

## Signed releases

Tag builds require these GitHub Actions secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. The release workflow produces a signed, minified APK and AAB plus checksums/source archive. Local release signing uses the equivalent `AGENTPULSE_KEYSTORE_PATH`, `AGENTPULSE_KEYSTORE_PASSWORD`, `AGENTPULSE_KEY_ALIAS`, and `AGENTPULSE_KEY_PASSWORD` environment variables.

### Opt-in public Relay pairing regression

`PairingConnectionTest.liveQrConnectsWithoutCardAuthenticationError` runs on a
real device with a live terminal QR image saved in the app's private `files`
directory. Supply its filename as instrumentation argument `agentpulseQrFile`;
without it the test is skipped. The test launches MainActivity; approve the
device in the terminal while the test waits. The test uses ML Kit to decode
the image, then calls the foreground activity's actual MainViewModel and observes
the same ConnectionRuntime used by the connection card. It fails on any card
error and checks that the connection remains live for five seconds.

Set `agentpulseRePairConnected=true` to establish the saved connection before
re-pairing. This test exercises the real public Relay, TLS, credential storage,
and connection service; it does not exercise camera focus/capture. The image is
deleted after decoding, and neither the QR URI nor credentials are logged.

When re-pairing the currently connected Host, MainViewModel stops and awaits the
old connection before sending the pairing request. Terminal approval rotates the
device credential; the old connection must not retry that revoked credential
while the new pairing result is still in transit. A different Host's connection
is not stopped at this step. Pairing failure leaves the stopped connection for
explicit user recovery; success automatically connects using the new credential.

### Public direct QR pairing

The scanner supports legacy Relay v1 and direct discovery v2 without a mode
selection step. Direct profiles retain the public Native destination and use a
DIRECT route that bypasses Relay and mDNS on reconnect. Existing saved LAN and
RELAY profiles retain their routes. Configure the Host once using `agentpulse
direct configure`; see [Host setup](../agentpulse-rs/agentpulse-host/README.md#public-direct-pairing)
and [discovery v2](../agentpulse-protocol/pairing-v2.md).

The v2 golden bundle is mirrored in `protocol/src/test/resources/pairing-v2`.
The existing opt-in `PairingConnectionTest` can exercise a live direct QR through
ML Kit, pairing and the Native connection. This does not itself establish mobile
network reachability or camera optical capture; those require device acceptance.

### Direct cellular acceptance

`PairingConnectionTest` accepts `agentpulseExpectedRoute=direct` to require a
DIRECT profile with no Relay endpoint and a cellular default network without
VPN/Wi-Fi. `DirectConnectionTest.savedHostConnectsAndReconnectsOverCellular` takes
`agentpulseHostId`, optional `agentpulseExpectedAddress`/`agentpulseExpectedPort`,
and optionally `agentpulseSessionId` for a real message/reply check. Start the
formal MainActivity after this instrumentation runner starts; force-stop the app
beforehand to exercise persisted credentials on cold start. Keep the managed
control connection for the message test online. The test never imports tokens or
adds a production pairing entry point. See the [2026-09-10 acceptance report](../docs/validation/2026-09-10-public-direct.md)
for passed scenarios, earlier test-harness failures, and the unresolved response
case after the control connection exited.
