# Roborock for EVA

Android app (Kotlin, no UI framework) that exposes a Roborock vacuum to EVA as
an installed extension. Protocol: EVA's `docs/extension-protocol.md`
("Installed-app AIDL protocol v1"); the AIDL files under
`app/src/main/aidl/com/colonelpanic/eva/extension/` are verbatim copies and part
of the ABI.

## Layout

- `api/`: Android-free Roborock client ported from python-roborock:
  `RoborockCloud` (email-code sign-in, home data, Hawk auth), `MiniMqtt`
  (minimal MQTT 3.1.1), `V1Codec` (1.0 frame + AES-ECB), `Robot` (RPC),
  `Cleaning`/`Presets` (rooms, modes, command plans).
- `eva/`: `Capabilities` (descriptor + execution + receipt states), `Journal`
  (invocation journal, written before any command is sent), `EvaExtensionService`
  (binder, bounded executor, caller verification against EVA's pinned release cert).
- `ui/MainActivity`: sign-in, session import, rooms, presets, status check.
- `Store`: app-private SharedPreferences; excluded from backup/transfer.

## Invariants

- Never resend a command for a replayed invocation ID; an unconfirmed start is
  `unknown`, never retried.
- `describe` does no network work. Changing the catalog bumps `CATALOG_REVISION`;
  the account scope is part of the descriptor revision.
- Credentials never leave app-private storage or appear in replies/logs.

## Checks

```sh
direnv exec . just fmt
direnv exec . just check       # ktlint, lint, JVM tests, debug build
direnv exec . just live-test   # read-only calls to the real robot via ~/.roborock
```
