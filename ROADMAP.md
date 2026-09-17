# RemotePhone Direct Roadmap

This file is the source of truth for the project roadmap and release checklist.

Implementation checkboxes mean the code/build work is complete. Physical-device validation is intentionally batched into **4.9 Unattended stress testing** unless a build itself fails.

## v0.3 — Remote control becomes usable

Status: ✅ Completed / baseline established

Core scope:
- Connection stability
- Screen quality
- Touch / swipe
- Typing
- Wake Host
- Host state detection
- Controller reconnection
- Host / Controller terminology

## v0.4 — Host becomes unattended

Primary objective: once a Host has been set up, it should stay reachable and recover from normal failures without someone standing next to the Host phone.

### v0.4 Core — must finish before v0.4 is complete

- [x] **4.1 Heartbeat / Host health** — periodic heartbeat, latency, last-seen time. Implementation complete; physical validation deferred to 4.9.
- [x] **4.2 Connection watchdog** — detect dead/stuck session and force the existing reconnect loop to recover automatically after prolonged loss of Host traffic. Implementation complete; physical validation deferred to 4.9.
- [ ] **4.3 Video watchdog / stale-frame detection** — distinguish video freeze from connection loss
- [ ] **4.4 Reliable foreground Host service** — remain alive during normal background / screen-off usage
- [ ] **4.5 Automatic Host-service recovery** — recover non-protected Host functions if Android kills the process/service
- [ ] **4.6 Relay self-recovery** — repeated relay disconnect/reconnect without stale sessions or manual restart
- [ ] **4.7 Network-change recovery** — recover across temporary Internet loss and Wi-Fi/mobile-data changes
- [ ] **4.8 Host state machine** — Ready / Sleeping / Locked / Stream unavailable / Needs capture approval / Offline / Reconnecting
- [ ] **4.9 Unattended stress testing** — reconnect, screen-off, idle, network loss, service failure and multi-hour soak tests

### v0.4.1 Hardening — can follow after v0.4 Core

- [ ] **4.10 Separate lightweight control channel** — status / wake / recovery path independent of video stream
- [ ] **4.11 Battery / OEM hardening** — OnePlus / Samsung / Xiaomi battery optimization and auto-start guidance
- [ ] **4.12 Advanced reboot recovery** — restore everything Android permits after reboot and clearly expose screen-capture approval state
- [ ] **4.13 Edge-case failure handling** — unusual OEM/network/process failure cases discovered in field testing

### Already present in the v0.4 codebase

- Foreground Host service
- `START_STICKY`
- Partial CPU wake lock
- Relay reconnect loop
- Controller reconnect loop after an established session drops
- Wake Host command
- Host Ready / Sleeping / Locked detection
- BootReceiver foundation
- Saved Host records with Remote ID, friendly name and pinned Host identity

### v0.4 acceptance target

A Host left at home/office should remain usable from a remote Controller with no physical interaction during normal conditions. The Controller should reconnect automatically after temporary network/session failures, clearly distinguish Host/video states, wake a sleeping Host where Android permits, and report when Android requires screen-capture approval.

## v0.5 — Multi-Host

- [ ] Host dashboard
- [ ] 2 / 5 / 10 Hosts
- [ ] Friendly naming
- [ ] Live online / offline / sleeping status
- [ ] One-tap connection

## v0.6 — Lock / sleep intelligence

- [ ] Better wake handling
- [ ] Distinguish sleeping vs secure-locked vs capture failure
- [ ] Investigate known PIN / pattern interaction where Android permits it
- [ ] Never bypass Android security controls

## v0.7 — Communication-app experience

- [ ] WhatsApp / Telegram / communication-app shortcuts
- [ ] Host-to-app mapping
- [ ] Launch target app after connecting
- [ ] Reduce unnecessary Android/system UI so remote access feels closer to using the communication app directly
