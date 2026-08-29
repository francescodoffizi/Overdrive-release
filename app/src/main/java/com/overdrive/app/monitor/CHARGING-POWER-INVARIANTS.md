# Charging-power resolution — funnel and invariants

Contract for the charging-power path. Check every proposed change against ALL
invariants below, not just the one that motivated it.

Reference evidence comes from bytecode inspection of three independently-built
BYD companion/launcher apps (2026-07-29), anonymised below as reference apps A/B/C,
plus device logs `log_X5RRX996` (Sealion/Tang-class DM-i PHEV, 21.5 kWh nominal,
2026-07-28) and `log_F2ZQH7CC` (BEV, 2026-07-29).

## Funnel

| Stage | Where | Produces |
|---|---|---|
| **C0** HAL access | `BydDataCollector.initDevice` → `BydManagerChannel` | device handle + activation; manager-level `getDouble/getInt` fallback |
| **C1** HAL read | `BydDataCollector.collectCharging` / `collectInstrument` / `collectEngine` | `chargingPowerKw`, `chargePowerKw`, `externalChargingPowerKw`, `clusterChargePowerKw`, `enginePowerKw` |
| **C2** Fused detect | `ChargingDetector` | `isCharging()` verdict (L1 BMS edge, L2 `Power.isCharging`, L3 inference) |
| **C3** Resolve | `VehicleDataMonitor.getChargingState()` | `chargingPowerKW` + `isEstimated` |
| **C4** Estimate | `ChargingPowerEstimator` | SOC/counter-derivative fallback kW |
| **C4a** Unit calibration | `CounterScaleCalibrator` (fed from `BydDataCollector`, same tick as C4) | per-source register-width factor + pre-verdict suspicion (see I8) |
| **C5** Persist | `ChargingSessionManager.sampleOnce` → `SocHistoryDatabase` | CPS ramp curve → `energy_added_kwh`, `avg_power_kw`, `session_cost` |

## Invariants

- **I1 — Dedicated charging-device kW is primary on every drivetrain.**
  `chargingPowerKw` comes from the charging device's `getChargingPower()` property, whose SDK
  contract is already-normalized signed kW. It must outrank cluster, external, instrument,
  counter-slope, and inferred sources for BEV and PHEV alike. The older `getChargePower()` method
  on that same device is only a compatibility alias when the primary method is absent; the
  instrument device's similarly named field remains a guarded fallback with different semantics.
  The dedicated value does not require unit learning, motion, or a changing waveform before it can
  be persisted. If it is unavailable, the existing cascade and all of its scale/quality gates still
  apply. `enginePowerKw` and nominal/SOC-derived figures remain estimates and must stay behind every
  measured charger-side source.
- **I2 — Never publish a confidently-wrong measured-looking value.** An honest
  estimate flagged `isEstimated` beats a precise-looking wrong number. A *stuck*
  value is not a measurement.
- **I3 — `isEstimated` must never enter the CPS curve.** `ChargingSessionManager`
  skips estimated samples; C5 integrates the curve into energy AND cost, so a
  poisoned curve corrupts money. This is the load-bearing guard for I5.
  Every branch that publishes a value it did not read from a charger-side getter MUST
  set the flag — including `enginePowerKw(phev)` (`abs()` of the motor's own figure is
  an inference, not a measurement). Consumers that chart or persist power must honour
  it too: `getOpenChargingSessionTimeToFullMin` (it LATCHES into the session row),
  ABRP, and MQTT all gate on `!isEstimated`. Adding a new consumer of
  `chargingPowerKW` without that check reopens this.
- **I4 — Sentinels never become values.** Known BYD sentinels: `-10011`,
  `-2147482645`, `-2147482648`, `65535`, `104857.5`, and observed idle junk
  `~359.x` on ambiguous instrument fields. A flat value is suspicious only for those ambiguous
  fields; a steady dedicated AC charging rate is valid measured power and must not be rejected for
  lack of movement.
- **I5 — Cost/energy rows are immutable snapshots.** Fixing power improves FUTURE
  sessions only. Never retroactively reprice global-rate-priced history.
- **I6 — No circularity in the C1 gate.** The `clusterChargePowerKw` publish gate
  may consult `ChargingDetector`, because the detector reads `enginePowerKw`,
  `externalChargingPowerKw`, `chargingPowerKw` and BMS state — never
  `clusterChargePowerKw`. Do not feed cluster-derived data back into C2.
- **I7 — V2L / regen is not charging.** Gun state `5` = V2L (pack discharging);
  only `2` (AC), `3` (DC), `4` (AC_DC) are charging-plausible. A falling counter
  is never charging power.
- **I8 — The yardstick is not exempt from verification.** `getChargingCapacity`'s kWh unit is
  *documented*, and the whole scale-checking apparatus treated that as proof — `isScaleVerified`
  returned `true` for `SRC_CAPACITY` unconditionally and `referenceRateKw()` fed its slope to every
  other source's veto. Log `AL37RNJ9` (BEV, 2026-08-09) disproves the premise: the register advanced
  10.675 kWh while remaining pack energy advanced 20.200 kWh over the same 1.900 h. Because the
  faulty source WAS the yardstick, its 5.61 kW slope vetoed the three accessors reading ~10.05 kW
  (ratio 1.79 > `RATE_VS_COUNTER_MAX_RATIO` 1.35) — the broken sensor suppressed exactly the evidence
  that would have exposed it, then got priced. Any source used to judge others must itself be
  judged; `CounterScaleCalibrator` does it against remaining pack energy, which the counter does not
  feed. **Withholding is the safe direction:** while a fault is suspected but unproven, publish
  nothing rather than a probably-wrong figure — the cascade falls to another source or a flagged
  estimate, both recoverable, whereas a factor error is billed.

- **I9 — Confirmed AC cross-validates battery counters.** On the Atto 3, `remainKwh`
  cycles and jumped 1.5→6.6 kWh in one poll while `chargingCapacityKwh` rose smoothly
  at the granny charger's rate. The old remain-first rule produced a false 155.5 kW
  peak, persisted 41.6 false kWh, and classified an explicit AC session as DC. When
  gun state is AC (`2`), C4 waits briefly for both present counters, rejects slopes
  above the physical AC ceiling, and selects capacity only when remain is more than
  4× the simultaneous capacity slope. The 4× guard preserves the known half-scale
  capacity counter on the Seal, where 2:1 still selects remain. DC and unknown gun
  states retain remain-first selection. A transient disconnected state (`1`) with
  fused charging publishes no estimate until the connector byte catches up, so an
  unvalidated pre-connection sample cannot poison the session curve.

## Load-bearing asymmetries (do NOT "simplify")

1. **Unit scale is per-firmware and genuinely ambiguous.** A reference app reads the same
   feature id (`CHARGING_CHARGE_POWER_DD` = `0x32300018`) and applies NO scaling —
   verified in bytecode: zero `div-double`/`mul-double` in the whole method, only
   constants `0.0`, `0.0`, `500.0`. Reference apps A and C likewise never scale power.
   BUT our own field captures on a Seal U DM-i recorded **221.7 raw for ~1.9 kW**
   and **189.5 raw for ~1.8 kW** — hectowatts. Both cannot be one unit, so
   `scaleClusterChargePowerKw`'s magnitude split stays until a per-trim capture
   settles it. **Deleting the divide regresses the trim we measured.** The
   ambiguity is contained by the CONSUMER: PHEV-only, because a PHEV onboard
   charger cannot reach the ambiguous 22–500 raw band.
2. **Engine power scales, charging power does not.** A reference app applies
   `raw > 100 ? raw * 0.1 : raw` with a *signed* band `[-200, 400]` to ENGINE_POWER
   (ours matches at `BydDataCollector:1967`) and deliberately applies neither to
   charging power (unsigned band `(0, 500]`). Never port a motor-path scale onto
   charging power: a magnitude rule is harmless on a BEV at 120 kW and catastrophic
   on a PHEV at 3.3 kW — precisely the BEV-right/PHEV-wrong signature.
3. **PHEV distrusts the hardware getters; BEV trusts them.** On PHEV
   `getExternalChargingPower` reports the EVSE's RATED capacity (observed a flat
   `6.50` for 18 consecutive samples while SOC climbed 97→100%, and a flat 7.13 on
   a real 1.7 kW charge), and `getChargePower` returns `0.00`. A second reference app corroborates
   that `getExternalChargingPower` is not a live kW rate — it snapshots it at
   session start/end and *subtracts* the two, i.e. treats it as a cumulative kWh
   meter. A third treats it as instantaneous kW. They cannot both be right;
   PHEV must not depend on it either way.
4. **Do not derive power from `getBatteryCapacity` / `getBatteryPowerHEV`.** On our
   PHEV these read `41.00` (~2× the 21.5 kWh nominal) and a frozen `11.20`. A reference app
   is immune only because it uses them for DISPLAY and never as a divisor.
   Anything that multiplies SOC% by a capacity figure inherits both errors.
5. **Coarse-counter rules are PHEV-only.** A 1% SOC step on a 21.5 kWh pack is
   0.215 kWh; below ~1.3 kW a step takes longer than the 10-min window, so a
   clock-only eviction can starve the ring permanently. BEV's `remainKwh` counter is
   fine-grained, so its ring rules stay untouched (I1).
6. **Freshness means the DATA moved, not that the maths succeeded.** Once `minPoints`
   shields old points from eviction, a frozen counter re-derives the *same* slope
   forever. Stamping `lastDeriveMs` on a successful derive therefore re-armed the
   expiry indefinitely (measured: 8.60 kW republished unchanged for 85+ min). It must
   only be stamped when a ring actually appended a point. Any future "derive succeeded"
   signal has the same trap.
7. **A publication floor is not an eviction floor.** `minPoints` gating only eviction
   left `size() < 2` as the publish gate, so the two-point state it existed to prevent
   stayed reachable — and the baseline-anchoring fallback then measured gauge-flip
   timing rather than power (154.8 kW at the 5 s cadence on a true 1.7 kW charge).
   Coarse rings must refuse to guess; only fine-grained rings may use that fallback.
8. **The C3 power cascade is gated on `effectiveState == CHARGING`.** Relaxing a
   collector-side (C1) suppression is INERT on its own whenever the detector reads
   false — the stored value simply never gets read. A C1 relaxation aimed at a
   detector-false scenario must be paired with a matching C3 admission (the
   `taperOverride`), or it does nothing but extend the value's lifetime in the snapshot.
9. **`enginePowerKw` has THREE writers.** `collectEngine`'s feature-ID path, its typed
   getter, and `onEngineCallback`'s generic ENGINE_POWER event. The listener is the
   DOMINANT one on PHEV firmware (typed listeners dormant; parked polls are 90 s).
   Any filter on this field must be applied at all three, or it is cosmetic. Note only
   the poll path calls `pushChargingEvidence`, so clearing a "stale" value on the poll
   path must honour a recent listener write (`lastEnginePowerListenerMs`) or it will
   discard the only live reading and can BREAK detection rather than fix it.
10. **Carry the AGE with the value, never re-stamp on read.** `toBuilder()` makes every snapshot
   field non-NaN forever after one successful read, so any consumer that stamps its own clock on
   "value present" has no freshness check at all. `BydVehicleData.enginePowerAtMs` is set by the
   Builder setter itself (and zeroed on a NaN write) and COPIED — not re-stamped — by
   `toBuilder()`; `ChargingDetector` reads that instead of `now()`. A per-writer TTL boolean is
   not a substitute: an independent TTL longer than the consumer's window silently recreates the
   phantom for every age between the two, which is exactly what a 120 s TTL against a 15 s
   window did. Keep the producer's TTL equal to the consumer's window.
11. **A state code is not a place to smuggle a hint.** Overwriting `effectiveState` to CHARGING to
   unlock the power block destroyed the FINISHED signal that `status`/`stateName`/`isError` derive
   from — which collapsed the API's `full`/`plugged` flags (a full plugged-in car read "Idle") and
   made `SocHistoryDatabase`'s `!isCharging && wasCharging` session-close test unreachable, leaving
   the row open with its peak/avg advancing off a phantom. Side-channel it instead
   (`ChargingStateData.isTaperCharging`) and let every state consumer keep seeing the truth.
12. **In-band is not alive.** `clusterChargePowerKw` is sticky (the feature id keeps answering its
   last in-band value after the gun comes out) and nothing else ages it, so any admission test
   built only on "value is in range" latches forever. Require recent CHANGE
   (`TAPER_CLUSTER_TTL_MS`), which is asymmetry 6 applied to a different field.
13. **Liveness must belong to the SELECTED source, not to any source.** The published rate comes
   from one priority-selected ring, so an any-ring freshness disjunction let a still-ticking
   counter vouch for a frozen one: near full the SOC gauge sticks while `chargingCapacityKwh`
   keeps ticking, which re-armed a stale `socE` slope every cycle (simulated: 3.44 kW held for
   76 min against a true 0.50 kW taper). Capture the flag per call, then carry the selected
   source's flag. This is asymmetry 6 a third time — first "the maths succeeded", then "some
   data moved", now "the wrong data moved".
14. **`SOC_MIN_RING_POINTS`, `SOC_MAX_SPAN_MS` and `STALE_ESTIMATE_MS` are ONE tuple.** The floor
   needs N−1 quanta inside the span (the baseline consumes an interval), so raising the floor
   without widening the span silently mutes the low-taper regime — at 4 points / 90 min the
   publication floor was ~0.43 kW, so a genuine 0.3–0.4 kW CV taper published *nothing*. And
   `STALE_ESTIMATE_MS` must stay ABOVE `SOC_MAX_SPAN_MS`: that ordering is what makes the
   destructive expiry undefeatable (points are evicted before the expiry fires, so an emptied
   ring cannot re-derive its old slope). Current tuple: 4 / 135 min / 150 min → floor ~0.29 kW.
15. **A coarse ring needs dither tolerance.** A 1%-quantised gauge wobbles across a boundary near
   top-of-charge; treating a single-quantum drop as a session reset wiped the ring, which now
   costs 3 quanta (~129 min at 0.3 kW) to rebuild — so dithering faster than that silenced the
   estimator for the rest of the session. Absorb a one-quantum drop (`DITHER_TOLERANCE_KWH`),
   clear on anything larger. Coarse-only: on a fine-grained BEV counter a real decrease IS a
   reset (I1).
16. **There is more than one way into the HAL (C0).** A getter reading NaN does not prove the data
   is absent — it may prove we never had a working handle, or never activated the device. Two
   independently-built reference apps use channels we did not: explicit device ACTIVATION
   (`enableDevice`, both an `IBYDAutoDevice` shape and an `int deviceType` shape), manager-level
   `getDouble(deviceType, featureId)` as a second read entry point, and a 3-tier acquisition
   ladder (`DeviceManager.getDevice(type)` → per-device `getInstance` → raw constructor) where we
   had only the middle tier. The BEV capture that motivated this had the ENTIRE charging surface
   NaN simultaneously — four getters plus the feature id — which fits one dead handle far better
   than four independent HAL bugs. All of it is wired as FALLBACK ONLY (`BydManagerChannel`):
   tier 1 and the per-device read run first and unchanged, so a working trim is byte-identical.
   Note feature-ID coverage is NOT the gap — all 7 charging/power IDs in the SDK table are already
   read, and no pack-current / V×I channel exists anywhere in the mapped API.
17. **A capability keyed by deviceType cannot run before you hold a handle.** The manager
   acquisition tier and both manager-level reads key on `deviceTypeOf(device)`, which needs an
   INSTANCE (`getDevicetype()`) — so on first acquisition there is nothing to key with and the tier
   is *statically* dead. It cannot be fixed with a hardcoded className→type table: the bundled
   compile-time stubs report placeholder types (several duplicate `1007`, most return `0`), so a
   table built from them is actively wrong. Learn the real type off a live handle
   (`BydManagerChannel.rememberDeviceType`) and keep it across re-init — a className→type mapping
   is a property of the platform, not of a Context or binder.
18. **Latched handles must be dropped when the Context changes.** `init()` is re-entered with a NEW
   Context (the ACC-ON path exists precisely because the daemon can start with a broken synthetic
   one). Manager handles resolved from the old Context are latched once-per-process, so without an
   explicit `BydManagerChannel.invalidate()` in `init()` the channel stays permanently dead on
   exactly the "0/17 devices" vehicle it was built for. Also: a diagnostic list like
   `availableDevices` is the project's "is the HAL alive" readout — a fallback-acquired handle must
   be TAGGED there, or a capture reads 17/17 available with the data still absent and the
   "no handle" vs "handle but dead" distinction is destroyed.

19. **Instrumentation that R8 strips is not instrumentation.** The HAL-access diagnostics are the
   whole payoff of C0, and in the `release` (alpha) variant they were unreachable three ways over:
   the Gradle auto-detect adds `proguard-rules-strip-logs.pro` (R8 deletes the `info()`/`debug()`
   calls), `strip-console` is always applied, and no BYD telemetry tag was in
   `DaemonLogConfig.ENABLED_TAGS`. A braveheart build sees them; the variant customers actually run
   did not — so the "one capture settles it" claim was false for the very owner whose vehicle
   motivated the work. Fixed with `DaemonLogConfig.BYD_TELEMETRY`, which both keeps the calls (the
   auto-detect regex matches any `= true` flag) and opens the tags. Before promising a capture,
   check which variant the reporter is on.
20. **Stubs in `android.hardware` need explicit keeps; the `bydauto.**` rule does not cover them.**
   `IBYDAutoDevice` and `IBYDAutoEvent` sit directly in `android.hardware`. R8 will not delete a
   class referenced from a kept member's descriptor, but it will RENAME it — and a renamed type in
   an override's descriptor means the override stops overriding the platform method, so the HAL
   dispatches to its own no-op base and the callback silently never fires. Release-only, invisible
   in debug. The stubs themselves are inert at runtime (Android's classloader is parent-first, so
   the framework class always wins); the keeps matter for app classes that extend/implement them.

21. **A diagnostic flag must carry the LEVEL and every tag in the causal chain.** `BYD_TELEMETRY`
   opened two tags and survived R8, but `DaemonLogger`'s `minLevel` defaults to INFO and
   `withMinLevel()` was never called anywhere — so every `debug()` line was dropped at runtime. All
   the lines explaining *why* a read failed (accessor-width misses, "Could not resolve deviceType",
   manager-unavailable reasons) are debug-level, and `BydDeviceHelper` — which owns most of them —
   was not even in the tag set. The capture showed the symptom and never the cause. A flag that
   enables logging must also lower the level and include every class on the failure path.
22. **Never cache a failure that can be transient.** `resolveDeviceType` cached `Integer.MIN_VALUE`
   permanently, keyed by class, with no expiry. One dead-binder moment or one probe under a broken
   synthetic Context therefore became a process-lifetime outage: manager reads short-circuit,
   `rememberDeviceType` can never learn the class, activation reports unknown-type, and the
   acquisition fallback is skipped — so a later re-init with fresh handles could not recover, on
   exactly the vehicle the channel exists for. Cache successes (a type is immutable per class);
   retry failures. And a cache mutated from poll/HTTP/HAL threads must be a `ConcurrentHashMap` —
   a plain `HashMap` can corrupt its table under concurrent `put`.
23. **`init()` re-entry must not stack HAL listeners.** `registerAllListeners()` has ~22 register
   sites and no unregister path, and the device accessors are singletons — so an ACC-ON re-init
   re-registered on the SAME handles and duplicated every door/charging notification. Guard on
   handle IDENTITY, not a bare "already done" flag: if the platform genuinely returns new objects
   the old registrations are orphaned and re-registering is mandatory, so only identity
   distinguishes "duplicate" from "required".

24. **A threshold whose denominator the bug corrupts is a ratchet, not a guard.**
   `SessionEnergyResolver`'s `HALF_SCALE_MAX_RATIO = 0.55` tests `metered / socEstimate`, but
   `socEstimate` is `socDelta x nominal x SOH/100` — and every halved session drags SOH down, which
   shrinks the estimate and moves the ratio UP, away from the band. Log `AL37RNJ9` measured 0.58 on a
   counter independently proven to be running at exactly half: past the band, so the session was
   priced at ~half AND offered for calibration, where it computed 55.8% pack health (only the
   `MIN_PLAUSIBLE_SOH_PERCENT` floor rejected it — the guardrail caught it, the logic did not). Each
   such session makes the next one harder to catch. A scale test must key off something the fault
   cannot move: hence `counterScaleSuspect`, decided against a register the SOH never touches. The
   band remains as a fallback, not as the only defence.
25. **Two figures sharing one fault are not a cross-check — but check which way that fails.**
   `integrateSessionEnergyKwh` sums the PUBLISHED `power_kw` samples, and the counter's own slope can
   win the power cascade, so on a half-scale trim the integral is halved too. The instinct is to gate
   the counter-vs-integral test on independence — but that is **wrong**, and the existing tests catch
   it: reaching `metered/integrated <= 0.55` REQUIRES the integral to be ~2x the counter, which a
   contaminated integral cannot be. Contamination costs a MISS, never a false correction, so the test
   is self-validating and needs no flag; the miss is covered by I8's behavioural verdict instead.
   Before adding an independence guard, work out whether the shared fault produces a false POSITIVE
   or merely a false negative — only the former needs gating.
26. **A cumulative counter's correction belongs at the READ boundary, not inside the accumulator.**
   `ChargeCounterAccumulator`'s wrap/saturation/reset arithmetic keys off the register's real modulus,
   and `counter_energy_kwh` round-trips through the database in the counter's own frame — so scaling
   inside `observe()` would corrupt wrap detection and double-apply on every restore. All four close
   paths (session end, feature-disable, stale-finalize, live in-progress) therefore convert through
   one helper (`meteredEnergyKwh` / `correctStoredCounterEnergy`), which is also what keeps the live
   card from disagreeing with the row it becomes.
27. **Suspicion floors must clear the registers' quantisation skew.** The two registers step at
   different quanta (~0.1 kWh vs ~0.2 kWh observed), so whichever ticks first makes the opening ratio
   wildly wrong on hardware with NO fault — 0.09 against 0.20 reads as 2.2x. Since suspicion
   *withholds* the published rate, a noise-floor threshold would blank the power card for the first
   minutes of every charge on healthy trims. `MIN_SUSPICION_KWH = 0.5` per series is deliberately far
   above `MIN_STEP_KWH`; simulation over the captured series shows the real fault is still suspected
   at ~6 min and decided at ~21 min, with the correct 10 kW sources covering the interim.
28. **Process-wide verdict caches leak across test classes.** Gradle shares one JVM, so calibrating
   the real `SRC_CAPACITY` key in a calibrator test doubled the yardstick for every suite feeding
   `observeCounterForScale(SRC_CAPACITY, ...)` and failed four unrelated power tests. Use a private
   source key and reset before AND after each test. Same hazard as `ChargeSourceClassifier`'s
   persisted verdicts.

## Accepted bounded residuals

- The 22 kW `CLUSTER_AC_KW_CEILING` split mis-reads raw `(7.5, 22]` on a
  hectowatt-reporting PHEV trim (would show e.g. 15 kW instead of 0.15 kW).
  Tightening to a PHEV-only ~7.5 kW ceiling would close it but breaks any PHEV
  that DC-charges. Left as-is pending a per-trim raw capture.
- SOC-derivative accuracy is bounded by the 1% gauge quantum. Averaging over ≥2
  quanta reduces the per-step beat but cannot beat the gauge's resolution.
