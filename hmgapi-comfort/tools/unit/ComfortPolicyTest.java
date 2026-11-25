package dev.lemoyne.comfort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Off-car unit tests for ComfortPolicy, ComfortConfig, and OccupantSignalProbe.
 */
public final class ComfortPolicyTest {
    private static int fail;

    private static void check(String what, Object want, Object got) {
        String w = String.valueOf(want), g = String.valueOf(got);
        if (w.equals(g)) {
            System.out.println("ok    " + what);
        } else {
            System.out.println("FAIL  " + what + ": wanted [" + w + "], got [" + g + "]");
            fail = 1;
        }
    }

    private static String actions(List<ComfortPolicy.Action> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(list.get(i).toString());
        }
        return sb.toString();
    }

    private static Map<String, String> state(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        ComfortConfig def = ComfortConfig.defaults();

        // 1. Sentinel filtering
        check("rejects -40.0 F DATC sentinel", true, Float.isNaN(ComfortPolicy.parseAmbientF("-40.0")));
        check("rejects - dash", true, Float.isNaN(ComfortPolicy.parseAmbientF("-")));
        check("accepts 24.5 F", "24.5", ComfortPolicy.parseAmbientF("24.5"));

        // 2. Multi-tier winter heating (driver + wheel)
        ComfortPolicy.Plan p20 = ComfortPolicy.evaluateClimate(def, 20.0f, state("seat_state.driver", "2", "wheel_heat", "0"));
        check("20 F -> driver high + wheel on", "seat_heat driver high | wheel_heat on", actions(p20.actions));

        ComfortPolicy.Plan p34 = ComfortPolicy.evaluateClimate(def, 34.0f, state("seat_state.driver", "2", "wheel_heat", "0"));
        check("34 F -> driver medium + wheel on", "seat_heat driver medium | wheel_heat on", actions(p34.actions));

        ComfortPolicy.Plan p48 = ComfortPolicy.evaluateClimate(def, 48.0f, state("seat_state.driver", "2", "wheel_heat", "0"));
        check("48 F -> driver low only (above 45 F wheel threshold)", "seat_heat driver low", actions(p48.actions));

        ComfortPolicy.Plan p62 = ComfortPolicy.evaluateClimate(def, 62.0f, state("seat_state.driver", "2", "wheel_heat", "0"));
        check("62 F mild -> no actions", "", actions(p62.actions));

        // 3. Follower seat policies (match vs 1 step lower) + 2-step rear fallback
        ComfortConfig fam = new ComfortConfig(
                true, 800, true, 25, 38, 50, true, 45,
                ComfortConfig.POLICY_MATCH, ComfortConfig.POLICY_LOWER, ComfortConfig.POLICY_MATCH,
                true, 75, 85, 95, false);
        ComfortPolicy.Plan pfam = ComfortPolicy.evaluateClimate(fam, 22.0f, state());
        check("22 F family policies (match pass, -1 rear_left, match rear_right)",
                "seat_heat driver high | seat_heat passenger high | seat_heat rear_left medium | seat_heat rear_right high | wheel_heat on",
                actions(pfam.actions));
        check("2-step rear fallback maps medium -> low", "low", ComfortPolicy.fallbackTwoStep("medium"));
        check("2-step rear fallback preserves high", "high", ComfortPolicy.fallbackTwoStep("high"));

        // 4. Summer seat ventilation (front seats only, rear never ventilated)
        ComfortConfig passLower = new ComfortConfig(
                true, 800, true, 25, 38, 50, true, 45,
                ComfortConfig.POLICY_LOWER, ComfortConfig.POLICY_MATCH, ComfortConfig.POLICY_MATCH,
                true, 75, 85, 95, false);
        ComfortPolicy.Plan p78 = ComfortPolicy.evaluateClimate(passLower, 78.0f, state());
        check("78 F -> driver vent low (passenger -1 = off)", "seat_vent driver low", actions(p78.actions));

        ComfortPolicy.Plan p88 = ComfortPolicy.evaluateClimate(fam, 88.0f, state());
        check("88 F -> driver + passenger vent medium", "seat_vent driver medium | seat_vent passenger medium", actions(p88.actions));

        ComfortPolicy.Plan p98 = ComfortPolicy.evaluateClimate(fam, 98.0f, state());
        check("98 F -> driver + passenger vent high", "seat_vent driver high | seat_vent passenger high", actions(p98.actions));

        // 5. Bluelink / manual climate preservation
        ComfortPolicy.Plan pBlue = ComfortPolicy.evaluateClimate(fam, 18.0f,
                state("seat_state.driver", "8", "seat_state.passenger", "2", "wheel_heat", "1"));
        check("Bluelink active driver & wheel preserved, passenger still warmed",
                "seat_heat passenger high | seat_heat rear_left medium | seat_heat rear_right high",
                actions(pBlue.actions));

        // 6. DriveTracker across cold boot, drive off, and QuickBoot STR resume
        ComfortPolicy.DriveTracker dt = new ComfortPolicy.DriveTracker();
        dt.onSuspendCheck(1000L);
        check("cold boot EV_RDY=1 triggers", true, dt.shouldTrigger("1"));
        dt.markCompleted();
        check("same drive EV_RDY=1 does not re-trigger", false, dt.shouldTrigger("1"));
        check("single EV_RDY=0 glitch does not reset", false, dt.shouldTrigger("0"));
        check("still completed after 1 glitch", false, dt.shouldTrigger("1"));
        dt.shouldTrigger("0");
        dt.shouldTrigger("0");
        check("sustained EV_RDY=0 resets for next drive", true, dt.shouldTrigger("1"));
        dt.markCompleted();
        dt.onSuspendCheck(601000L);   // 10 min QuickBoot STR sleep
        check("QuickBoot STR sleep gap re-arms drive trigger", true, dt.shouldTrigger("1"));

        // 7. WelcomeTracker across cold boot, SCREEN_OFF/SCREEN_ON, and QuickBoot STR resume
        ComfortPolicy.WelcomeTracker wt = new ComfortPolicy.WelcomeTracker(1000L);
        wt.onSuspendCheck(500L, 1000L);
        check("before delay -> false", false, wt.readyToDismiss(1500L, 800));
        check("after 800 ms delay -> true", true, wt.readyToDismiss(1800L, 800));
        wt.markDone();
        check("done -> false", false, wt.readyToDismiss(3000L, 800));
        wt.onScreenOff();
        check("while screen off -> false", false, wt.readyToDismiss(5000L, 800));
        wt.onScreenOn(6000L);
        check("screen on before delay -> false", false, wt.readyToDismiss(6500L, 800));
        check("screen on after delay -> true", true, wt.readyToDismiss(6800L, 800));
        wt.markDone();
        wt.onSuspendCheck(300500L, 10000L);   // QuickBoot resume without broadcast
        check("QuickBoot STR resume re-arms welcome dismiss", true, wt.readyToDismiss(10800L, 800));

        // 8. Plugged-in / Utility cabin sessions vs unoccupied charging, ACC-only, and remote Bluelink
        ComfortPolicy.DriveTracker pdt = new ComfortPolicy.DriveTracker();
        check("unoccupied overnight charging (IG2 off, screen off) ignored", false,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "0"), false));
        check("ACC-only / IG2 off (seat_state=0) with screen on ignored", false,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "0", "gear.park", "1", "seat_state.driver", "0"), true));
        check("cold-boot uninitialized BCM (seat_state=15) ignored", false,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "15"), true));
        check("remote Bluelink while locked/dark screen ignored until owner wakes screen", false,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "8"), false));
        check("plugged-in active charging cabin session triggers once screen wakes", true,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "2"), true));
        pdt.markCompleted();
        check("pressing Display Off mid-session does not reset completed cabin session", false,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "8"), false));
        check("waking display in same plugged-in session does not double-fire", false,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "8"), true));
        check("unplugging directly into EV_RDY=1 in same session does not double-fire", false,
                pdt.shouldTrigger(state("hv.ready", "1", "charging", "0", "gear.park", "1", "seat_state.driver", "8"), true));
        pdt.onPowerOff();
        check("plugged-in session after target SOC reached (charging=0, IG2 on) triggers", true,
                pdt.shouldTrigger(state("hv.ready", "0", "charging", "0", "gear.park", "1", "seat_state.driver", "2"), true));

        // 9. Closed-loop command verification rejects cold-boot "-", "0", "15", and "2"
        ComfortPolicy.Action wheelOn = new ComfortPolicy.Action("wheel_heat", "on", "");
        check("wheel_heat unconfirmed on cold-boot '-'", false, ComfortPolicy.isActionConfirmed(wheelOn, state("wheel_heat", "-")));
        check("wheel_heat unconfirmed on '0'", false, ComfortPolicy.isActionConfirmed(wheelOn, state("wheel_heat", "0")));
        check("wheel_heat confirmed on '1'", true, ComfortPolicy.isActionConfirmed(wheelOn, state("wheel_heat", "1")));

        ComfortPolicy.Action driverHigh = new ComfortPolicy.Action("seat_heat", "driver", "high");
        check("seat_heat unconfirmed on cold-boot '-'", false, ComfortPolicy.isActionConfirmed(driverHigh, state("seat_state.driver", "-")));
        check("seat_heat unconfirmed on cold-boot '0'", false, ComfortPolicy.isActionConfirmed(driverHigh, state("seat_state.driver", "0")));
        check("seat_heat unconfirmed on cold-boot '15'", false, ComfortPolicy.isActionConfirmed(driverHigh, state("seat_state.driver", "15")));
        check("seat_heat unconfirmed when still off ('2')", false, ComfortPolicy.isActionConfirmed(driverHigh, state("seat_state.driver", "2")));
        check("seat_heat unconfirmed when ventilating ('4')", false, ComfortPolicy.isActionConfirmed(driverHigh, state("seat_state.driver", "4")));
        check("seat_heat confirmed on BCM heat state ('8')", true, ComfortPolicy.isActionConfirmed(driverHigh, state("seat_state.driver", "8")));

        ComfortPolicy.Action driverVent = new ComfortPolicy.Action("seat_vent", "driver", "medium");
        check("seat_vent unconfirmed on '2'", false, ComfortPolicy.isActionConfirmed(driverVent, state("seat_state.driver", "2")));
        check("seat_vent confirmed on BCM vent state ('4')", true, ComfortPolicy.isActionConfirmed(driverVent, state("seat_state.driver", "4")));

        // 10. Cold-boot transient vs permanent refusals & confirmed out-of-Park guard
        check("transient: vehicle service isn't running", false,
                ComfortPolicy.isPermanentCommandRefusal("the vehicle service isn't running (yet)"));
        check("transient: car's service failed on boot", false,
                ComfortPolicy.isPermanentCommandRefusal("refused: the car's service failed (java.lang.IllegalStateException: no vrm service)"));
        check("transient: hmgapid busy code 75", false,
                ComfortPolicy.isPermanentCommandRefusal("hmgapid: code 75"));
        check("permanent: owner denied command", true,
                ComfortPolicy.isPermanentCommandRefusal("refused: the owner didn't allow wheel_heat for hmgapi-comfort"));
        check("permanent: rear seat lacks ventilation", true,
                ComfortPolicy.isPermanentCommandRefusal("refused: the rear seats have no ventilation"));

        check("cold-boot uninitialized gear/hv is NOT confirmed out of Park", false,
                ComfortPolicy.isConfirmedOutOfPark(state("hv.ready", "-", "gear.park", "-")));
        check("hv.ready=0 with gear.park=0 is NOT confirmed out of Park", false,
                ComfortPolicy.isConfirmedOutOfPark(state("hv.ready", "0", "gear.park", "0")));
        check("hv.ready=1 in Park is NOT out of Park", false,
                ComfortPolicy.isConfirmedOutOfPark(state("hv.ready", "1", "gear.park", "1")));
        check("hv.ready=1 with gear.park=0 IS confirmed out of Park", true,
                ComfortPolicy.isConfirmedOutOfPark(state("hv.ready", "1", "gear.park", "0")));

        // 11. LoopbackServer + LoopbackClient round-trip for OCCUPANCY, CABIN_STATE, SeatCapabilities & WAKE
        final Map<String, String> liveSignals = new LinkedHashMap<String, String>();
        liveSignals.put("HSCAN.getOccupancy", "2");
        liveSignals.put("SYS.getClassStatus", "2");
        liveSignals.put("HSCAN.getAsstStBltSwSta", "1");
        liveSignals.put("BCM.getIGNStatus", "true");
        liveSignals.put("DATC.isIGN3Status", "true");
        liveSignals.put("BLACKSCREEN.isBlackScreenShown", "false");
        liveSignals.put("POWER.isAvPowerOff", "false");
        liveSignals.put("POWER.isDisplayOffMode", "false");
        liveSignals.put("BCM.getDrvSeatHeatOption", "2");
        liveSignals.put("BCM.getAstSeatHeatOption", "2");
        liveSignals.put("BCM.getRLSeatHeatOption", "0");
        liveSignals.put("BCM.getRRSeatHeatOption", "0");
        liveSignals.put("PUSH.outside_temp_f", "33.5");
        liveSignals.put("PUSH.outside_temp", "0.8");
        liveSignals.put("PUSH.wheel_heat", "0");
        liveSignals.put("PUSH.datc_sentinel", "0");

        final int[] wakeCount = new int[] { 0 };
        OccupantSignalProbe.SignalSource mockSrc = new OccupantSignalProbe.SignalSource() {
            @Override
            public OccupantSignalProbe.PassengerPresence readPassengerPresence() {
                return OccupantSignalProbe.PassengerPresence.fromSignalMap(liveSignals);
            }

            @Override
            public OccupantSignalProbe.CabinState readCabinState() {
                return OccupantSignalProbe.CabinState.fromSignalMap(liveSignals);
            }

            @Override
            public int readMobisSleepingFast() {
                return OccupantSignalProbe.CabinState.fromSignalMap(liveSignals).mobisSleeping;
            }

            @Override
            public void onWake() {
                wakeCount[0]++;
            }
        };

        OccupantSignalProbe probe = new OccupantSignalProbe(mockSrc);
        OccupantSignalProbe.LoopbackServer srv = new OccupantSignalProbe.LoopbackServer(probe, 0);
        srv.start();
        try {
            int port = srv.getLocalPort();
            OccupantSignalProbe.LoopbackClient cli = new OccupantSignalProbe.LoopbackClient(
                    port, new OccupantSignalProbe(mockSrc));
            check("loopback pingDaemon succeeds", true, cli.pingDaemon());
            OccupantSignalProbe.PassengerPresence loopOcc = cli.queryPassengerPresence();
            check("loopback OCCUPANCY query reports available & occupied",
                    "true/true/2/2/1",
                    loopOcc.available + "/" + loopOcc.occupied + "/"
                            + loopOcc.hscanOccupancy + "/" + loopOcc.sysClassStatus + "/" + loopOcc.asstBelt);
            OccupantSignalProbe.CabinState loopCabin = cli.queryCabinState();
            check("loopback CABIN_STATE query reports push temp, wheel, ign, avAwake, mobisSleeping & seatCaps",
                    "33.5/0.8/0/1/1/1/0/false/vent=0,rl=0,rr=0",
                    loopCabin.outsideTempF + "/" + loopCabin.outsideTempC + "/" + loopCabin.wheelHeat
                            + "/" + loopCabin.ignOn + "/" + loopCabin.ign3On
                            + "/" + loopCabin.avAwake + "/" + loopCabin.mobisSleeping
                            + "/" + loopCabin.datcSentinel + "/" + loopCabin.seatCaps.summary());
            cli.notifyWake();
            check("loopback WAKE triggers source.onWake()", 1, wakeCount[0]);
        } finally {
            srv.stop();
        }

        // 15. hmgapid MAX_ARGS (16) vehicle batch chunking invariant
        String[] sixteenKeys = {
            "hv.ready", "gear.park", "charging", "outside_temp_f", "outside_temp", "wheel_heat",
            "seat_heat.driver", "seat_heat.passenger", "seat_heat.rear_left", "seat_heat.rear_right",
            "seat_vent.driver", "seat_vent.passenger",
            "seat_state.driver", "seat_state.passenger", "seat_state.rear_left", "seat_state.rear_right"
        };
        List<String[]> batches = ComfortPolicy.chunkVehicleKeys(sixteenKeys, ComfortPolicy.MAX_VEHICLE_BATCH_KEYS);
        check("16 vehicle keys split into 2 batches of 8", 2, batches.size());
        check("batch 0 has 8 keys (argc=9 <= 16)", 8, batches.get(0).length);
        check("batch 1 has 8 keys (argc=9 <= 16)", 8, batches.get(1).length);
        boolean allUnderMaxArgs = true;
        int totalChunked = 0;
        for (int i = 0; i < batches.size(); i++) {
            totalChunked += batches.get(i).length;
            if (1 + batches.get(i).length > 16) allUnderMaxArgs = false;
        }
        check("all vehicle IPC batches satisfy argc <= 16", true, allUnderMaxArgs && totalChunked == 16);

        // 16. PassengerPresence classification across Ioniq 5 empirical states & wire codec
        OccupantSignalProbe.PassengerPresence preCan = OccupantSignalProbe.PassengerPresence.fromRaw(0, 0, 0, 0);
        check("pre-CAN (0,0,0,0) unavailable & unoccupied", "false/false", preCan.available + "/" + preCan.occupied);
        OccupantSignalProbe.PassengerPresence invalidCan = OccupantSignalProbe.PassengerPresence.fromRaw(15, 255, -1, 0);
        check("invalid CAN (15,255,-1,0) unavailable & unoccupied", "false/false",
                invalidCan.available + "/" + invalidCan.occupied);
        OccupantSignalProbe.PassengerPresence soloEmpty = OccupantSignalProbe.PassengerPresence.fromRaw(1, 1, 0, 0);
        check("solo empty (1,1,0,0) available & unoccupied", "true/false",
                soloEmpty.available + "/" + soloEmpty.occupied);
        OccupantSignalProbe.PassengerPresence childCrs = OccupantSignalProbe.PassengerPresence.fromRaw(1, 3, 0, 0);
        check("ODS child CRS (1,3,0,0) available & unoccupied (never heats CRS)", "true/false",
                childCrs.available + "/" + childCrs.occupied);
        OccupantSignalProbe.PassengerPresence seatedUnbuckled = OccupantSignalProbe.PassengerPresence.fromRaw(2, 2, 0, 0);
        check("adult seated unbuckled (2,2,0,0) available & occupied", "true/true",
                seatedUnbuckled.available + "/" + seatedUnbuckled.occupied);
        OccupantSignalProbe.PassengerPresence seatedBuckled = OccupantSignalProbe.PassengerPresence.fromRaw(2, 2, 1, 0);
        check("adult seated buckled (2,2,1,0) available & occupied", "true/true",
                seatedBuckled.available + "/" + seatedBuckled.occupied);
        OccupantSignalProbe.PassengerPresence beltOnly = OccupantSignalProbe.PassengerPresence.fromRaw(1, 1, 1, 0);
        check("passenger belt buckled (1,1,1,0) available & occupied", "true/true",
                beltOnly.available + "/" + beltOnly.occupied);
        OccupantSignalProbe.PassengerPresence decoded =
                OccupantSignalProbe.PassengerPresence.decode(seatedBuckled.encode());
        check("PassengerPresence encode/decode round-trip", seatedBuckled.encode(), decoded.encode());

        // 17. Automatic passenger occupancy gating on startup climate (heat & vent)
        ComfortConfig autoPass = new ComfortConfig(
                true, 800, true, 25, 38, 50, true, 45,
                ComfortConfig.POLICY_MATCH, true, ComfortConfig.POLICY_OFF, ComfortConfig.POLICY_OFF,
                true, 75, 85, 95, false);
        ComfortConfig alwaysPass = new ComfortConfig(
                true, 800, true, 25, 38, 50, true, 45,
                ComfortConfig.POLICY_MATCH, false, ComfortConfig.POLICY_OFF, ComfortConfig.POLICY_OFF,
                true, 75, 85, 95, false);

        ComfortPolicy.Plan soloHeat = ComfortPolicy.evaluateClimate(
                autoPass, 22.0f, state("seat_state.driver", "2", "seat_state.passenger", "2", "wheel_heat", "0"), soloEmpty);
        check("auto-detect skips empty passenger seat at 22 F",
                "seat_heat driver high | wheel_heat on", actions(soloHeat.actions));
        check("auto-detect summary notes passenger empty", true, soloHeat.summary.contains("passenger empty"));

        ComfortPolicy.Plan occHeat = ComfortPolicy.evaluateClimate(
                autoPass, 22.0f, state("seat_state.driver", "2", "seat_state.passenger", "2", "wheel_heat", "0"), seatedUnbuckled);
        check("auto-detect warms occupied passenger seat at 22 F",
                "seat_heat driver high | seat_heat passenger high | wheel_heat on", actions(occHeat.actions));

        ComfortPolicy.Plan soloVent = ComfortPolicy.evaluateClimate(
                autoPass, 90.0f, state("seat_state.driver", "2", "seat_state.passenger", "2"), soloEmpty);
        check("auto-detect skips empty passenger ventilation at 90 F",
                "seat_vent driver medium", actions(soloVent.actions));

        ComfortPolicy.Plan occVent = ComfortPolicy.evaluateClimate(
                autoPass, 90.0f, state("seat_state.driver", "2", "seat_state.passenger", "2"), seatedBuckled);
        check("auto-detect cools occupied passenger seat at 90 F",
                "seat_vent driver medium | seat_vent passenger medium", actions(occVent.actions));

        ComfortPolicy.Plan fallbackHeat = ComfortPolicy.evaluateClimate(
                autoPass, 22.0f, state("seat_state.driver", "2", "seat_state.passenger", "2", "wheel_heat", "0"),
                OccupantSignalProbe.PassengerPresence.UNAVAILABLE);
        check("unavailable sensor falls back gracefully to configured policy",
                "seat_heat driver high | seat_heat passenger high | wheel_heat on", actions(fallbackHeat.actions));

        ComfortPolicy.Plan forcedHeat = ComfortPolicy.evaluateClimate(
                alwaysPass, 22.0f, state("seat_state.driver", "2", "seat_state.passenger", "2", "wheel_heat", "0"), soloEmpty);
        check("passengerAutoDetect=false warms passenger even when empty",
                "seat_heat driver high | seat_heat passenger high | wheel_heat on", actions(forcedHeat.actions));

        // 18. Mid-session late passenger boarding tracker
        ComfortPolicy.PassengerBoardingTracker bt = new ComfortPolicy.PassengerBoardingTracker();
        Map<String, String> drvSession = state(
                "hv.ready", "1", "gear.park", "0", "seat_state.driver", "8", "seat_state.passenger", "2");
        bt.onSessionClimateEvaluated(autoPass, soloEmpty);
        check("brief 1-tick seat lean ignored", false,
                bt.shouldTriggerLateBoarding(autoPass, drvSession, true, seatedUnbuckled));
        check("sustained 2-tick seated passenger triggers late boarding", true,
                bt.shouldTriggerLateBoarding(autoPass, drvSession, true, seatedUnbuckled));
        ComfortPolicy.Plan boardPlan = ComfortPolicy.evaluatePassengerBoarding(
                autoPass, 24.0f, drvSession, seatedUnbuckled);
        check("late boarding plan warms passenger seat only",
                "seat_heat passenger high", actions(boardPlan.actions));
        bt.markBoardingHandled();
        check("does not re-fire while same passenger remains seated (respects manual Off)", false,
                bt.shouldTriggerLateBoarding(autoPass, drvSession, true, seatedBuckled));
        for (int i = 0; i < 5; i++) {
            check("short unseat <6 polls does not re-arm", false,
                    bt.shouldTriggerLateBoarding(autoPass, drvSession, true, soloEmpty));
        }
        check("6th empty poll re-arms tracker for next passenger", false,
                bt.shouldTriggerLateBoarding(autoPass, drvSession, true, soloEmpty));
        check("buckling belt triggers immediately on 1st tick after re-arm", true,
                bt.shouldTriggerLateBoarding(autoPass, drvSession, true, seatedBuckled));

        ComfortPolicy.PassengerBoardingTracker btSeatedStart = new ComfortPolicy.PassengerBoardingTracker();
        btSeatedStart.onSessionClimateEvaluated(autoPass, seatedUnbuckled);
        check("passenger already seated at startup does not double-fire mid-session", false,
                btSeatedStart.shouldTriggerLateBoarding(autoPass, drvSession, true, seatedBuckled));

        // 20. QuickBoot plugged-in charging: CabinState push fallback, DisplayOff vs BlackScreen, and ACC -> Ignition ON
        check("DisplayOff sets avAwake=0 without mobisSleeping=1", "0/0",
                OccupantSignalProbe.CabinState.computeAvAwake(0, 0, 1)
                        + "/" + OccupantSignalProbe.CabinState.computeMobisSleeping(0, 0));
        check("BlackScreen sets both avAwake=0 and mobisSleeping=1", "0/1",
                OccupantSignalProbe.CabinState.computeAvAwake(1, 0, 0)
                        + "/" + OccupantSignalProbe.CabinState.computeMobisSleeping(1, 0));
        check("AV awake sets avAwake=1 and mobisSleeping=0", "1/0",
                OccupantSignalProbe.CabinState.computeAvAwake(0, 0, 0)
                        + "/" + OccupantSignalProbe.CabinState.computeMobisSleeping(0, 0));

        OccupantSignalProbe.CabinState pushOn = new OccupantSignalProbe.CabinState(
                soloEmpty, "31.5", "-0.3", "1", 1, 1, 1, 0, false);
        OccupantSignalProbe.CabinState pushDecoded =
                OccupantSignalProbe.CabinState.decode(pushOn.encode());
        check("CabinState encode/decode round-trip", pushOn.encode(), pushDecoded.encode());

        Map<String, String> staleWakeHub = state(
                "hv.ready", "0", "charging", "1", "gear.park", "1",
                "outside_temp_f", "-", "outside_temp", "-", "wheel_heat", "-",
                "seat_state.driver", "2");
        Map<String, String> mergedWake = ComfortPolicy.mergeCabinFallback(staleWakeHub, pushOn);
        check("mergeCabinFallback fills missing outside_temp_f from CabinPushBridge",
                "31.5", mergedWake.get("outside_temp_f"));
        check("mergeCabinFallback fills missing outside_temp C from CabinPushBridge",
                "-0.3", mergedWake.get("outside_temp"));
        check("mergeCabinFallback fills missing wheel_heat from CabinPushBridge",
                "1", mergedWake.get("wheel_heat"));

        Map<String, String> liveHub = state(
                "outside_temp_f", "29.0", "outside_temp", "-1.7", "wheel_heat", "0");
        Map<String, String> preservedHub = ComfortPolicy.mergeCabinFallback(liveHub, pushOn);
        check("mergeCabinFallback preserves non-empty HMG-API hub readings",
                "29.0/-1.7/0",
                preservedHub.get("outside_temp_f") + "/"
                        + preservedHub.get("outside_temp") + "/"
                        + preservedHub.get("wheel_heat"));

        OccupantSignalProbe.CabinState accSentinelCabin = new OccupantSignalProbe.CabinState(
                soloEmpty, "-40.0", "-40.0", "0", 0, 1, 1, 0, true);
        Map<String, String> mergedAcc = ComfortPolicy.mergeCabinFallback(staleWakeHub, accSentinelCabin);
        check("mergeCabinFallback rejects -40.0 F DATC sentinel while still filling wheel_heat",
                "-/0", mergedAcc.get("outside_temp_f") + "/" + mergedAcc.get("wheel_heat"));

        ComfortPolicy.DriveTracker qbChargeDt = new ComfortPolicy.DriveTracker();
        Map<String, String> pluggedAcc = state(
                "hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "0");
        check("plugged-in ACC standby detected when charging in Park with ignOn=0", true,
                ComfortPolicy.isPluggedInAccStandby(pluggedAcc, true, 0));
        check("plugged-in ACC standby does NOT trigger heaters while IG2 off", false,
                qbChargeDt.shouldTrigger(pluggedAcc, true, 0));
        check("pressing Start -> Ignition ON (ignOn=1) while charging immediately triggers climate", true,
                qbChargeDt.shouldTrigger(pluggedAcc, true, 1));
        check("isPluggedInAccStandby clears as soon as ignOn=1", false,
                ComfortPolicy.isPluggedInAccStandby(pluggedAcc, true, 1));
        qbChargeDt.markCompleted();
        check("same plugged-in Ignition ON session does not re-fire", false,
                qbChargeDt.shouldTrigger(state(
                        "hv.ready", "0", "charging", "1", "gear.park", "1", "seat_state.driver", "8"), true, 1));
        qbChargeDt.shouldTrigger(pluggedAcc, false, 0);
        qbChargeDt.shouldTrigger(pluggedAcc, false, 0);
        check("turning off Ignition while remaining plugged in re-arms for next wake", true,
                qbChargeDt.shouldTrigger(pluggedAcc, true, 1));

        // 21. Optional seat hardware auto-detection (BCM/USM SeatCapabilities) & policy gating
        OccupantSignalProbe.SeatCapabilities preCanCaps =
                OccupantSignalProbe.SeatCapabilities.fromRawOptions(0, 0, 0, 0);
        check("pre-CAN (0,0,0,0) remains UNKNOWN so controls never hide prematurely",
                "vent=-,rl=-,rr=-/true/true",
                preCanCaps.summary() + "/" + preCanCaps.isFrontVentSupported() + "/"
                        + preCanCaps.hasAnyRearHeatSupported());

        OccupantSignalProbe.SeatCapabilities sentinelCaps =
                OccupantSignalProbe.SeatCapabilities.fromRawOptions(255, -1, 0, 0);
        check("sentinel (255,-1,0,0) remains UNKNOWN",
                OccupantSignalProbe.SeatCapabilities.UNKNOWN, sentinelCaps);

        OccupantSignalProbe.SeatCapabilities ioniq5SelCaps =
                OccupantSignalProbe.SeatCapabilities.fromRawOptions(2, 2, 0, 0);
        check("Ioniq 5 SEL (2,2,0,0) detects no front vent & no rear heaters",
                "vent=0,rl=0,rr=0/false/false",
                ioniq5SelCaps.summary() + "/" + ioniq5SelCaps.isFrontVentSupported() + "/"
                        + ioniq5SelCaps.hasAnyRearHeatSupported());

        OccupantSignalProbe.SeatCapabilities ev6GtLineCaps =
                OccupantSignalProbe.SeatCapabilities.fromRawOptions(6, 6, 1, 1);
        check("EV6 GT-Line / Ioniq 5 Limited (6,6,1,1) detects front vent & 2-step rear heaters",
                "vent=1,rl=1,rr=1/true/true",
                ev6GtLineCaps.summary() + "/" + ev6GtLineCaps.isFrontVentSupported() + "/"
                        + ev6GtLineCaps.hasAnyRearHeatSupported());

        OccupantSignalProbe.SeatCapabilities usmFallbackCaps =
                OccupantSignalProbe.SeatCapabilities.fromSignalMap(state(
                        "BCM.getDrvSeatHeatOption", "-1",
                        "USM.getFrontSeatHeat_DrvOption", "2",
                        "BCM.getAstSeatHeatOption", "0",
                        "USM.getFrontSeatHeat_AstOption", "2",
                        "BCM.getRLSeatHeatOption", "0",
                        "BCM.getRRSeatHeatOption", "0"));
        check("USM option fallback resolves front heater-only & absent rear heaters",
                ioniq5SelCaps, usmFallbackCaps);

        check("mergeKnown preserves cached capabilities across pre-CAN UNKNOWN reads",
                ioniq5SelCaps, ioniq5SelCaps.mergeKnown(OccupantSignalProbe.SeatCapabilities.UNKNOWN));

        OccupantSignalProbe.CabinState cabinWithCaps = new OccupantSignalProbe.CabinState(
                soloEmpty, "34.0", "1.1", "0", 1, 1, 1, 0, false, ioniq5SelCaps);
        OccupantSignalProbe.CabinState cabinCapsDecoded =
                OccupantSignalProbe.CabinState.decode(cabinWithCaps.encode());
        check("CabinState 10-field encode/decode preserves SeatCapabilities",
                ioniq5SelCaps, cabinCapsDecoded.seatCaps);

        String legacy9Field = soloEmpty.encode() + "\t34.0\t1.1\t0\t1\t1\t1\t0\t0";
        check("CabinState 9-field legacy decode defaults seatCaps to UNKNOWN",
                OccupantSignalProbe.SeatCapabilities.UNKNOWN,
                OccupantSignalProbe.CabinState.decode(legacy9Field).seatCaps);

        ComfortPolicy.Plan noVentSummer = ComfortPolicy.evaluateClimate(
                fam, 88.0f, state("seat_state.driver", "2", "seat_state.passenger", "2"),
                seatedBuckled, ioniq5SelCaps);
        check("evaluateClimate skips seat_vent at 88 F when frontVent is absent",
                "", actions(noVentSummer.actions));

        ComfortPolicy.Plan noRearWinter = ComfortPolicy.evaluateClimate(
                fam, 20.0f, state("seat_state.driver", "2", "seat_state.passenger", "2", "wheel_heat", "0"),
                seatedBuckled, ioniq5SelCaps);
        check("evaluateClimate skips rear_left & rear_right seat_heat when rear heaters are absent",
                "seat_heat driver high | seat_heat passenger high | wheel_heat on",
                actions(noRearWinter.actions));

        ComfortPolicy.Plan noVentBoarding = ComfortPolicy.evaluatePassengerBoarding(
                autoPass, 88.0f, state("seat_state.passenger", "2"), seatedBuckled, ioniq5SelCaps);
        check("evaluatePassengerBoarding skips seat_vent when frontVent is absent",
                "", actions(noVentBoarding.actions));

        ComfortPolicy.Plan okHeatBoarding = ComfortPolicy.evaluatePassengerBoarding(
                autoPass, 24.0f, state("seat_state.passenger", "2"), seatedBuckled, ioniq5SelCaps);
        check("evaluatePassengerBoarding still warms front passenger in winter when frontVent is absent",
                "seat_heat passenger high", actions(okHeatBoarding.actions));

        // 22. 1 F temperature threshold steppers & bidirectional tier cascading
        check("threshold +1 F increments heatHighF by 1", 26, def.withAdjustedThreshold(0, 1).heatHighF);
        check("threshold -1 F decrements heatHighF by 1", 24, def.withAdjustedThreshold(0, -1).heatHighF);
        check("threshold +1 F increments wheelF by 1", 46, def.withAdjustedThreshold(3, 1).wheelF);
        check("threshold -1 F decrements ventLowF by 1", 74, def.withAdjustedThreshold(4, -1).ventLowF);

        ComfortConfig tiedCold = new ComfortConfig(
                true, 800, true, 38, 38, 50, true, 45,
                ComfortConfig.POLICY_OFF, true, ComfortConfig.POLICY_OFF, ComfortConfig.POLICY_OFF,
                true, 75, 85, 95, false);
        ComfortConfig pushedUp = tiedCold.withAdjustedThreshold(0, 1);
        check("raising heatHighF past heatMedF cascades heatMedF up by 1 F",
                "39/39", pushedUp.heatHighF + "/" + pushedUp.heatMedF);
        ComfortConfig pushedDown = tiedCold.withAdjustedThreshold(1, -1);
        check("lowering heatMedF below heatHighF cascades heatHighF down by 1 F",
                "37/37", pushedDown.heatHighF + "/" + pushedDown.heatMedF);

        ComfortConfig tightGap = new ComfortConfig(
                true, 800, true, 25, 38, 70, true, 45,
                ComfortConfig.POLICY_OFF, true, ComfortConfig.POLICY_OFF, ComfortConfig.POLICY_OFF,
                true, 75, 85, 95, false);
        ComfortConfig gapUp = tightGap.withAdjustedThreshold(2, 1);
        check("raising heatLowF into 5 F comfort buffer cascades ventLowF up by 1 F",
                "71/76", gapUp.heatLowF + "/" + gapUp.ventLowF);
        ComfortConfig gapDown = tightGap.withAdjustedThreshold(4, -1);
        check("lowering ventLowF into 5 F comfort buffer cascades heatLowF down by 1 F",
                "69/74", gapDown.heatLowF + "/" + gapDown.ventLowF);

        // 23. Metric (C) temperature reading, vehicle unit detection, steppers & direct C evaluation
        check("validAmbientC rejects -40.0 C pre-CAN wake sentinel",
                false, ComfortPolicy.validAmbientC(-40.0f));
        check("validAmbientC rejects 87.5 / 123.9 / 255 CAN sentinels",
                false, ComfortPolicy.validAmbientC(87.5f)
                        || ComfortPolicy.validAmbientC(123.9f)
                        || ComfortPolicy.validAmbientC(255.0f));
        check("validAmbientC accepts 0.0 C, -18.5 C, and 24.5 C",
                true, ComfortPolicy.validAmbientC(0.0f)
                        && ComfortPolicy.validAmbientC(-18.5f)
                        && ComfortPolicy.validAmbientC(24.5f));
        check("parseAmbientC rejects '-' and '-40' and parses '4.5'",
                "true/true/4.5",
                Float.isNaN(ComfortPolicy.parseAmbientC("-")) + "/"
                        + Float.isNaN(ComfortPolicy.parseAmbientC("-40")) + "/"
                        + ComfortPolicy.formatTenths(ComfortPolicy.parseAmbientC("4.5")));

        // Metric car where HMG-API outside_temp_f holds sentinel 255 while outside_temp is valid 4.0 C
        Map<String, String> metricHubOnly = ComfortPolicy.mergeCabinFallback(
                state("outside_temp_f", "255", "outside_temp", "4.0", "wheel_heat", "0"),
                OccupantSignalProbe.CabinState.UNAVAILABLE);
        check("mergeCabinFallback converts valid outside_temp (4.0 C) when outside_temp_f is 255",
                "39.2/4.0",
                metricHubOnly.get("outside_temp_f") + "/" + metricHubOnly.get("outside_temp"));

        // Metric car on unpatched HMG-API where both hub keys are stuck at '-' and CabinPushBridge provides C+F
        OccupantSignalProbe.CabinState metricPushCabin = new OccupantSignalProbe.CabinState(
                soloEmpty, "39.2", "4.0", "0", 1, 1, 1, 0, false,
                OccupantSignalProbe.SeatCapabilities.ALL_EQUIPPED,
                OccupantSignalProbe.TEMP_UNIT_C);
        Map<String, String> metricBridgeFallback = ComfortPolicy.mergeCabinFallback(
                state("outside_temp_f", "-", "outside_temp", "-", "wheel_heat", "-"),
                metricPushCabin);
        check("mergeCabinFallback populates both C and F from CabinPushBridge when hub is '-'",
                "39.2/4.0/0",
                metricBridgeFallback.get("outside_temp_f") + "/"
                        + metricBridgeFallback.get("outside_temp") + "/"
                        + metricBridgeFallback.get("wheel_heat"));

        // Mismatched unpopulated 0.0 F alongside 20.0 C on a Metric vehicle
        OccupantSignalProbe.CabinState metricUnitOnly = new OccupantSignalProbe.CabinState(
                soloEmpty, "-", "-", "0", 1, 1, 1, 0, false,
                OccupantSignalProbe.SeatCapabilities.ALL_EQUIPPED,
                OccupantSignalProbe.TEMP_UNIT_C);
        Map<String, String> fixedMismatch = ComfortPolicy.mergeCabinFallback(
                state("outside_temp_f", "0.0", "outside_temp", "20.0"),
                metricUnitOnly);
        check("mergeCabinFallback resolves mismatched 0.0 F vs 20.0 C using vehicle Metric unit",
                "68/20.0",
                fixedMismatch.get("outside_temp_f") + "/" + fixedMismatch.get("outside_temp"));

        check("formatAmbientDisplay formats tenths in Metric and whole degrees in Imperial",
                "18.5°C / 65°F",
                ComfortPolicy.formatAmbientDisplay(state("outside_temp", "18.5"), true)
                        + " / "
                        + ComfortPolicy.formatAmbientDisplay(state("outside_temp", "18.5"), false));

        // CabinState.resolveTempUnit & 11-field wire compatibility
        check("resolveTempUnit detects Metric (USM=0, km=0, DATC=1)",
                OccupantSignalProbe.TEMP_UNIT_C,
                OccupantSignalProbe.CabinState.resolveTempUnit(0, 0, 1, 0, 0, 0));
        check("resolveTempUnit detects Imperial (USM=1, mi=1, DATC=2)",
                OccupantSignalProbe.TEMP_UNIT_F,
                OccupantSignalProbe.CabinState.resolveTempUnit(1, 1, 2, 1, 1, 1));
        check("resolveTempUnit supports Metric C paired with miles (UK/Canada)",
                OccupantSignalProbe.TEMP_UNIT_C,
                OccupantSignalProbe.CabinState.resolveTempUnit(0, 1, 0, -1, -1, -1));
        check("resolveTempUnit falls back to DATC=1 (C) when USM returns 250 unsupported",
                OccupantSignalProbe.TEMP_UNIT_C,
                OccupantSignalProbe.CabinState.resolveTempUnit(250, 0, 1, -1, -1, -1));
        check("resolveTempUnit guards pre-CAN USM=0 when DATC=2 confirms Fahrenheit",
                OccupantSignalProbe.TEMP_UNIT_F,
                OccupantSignalProbe.CabinState.resolveTempUnit(0, -1, 2, -1, -1, -1));

        OccupantSignalProbe.CabinState decoded11 =
                OccupantSignalProbe.CabinState.decode(metricPushCabin.encode());
        check("CabinState 11-field encode/decode preserves TEMP_UNIT_C",
                OccupantSignalProbe.TEMP_UNIT_C, decoded11.tempUnit);
        String legacy10Field = soloEmpty.encode() + "\t34.0\t1.1\t0\t1\t1\t1\t0\t0\t1,1,1";
        check("CabinState 10-field legacy decode defaults tempUnit to TEMP_UNIT_UNKNOWN",
                OccupantSignalProbe.TEMP_UNIT_UNKNOWN,
                OccupantSignalProbe.CabinState.decode(legacy10Field).tempUnit);

        // ComfortConfig Metric defaults, 1 C steppers, 3 C comfort buffer, and custom C preservation
        check("ComfortConfig defaults derive clean Metric C tiers (-4/3/10, wheel 7, vent 24/29/35)",
                "-4/3/10/7/24/29/35",
                def.heatHighC + "/" + def.heatMedC + "/" + def.heatLowC + "/" + def.wheelC + "/"
                        + def.ventLowC + "/" + def.ventMedC + "/" + def.ventHighC);
        ComfortConfig metricStepUp = def.withAdjustedThreshold(1, 1, true);
        check("Metric threshold +1 C increments heatMedC to 4 C",
                4, metricStepUp.heatMedC);
        check("withClimateEnabled preserves custom Metric heatMedC=4 C",
                4, metricStepUp.withClimateEnabled(true).heatMedC);

        ComfortConfig metricTightGap = def
                .withAdjustedThreshold(2, 11, true); // 10 + 11 = 21 C vs ventLowC = 24 C (3 C gap)
        ComfortConfig metricGapPush = metricTightGap.withAdjustedThreshold(2, 1, true);
        check("Metric raising heatLowC into 3 C comfort buffer cascades ventLowC up by 1 C",
                "22/25", metricGapPush.heatLowC + "/" + metricGapPush.ventLowC);

        // Direct Metric (C) evaluation in ComfortPolicy (no F-rounding drift)
        ComfortPolicy.Plan metricWinter = ComfortPolicy.evaluateClimate(
                metricStepUp,
                state("outside_temp", "4.0", "outside_temp_f", "39.2",
                        "seat_state.driver", "2", "wheel_heat", "0"),
                soloEmpty,
                OccupantSignalProbe.SeatCapabilities.ALL_EQUIPPED,
                true);
        check("Metric evaluateClimate at 4.0 C with heatMedC=4 triggers medium heat + wheel without F drift",
                "seat_heat driver medium | wheel_heat on",
                actions(metricWinter.actions));
        check("Metric evaluateClimate formats summary in °C",
                true, metricWinter.summary.startsWith("4°C:"));

        ComfortPolicy.Plan metricDeepFreeze = ComfortPolicy.evaluateClimate(
                def,
                state("outside_temp", "-4.5", "seat_state.driver", "2", "wheel_heat", "0"),
                soloEmpty,
                OccupantSignalProbe.SeatCapabilities.ALL_EQUIPPED,
                true);
        check("Metric evaluateClimate at -4.5 C triggers high heat + wheel and formats tenths",
                "true:seat_heat driver high | wheel_heat on",
                metricDeepFreeze.summary.startsWith("-4.5°C:") + ":" + actions(metricDeepFreeze.actions));

        ComfortPolicy.Plan metricSummer = ComfortPolicy.evaluateClimate(
                autoPass,
                state("outside_temp", "30.0", "seat_state.driver", "2", "seat_state.passenger", "2"),
                soloEmpty,
                OccupantSignalProbe.SeatCapabilities.ALL_EQUIPPED,
                true);
        check("Metric evaluateClimate at 30.0 C triggers driver vent medium while solo",
                "seat_vent driver medium", actions(metricSummer.actions));

        ComfortPolicy.Plan metricBoarding = ComfortPolicy.evaluatePassengerBoarding(
                autoPass,
                state("outside_temp", "30.0", "seat_state.passenger", "2"),
                seatedBuckled,
                OccupantSignalProbe.SeatCapabilities.ALL_EQUIPPED,
                true);
        check("Metric evaluatePassengerBoarding at 30.0 C cools late-boarding passenger with °C summary",
                "Passenger detected (30°C): passenger vent medium -> seat_vent passenger medium",
                metricBoarding.summary + " -> " + actions(metricBoarding.actions));

        // 24. Cold-boot projection runway, silent dark-standby gating, /proc/net/tcp & public state.json
        check("Silent standby: ignOn=1 never suppresses comfort even if OpenLink marker or blackscreen",
                false, ComfortPolicy.isSilentParkedStandby(true, 6, 1, 1));
        check("Silent standby: OpenLink /dev/.openlink-bt marker suppresses parked silent wake",
                true, ComfortPolicy.isSilentParkedStandby(true, -1, 0, 0));
        check("Silent standby: Mobis BlackScreen/AV-Off (mobisSleeping=1, ignOn=0) is silent",
                true, ComfortPolicy.isSilentParkedStandby(false, 1, 1, 0));
        check("Silent standby: Micom DELAY_MODE (6) with unknown screen (-1) is silent",
                true, ComfortPolicy.isSilentParkedStandby(false, 6, -1, 0));
        check("Silent standby: Micom DELAY_MODE (6) with confirmed awake screen (0) allows welcome dismiss",
                false, ComfortPolicy.isSilentParkedStandby(false, 6, 0, 0));
        check("Silent standby: normal driver entry (power=1, mobisSleeping=0, ignOn=0) is active",
                false, ComfortPolicy.isSilentParkedStandby(false, 1, 0, 0));

        check("Cold-boot runway: waits while < 12 s and no projection/UI",
                false, ComfortPolicy.isColdBootRunwayReady(15_000L, 10_000L, ComfortPolicy.COLD_BOOT_RUNWAY_MS, false, false));
        check("Cold-boot runway: ready at >= 12 s even without phone",
                true, ComfortPolicy.isColdBootRunwayReady(22_000L, 10_000L, ComfortPolicy.COLD_BOOT_RUNWAY_MS, false, false));
        check("Cold-boot runway: ready immediately when AA/CarPlay TCP is connected",
                true, ComfortPolicy.isColdBootRunwayReady(12_000L, 10_000L, ComfortPolicy.COLD_BOOT_RUNWAY_MS, true, false));
        check("Cold-boot runway: ready immediately when Comfort UI is opened",
                true, ComfortPolicy.isColdBootRunwayReady(11_000L, 10_000L, ComfortPolicy.COLD_BOOT_RUNWAY_MS, false, true));

        String tcpListenAndLoopback =
                "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt\n"
                + "   0: 0100007F:8BE5 0100007F:C350 01 00000000:00000000 00:00000000 00000000\n"
                + "   1: 012BA8C0:149D 00000000:0000 0A 00000000:00000000 00:00000000 00000000\n";
        check("/proc/net/tcp: loopback ESTABLISHED and 192.168.43.1 LISTEN (0A) do not count as projection",
                false, ComfortPolicy.isProjectionTcpEstablished(tcpListenAndLoopback));
        String tcpAaEst = tcpListenAndLoopback
                + "   2: 012BA8C0:149D 142BA8C0:D431 01 00000000:00000000 00:00000000 00000000\n";
        check("/proc/net/tcp: 192.168.43.1 ESTABLISHED (01) detects Wireless AA session",
                true, ComfortPolicy.isProjectionTcpEstablished(tcpAaEst));
        String tcpCpIpv6 =
                "  sl  local_address                         remote_address                        st\n"
                + "   0: 0000000000000000FFFF00000131A8C0:1B58 0000000000000000FFFF00000A31A8C0:C001 01\n";
        check("/proc/net/tcp6: IPv4-mapped 192.168.49.1 ESTABLISHED detects Wireless CarPlay session",
                true, ComfortPolicy.isProjectionTcpEstablished(tcpCpIpv6));

        String pubEnabled = "{\"mods\":{\"other\":{\"enabled\":false},\"hmgapi-comfort\":{\"enabled\":true,\"settings\":{\"note\":\"a}b\"}}}}";
        String pubDisabled = "{\"mods\":{\"other\":{\"enabled\":true},\"hmgapi-comfort\":{\"settings\":{\"note\":\"{x}\"},\"enabled\": false}}}";
        check("Public state.json: enabled=true is not disabled",
                false, ComfortPolicy.isModDisabledInPublicState(pubEnabled, "hmgapi-comfort"));
        check("Public state.json: enabled=false after nested settings braces is detected",
                true, ComfortPolicy.isModDisabledInPublicState(pubDisabled, "hmgapi-comfort"));

        OccupantSignalProbe plainProbe = new OccupantSignalProbe(new OccupantSignalProbe.SignalSource() {
            @Override public OccupantSignalProbe.PassengerPresence readPassengerPresence() { return soloEmpty; }
            @Override public OccupantSignalProbe.CabinState readCabinState() { return OccupantSignalProbe.CabinState.UNAVAILABLE; }
            @Override public int readMobisSleepingFast() { return 0; }
            @Override public void onWake() {}
        });
        check("Plain SignalSource defaults readPowerStatusFast/readIgnStatusFast to -1",
                "-1/-1", plainProbe.readPowerStatusFast() + "/" + plainProbe.readIgnStatusFast());

        OccupantSignalProbe standbyProbe = new OccupantSignalProbe(new OccupantSignalProbe.StandbySignalSource() {
            @Override public OccupantSignalProbe.PassengerPresence readPassengerPresence() { return soloEmpty; }
            @Override public OccupantSignalProbe.CabinState readCabinState() { return OccupantSignalProbe.CabinState.UNAVAILABLE; }
            @Override public int readMobisSleepingFast() { return 1; }
            @Override public int readPowerStatusFast() { return 6; }
            @Override public int readIgnStatusFast() { return 0; }
            @Override public void onWake() {}
        });
        check("StandbySignalSource exposes power=6 and ign=0",
                "6/0/1",
                standbyProbe.readPowerStatusFast() + "/"
                        + standbyProbe.readIgnStatusFast() + "/"
                        + standbyProbe.readMobisSleepingFast());
        try {
            OccupantSignalProbe.LoopbackServer hbSrv = new OccupantSignalProbe.LoopbackServer(standbyProbe, 0);
            hbSrv.start();
            try {
                check("LoopbackServer.msSinceLastClient starts at Long.MAX_VALUE",
                        Long.MAX_VALUE, hbSrv.msSinceLastClient());
                OccupantSignalProbe.LoopbackClient hbCli =
                        new OccupantSignalProbe.LoopbackClient(hbSrv.getLocalPort(), plainProbe);
                hbCli.queryCabinState();
                check("LoopbackServer.msSinceLastClient updates on client query",
                        true, hbSrv.msSinceLastClient() < 5000L);
            } finally {
                hbSrv.stop();
            }
        } catch (Exception e) {
            check("LoopbackServer msSinceLastClient test threw no exception", "none", e.toString());
        }

        System.exit(fail);
    }
}
