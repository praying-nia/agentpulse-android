# agentpulse-android

Native Android client for the complete read-only AgentPulse path.

The app securely pairs with an `agentpulse` Host over nearby BLE or QR, stores only encrypted Host credentials in Android Keystore-backed storage, and connects through either authenticated private-LAN WSS or an explicitly configured public Relay. The Relay sees only an authenticated route and opaque, end-to-end Host-CA TLS bytes. Both routes reuse strict Native v1 discovery/baseline subscription and the same live Session/Event timeline. The app never persists Session/Event data and exposes no approval, input, command, or Provider write-back behavior.

## Requirements and build

- Android 8.0 / API 26 or newer
- Android SDK Platform 37.0 and Build Tools 37.0.0
- JDK 17 or newer

```bash
./gradlew test lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

The debug APK is written under `app/build/outputs/apk/debug/`. CI validates the wrapper, tests the pure Kotlin protocol/reducer module, runs Android lint, builds the APK, and launches a smoke test on an emulator.

## Pair and connect

1. Initialize and start the desktop Host, then run `agentpulse pair` in another terminal.
2. In the app choose **Pair nearby**. Android Companion Device Manager restricts discovery to the AgentPulse service and establishes the OS BLE association before the secure GATT read. If BLE is unavailable, choose **Scan QR code**.
3. Confirm the device name and UUID on the Host terminal.
4. Select the saved Host and choose **Connect**. Android 16/API 37 also asks for local-network access; Android 13+ asks for notification permission.
5. To use the optional public path, save the Relay endpoint supplied by the Host operator and explicitly select **Relay**. Select **LAN** to return to direct private-network access.

Connection and route choice are always explicit user actions; there is no silent LAN/Relay fallback. The connected-device foreground service keeps the chosen route alive and retries it with bounded jittered backoff. LAN mode can rediscover a changed private endpoint with mDNS. Disconnect stops the service. Warning, failure, read-only interaction, completion, and connection-loss notifications are grouped; ordinary event traffic stays in the app.

Phone layouts use list/detail navigation; expanded windows use a two-pane timeline. English and Simplified Chinese resources, light/dark themes, dynamic color, screen-reader descriptions for actions, and a read-only capability marker are included.

## Security and data boundary

- Initial WSS pairing trusts only the exact leaf SHA-256 in the BLE/QR bundle.
- The persistent Native connection validates the stable Host DNS name against the app-scoped CA returned after local approval.
- Every Native upgrade sends the stable installation UUIDv7 and its per-device bearer token; the following Client Hello repeats the same identity.
- Relay route IDs and proofs are domain-separated HMAC values derived from the existing device credential and canonical Relay endpoint. The outer connection uses publicly trusted TLS with hostname validation; the inner Native connection still validates the Host CA and never exposes its bearer token or Session/Event plaintext to Relay.
- Host profiles and tokens are encrypted with AES-256-GCM using a non-exportable Android Keystore key. Backup is disabled.
- Forgetting a Host removes the local credential. Use `agentpulse devices revoke` to invalidate it on the Host as well.
- Session snapshots and the latest 256 Events per Session exist only in process memory and disappear on disconnect/process death.

## Signed releases

Tag builds require these GitHub Actions secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. The release workflow produces a signed, minified APK and AAB plus checksums/source archive. Local release signing uses the equivalent `AGENTPULSE_KEYSTORE_PATH`, `AGENTPULSE_KEYSTORE_PASSWORD`, `AGENTPULSE_KEY_ALIAS`, and `AGENTPULSE_KEY_PASSWORD` environment variables.
