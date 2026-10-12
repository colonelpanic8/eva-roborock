# Roborock for EVA

An Android app that lets [EVA](https://github.com/colonelpanic8/eva) control a
Roborock robot vacuum. It is an EVA *installed extension*: EVA discovers it,
you enable its actions in EVA's Extensions screen, and EVA calls it over Binder,
including while the phone is locked (after the first unlock since boot).

Actions: `status`, `list_rooms`, `clean_rooms`, `clean_home`, `run_preset`,
`pause`, `return_to_dock`, `locate`, `invocation_status`.

The Roborock protocol code is a Kotlin port of the parts of
[python-roborock](https://github.com/Python-roborock/python-roborock) needed for
"1.0" (V1) robots: email-code sign-in, home data, and RPC over Roborock's MQTT
broker. No Roborock app internals or decompilation are involved.

## Setup

1. `just build && just install <adb-serial>`
2. Open **Roborock for EVA** and sign in with the emailed code, or import a
   desktop python-roborock session (push `~/.roborock`, optionally with `rooms`
   and `presets` fields, to the path the app shows, then tap *Import*).
3. Name rooms (`segment = Name, alias, …`; *Find rooms on the robot* lists the
   segments) and edit presets.
4. In EVA: Extensions → Roborock → turn on.

## Presets

```json
{
  "kitchen mop": {"rooms": ["kitchen"], "mode": "vacuum_and_mop", "suction": "max", "water": "high"},
  "whole home": {"rooms": [], "mode": "vacuum"}
}
```

`mode` is `vacuum`, `vacuum_and_mop` or `mop`; `suction` is `quiet`,
`balanced`, `turbo`, `max` or `max_plus`; `water` is `low`, `medium` or `high`;
`repeat` is 1–3.
