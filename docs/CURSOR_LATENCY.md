# Cursor Latency / Intermittent Lag — Root Cause & Fix

**TL;DR:** if the cursor feels mostly smooth but *intermittently* stutters or hitches,
it is almost always **Wi‑Fi power‑save batching packets** — on the **PC**, the
**phone**, or both. It is **not** the app's send path. Check it first:

```bash
iw dev <wifi-iface> get power_save     # on the PC, e.g. wlo1
```

If it says `Power save: on`, that's your culprit. Fix below.

---

## Symptom

- Cursor is smooth most of the time, then briefly lags / jumps / "catches up."
- Comes and goes; feels like "something is occasionally blocking the input."
- Everything else (clicks, typing, file transfer) works fine.

## Why it happens

The phone sends one tiny `M <dx> <dy>` line per pointer move (~60–120/sec). For a
smooth cursor these should arrive at the PC evenly, every ~8–16 ms.

**Wi‑Fi power‑save** parks the radio between beacon intervals to save battery. While
parked it **buffers packets and delivers them in bursts**. So even though the phone
hands each move to the OS immediately, the packets get **clumped**: several arrive
within <2 ms of each other, then a **stall of 60 ms – 1 s**, then another clump. The
PC injects the whole accumulated delta at once → the cursor jumps → it *feels* laggy.

`TCP_NODELAY` (which the app sets on both sockets) does **not** help — this is the
radio/MAC layer holding frames, not Nagle's algorithm.

Both endpoints can cause it independently. In our case **both** the PC (`wlo1`) and
the phone were on Wi‑Fi, and the **PC's RX power‑save was the dominant factor**.

## How it was diagnosed (measure, don't guess)

The app was suspected first, but measurement exonerated it:

1. **Timestamp arrivals at the PC.** With `-Dpcremote.debug=true`, log the arrival
   time of every command in `ControlServer.dispatch`. A smooth drag showed **bursts
   (<2 ms) then stalls up to ~1 s** instead of steady ~8–16 ms gaps → *bursty
   delivery*, not steady.
2. **Log the phone's writer batch size.** The single writer coroutine flushes each
   command as it's produced. It logged **`batch=1`, write time ≤2 ms for essentially
   every move** → the phone sends each move **immediately**; the app is optimal. So
   the burstiness must be *on the wire*, between the phone's socket write and the
   PC's read.
3. **Check the link.** `iw dev wlo1 get power_save` → `on`. That was it.

The clincher: the phone writes `batch=1` yet the PC receives clumps → the network,
not the app, is doing the batching.

## The fix

### PC (Linux) — the big one

Temporary (resets on reconnect/reboot):

```bash
sudo iw dev wlo1 set power_save off
```

Permanent (NetworkManager, per‑connection — replace with your Wi‑Fi name):

```bash
sudo nmcli connection modify "FreeFreeFree" 802-11-wireless.powersave 2
sudo nmcli connection up "FreeFreeFree"
# 2 = disable power-save, 3 = enable
```

Permanent (NetworkManager, global for all Wi‑Fi):

```bash
echo -e '[connection]\nwifi.powersave = 2' | sudo tee /etc/NetworkManager/conf.d/wifi-powersave-off.conf
sudo systemctl restart NetworkManager
```

Verify: `iw dev wlo1 get power_save` → `Power save: off`.

### Phone (Android) — belt-and-braces

The app requests a **low‑latency Wi‑Fi lock** while connected so Android keeps its
radio responsive:

- `AndroidManifest.xml` declares `android.permission.ACCESS_WIFI_STATE` (required, or
  `createWifiLock` throws and the lock silently never takes effect).
- `RemoteClient.acquireWifiLock()` holds `WifiManager.WifiLock` in
  `WIFI_MODE_FULL_LOW_LATENCY` (API 29+, else `FULL_HIGH_PERF`) while connected, and
  releases it in `closeQuietly()`.

> **Caveat:** this lock only takes effect while the app is foregrounded, and some
> devices ignore the hint. On the moto g67 test device `dumpsys wifi` still reported
> `0` low‑latency locks held even after the permission fix — so the PC‑side fix is
> what actually delivered the smoothness. The lock is kept because it's the correct,
> harmless pattern and helps on devices that honor it.

## Trade-off

Disabling Wi‑Fi power‑save costs a little battery/idle power on the PC (a laptop on
Wi‑Fi). For a device driving a remote‑control receiver that's a fine trade. On a
desktop, negligible.

## If it *still* lags after this

Then it's genuinely the link quality, not power‑save:

- Move closer to the router / use **5 GHz**; avoid a congested 2.4 GHz channel.
- Check `iw dev <iface> get power_save` on **both** ends.
- Re-run the arrival-timing measurement (step 1) — if gaps are steady now, any
  remaining "lag" is elsewhere (e.g. the display, not the input path).
