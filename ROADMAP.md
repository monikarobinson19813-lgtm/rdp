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
- [x] **4.3 Video watchdog / stale-frame detection** — distinguish a stale video stream from a dead Host connection using frame-age tracking while heartbeat remains healthy. Implementation complete; physical validation deferred to 4.9.
- [x] **4.4 Reliable foreground Host service** — explicitly remain active across normal background use, screen-off and UI task removal; reassert the foreground notification and CPU wake lock when needed. Implementation complete; physical validation deferred to 4.9.
- [x] **4.5 Automatic Host-service recovery** — remember that the Host should remain active, recover identity/Remote ID/PIN/foreground service/wake lock/local server/relay after Android recreates the service, expose capture-approval state, and resume screen capture when the user later re-approves it. Implementation complete; physical validation deferred to 4.9.
- [x] **4.6 Relay self-recovery** — enforce a single relay worker, discard stale relay transports/sessions, reconnect repeatedly and use bounded retry backoff during relay/network outages. Implementation complete; physical validation deferred to 4.9.
- [x] **4.7 Network-change recovery** — listen for Host connectivity changes and actively discard the stale relay/session so the relay loop reconnects over the new/returned network rather than waiting on the old transport. Implementation complete; physical validation deferred to 4.9.
- [x] **4.8 Host state machine** — canonical Controller states for Ready / Sleeping / Locked / Stream unavailable / Needs capture approval / Offline / Reconnecting, with heartbeat/video/session events mapped into those states. Implementation complete; physical validation deferred to 4.9.
- [ ] **4.9 Unattended stress testing** — reconnect, screen-off, idle, network loss, service failure and multi-hour soak tests

### v0.4.1 Hardening — can follow after v0.4 Core

- [x] **4.10 Separate lightweight control channel** — status / wake / recovery path independent of video stream. Implementation complete; physical validation deferred to 4.9.
- [x] **4.11 Battery / OEM hardening** — OnePlus / Samsung / Xiaomi battery optimization and auto-start guidance. Implementation complete; physical validation deferred to 4.9.
- [x] **4.12 Advanced reboot recovery** — restore everything Android permits after reboot and clearly expose screen-capture approval state. Implementation complete; physical validation deferred to 4.9.
- [x] **4.13 Edge-case failure handling** — reconcile stale capture-approval state when live frames prove capture is active; existing relay/network recovery retained. Implementation complete; physical validation deferred to 4.9.

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

- [x] Host dashboard — saved Hosts are presented in a dedicated Controller dashboard with saved-Host count.
- [x] 2 / 5 / 10 Hosts — Controller supports up to 10 saved Hosts with a hard capacity limit and capacity indicator.
- [x] Friendly naming — saved Hosts retain editable friendly names and display them in the dashboard.
- [x] Live online / offline / sleeping status — saved Hosts maintain a lightweight background status link and display current reachability/state.
- [x] One-tap connection — after one successful secure connection, the session PIN is stored encrypted with Android Keystore and a saved Host can reconnect with one tap while retaining fingerprint verification.

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
