package dev.lemoyne.comfort;

import static dev.lemoyne.comfort.ComfortTheme.*;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import hmgapi.client.HmgApi;

import java.util.Map;

/**
 * Material Design 3 widescreen automotive interface for Comfort & Startup (1920x720 Gen5W):
 * - Left M3 Navigation & Live Telemetry Rail (brand + author credit, ambient temp, HMG-API connection pill, powertrain status, 3 destinations)
 * - Right contextual pane (Overview & Startup, Climate & Seats, Temperature Thresholds)
 * - Automatic Day/Night M3 tonal palette via UiModeManager
 */
public final class MainActivity extends Activity implements ComfortEngine.Listener {
    private static final int TAB_OVERVIEW = 0;
    private static final int TAB_CLIMATE = 1;
    private static final int TAB_THRESHOLDS = 2;

    private static final String[][] POLICY_OPTIONS = {
        { ComfortConfig.POLICY_OFF, "Off" },
        { ComfortConfig.POLICY_LOWER, "−1 Step" },
        { ComfortConfig.POLICY_MATCH, "Match" }
    };

    private static int activeTab = TAB_OVERVIEW;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ComfortEngine engine;
    private ComfortConfig current = ComfortConfig.defaults();
    private float latestAmbientF = Float.NaN;
    private float latestAmbientC = Float.NaN;
    private boolean latestMetric;
    private OccupantSignalProbe.PassengerPresence latestPresence =
            OccupantSignalProbe.PassengerPresence.UNAVAILABLE;
    private OccupantSignalProbe.CabinState latestCabin =
            OccupantSignalProbe.CabinState.UNAVAILABLE;
    private OccupantSignalProbe.SeatCapabilities latestCaps =
            OccupantSignalProbe.SeatCapabilities.UNKNOWN;

    // Left rail & navigation views
    private TextView lblAmbientTemp;
    private TextView lblAmbientSub;
    private TextView lblConnChip;
    private TextView chipEv;
    private TextView chipGear;
    private TextView chipWheel;
    private final LinearLayout[] navItems = new LinearLayout[3];
    private final ComfortIcons[] navIcons = new ComfortIcons[3];
    private final TextView[] navLabels = new TextView[3];

    // Right pane header & pages
    private TextView pageTitle;
    private TextView pageSubtitle;
    private final View[] headerActions = new View[3];
    private final View[] pages = new View[3];
    private TextView lblClimateSummaryChip;

    // Overview & Startup controls
    private TextView lblCabinCardSub;
    private LinearLayout rearSeatsRow;
    private ComfortWidgets.SeatTileView tileDriver;
    private ComfortWidgets.SeatTileView tilePassenger;
    private ComfortWidgets.SeatTileView tileRearLeft;
    private ComfortWidgets.SeatTileView tileRearRight;
    private TextView lblWelcomeStatus;
    private TextView lblWelcomeTime;
    private TextView lblClimateStatus;
    private TextView lblClimateTime;
    private ComfortWidgets.M3Switch swWelcome;
    private LinearLayout welcomeDelayRow;
    private TextView lblWelcomeDelay;

    // Climate & Seats controls
    private ComfortWidgets.M3Switch swClimate;
    private ComfortWidgets.M3Switch swWheel;
    private View ventHairline;
    private LinearLayout ventSwitchRow;
    private ComfortWidgets.M3Switch swVent;
    private ComfortWidgets.M3Switch swToast;
    private TextView lblPolCardTitle;
    private TextView lblPolCardSub;
    private TextView lblPassengerPolSub;
    private ComfortWidgets.M3SegmentedGroup segPassenger;
    private ComfortWidgets.M3Switch swPassengerAuto;
    private LinearLayout passengerAutoRow;
    private TextView lblPassengerAutoSub;
    private View rearPolHairline;
    private LinearLayout rearLeftBlock;
    private LinearLayout rearRightBlock;
    private ComfortWidgets.M3SegmentedGroup segRearLeft;
    private ComfortWidgets.M3SegmentedGroup segRearRight;

    // Thresholds controls
    private ComfortWidgets.SpectrumBarView spectrumBar;
    private LinearLayout winterCard;
    private LinearLayout summerCard;
    private TextView lblWinterBluelinkNote;
    private TextView lblHeatHigh;
    private TextView lblHeatMed;
    private TextView lblHeatLow;
    private TextView lblWheelF;
    private TextView lblVentLow;
    private TextView lblVentMed;
    private TextView lblVentHigh;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ComfortTheme.apply(this);
        setTheme(ComfortTheme.night
                ? android.R.style.Theme_Holo_NoActionBar
                : android.R.style.Theme_Holo_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        startService(new Intent(this, ComfortService.class)
                .setAction(ComfortService.ACTION_ENSURE_RUNNING));
        engine = ComfortEngine.getInstance(this);
        ComfortEngine.Snapshot initSnap = engine.snapshot();
        current = initSnap.config;
        latestCaps = initSnap.seatCaps;
        latestMetric = initSnap.metricUnits;
        engine.ensureStarted();
        setContentView(buildRoot());
        applyHardwareCapabilities();
        selectTab(activeTab);
        updateControls(current);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine != null) {
            engine.setUiForeground(true);
            engine.addListener(this);
            engine.requestSettingsReload();
        }
    }

    @Override
    protected void onPause() {
        if (engine != null) {
            engine.flushPendingSettingsSave();
            engine.setUiForeground(false);
            engine.removeListener(this);
        }
        super.onPause();
    }

    @Override
    public void onStateUpdated(final ComfortEngine.Snapshot snap) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) render(snap);
            }
        });
    }

    // ---- Root M3 Widescreen Layout (Left Rail + Right Contextual Pane) ----

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(SURFACE);
        // 38dp right padding keeps controls clear of the Gen5W split-screen drawer handle
        root.setPadding(d(14), d(8), d(38), d(8));

        root.addView(buildLeftRail(), new LinearLayout.LayoutParams(d(316), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.addView(buildRightHeader());

        FrameLayout stage = new FrameLayout(this);
        pages[TAB_OVERVIEW] = wrapScroll(buildOverviewPage());
        pages[TAB_CLIMATE] = wrapScroll(buildClimatePage());
        pages[TAB_THRESHOLDS] = wrapScroll(buildThresholdsPage());
        for (int i = 0; i < pages.length; i++) {
            stage.addView(pages[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        stageLp.topMargin = d(8);
        right.addView(stage, stageLp);

        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightLp.leftMargin = d(14);
        root.addView(right, rightLp);
        return root;
    }

    private ScrollView wrapScroll(View content) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(false);
        sv.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private View buildLeftRail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackground(shape(SURFACE_LOW, OUTLINE_VAR, Math.max(1, d(1)), d(24)));
        int p = d(14);
        rail.setPadding(p, d(12), p, d(12));

        // 1. App brand row + author credit + Close button
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout emblem = ComfortWidgets.iconBadge(
                this, ComfortIcons.SEAT_WAVE, PRIMARY_CONT, ON_PRIMARY_CONT, 38, 22);
        brandRow.addView(emblem);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(d(10), 0, d(8), 0);
        TextView appTitle = label("Comfort", 18.5f, ON_SURFACE, true);
        TextView lblAuthor = label("by lemoyneiv", 11.5f, ON_SURFACE_VAR, false);
        titleCol.addView(appTitle);
        titleCol.addView(lblAuthor);
        brandRow.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout closeBtn = ComfortWidgets.circleIconButton(
                this, ComfortIcons.CLOSE, SURFACE_HIGH, ON_SURFACE, OUTLINE_VAR, 36,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
        brandRow.addView(closeBtn);
        rail.addView(brandRow);

        // 2. Live ambient, HMG-API connection & powertrain telemetry card
        LinearLayout teleCard = new LinearLayout(this);
        teleCard.setOrientation(LinearLayout.VERTICAL);
        teleCard.setBackground(shape(SURFACE_CONT, OUTLINE_VAR, Math.max(1, d(1)), d(18)));
        teleCard.setPadding(d(13), d(11), d(13), d(11));
        LinearLayout.LayoutParams teleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        teleLp.topMargin = d(11);

        LinearLayout tempRow = new LinearLayout(this);
        tempRow.setOrientation(LinearLayout.HORIZONTAL);
        tempRow.setGravity(Gravity.CENTER_VERTICAL);

        lblAmbientTemp = label(latestMetric ? "--°C" : "--°F", 26f, ON_SURFACE, true);
        lblAmbientTemp.setSingleLine(true);
        tempRow.addView(lblAmbientTemp);

        lblAmbientSub = label("", 12.5f, ON_SURFACE_VAR, false);
        lblAmbientSub.setSingleLine(true);
        lblAmbientSub.setPadding(d(6), d(4), 0, 0);
        tempRow.addView(lblAmbientSub);

        View tempSpacer = new View(this);
        tempRow.addView(tempSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        lblConnChip = statusChip("Waiting · HMG-API", SURFACE_HIGH, ON_SURFACE_VAR);
        lblConnChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        lblConnChip.setPadding(d(8), d(4), d(8), d(4));
        tempRow.addView(lblConnChip);
        teleCard.addView(tempRow);

        LinearLayout chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crLp.topMargin = d(9);

        chipEv = statusChip("EV --", SURFACE_HIGH, ON_SURFACE_VAR);
        chipGear = statusChip("Gear --", SURFACE_HIGH, ON_SURFACE_VAR);
        chipWheel = statusChip("Wheel --", SURFACE_HIGH, ON_SURFACE_VAR);
        chipsRow.addView(chipEv, chipWeight(1.35f, 0, 3));
        chipsRow.addView(chipGear, chipWeight(0.75f, 3, 3));
        chipsRow.addView(chipWheel, chipWeight(1.0f, 3, 0));
        teleCard.addView(chipsRow, crLp);
        rail.addView(teleCard, teleLp);

        // 3. M3 Navigation Rail destination pills
        LinearLayout navCol = new LinearLayout(this);
        navCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams navColLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navColLp.topMargin = d(12);

        navCol.addView(buildNavItem(TAB_OVERVIEW, ComfortIcons.SHIELD_CHECK, "Overview & Startup"));
        navCol.addView(buildNavItem(TAB_CLIMATE, ComfortIcons.SEAT_WAVE, "Climate & Seats"));
        navCol.addView(buildNavItem(TAB_THRESHOLDS, ComfortIcons.THERMOMETER, "Thresholds"));
        rail.addView(navCol, navColLp);

        rail.addView(new View(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 4. Primary quick action button pinned at bottom of rail
        LinearLayout runBtn = ComfortWidgets.m3Button(
                this, ComfortIcons.PLAY_BOLT, "Run Climate Check", ComfortWidgets.BTN_FILLED, 42,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (engine != null) {
                            engine.triggerClimateNow();
                            Toast.makeText(MainActivity.this, "Running cabin climate check…", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        rail.addView(runBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return rail;
    }

    private View buildNavItem(final int index, int iconKind, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(d(44));
        row.setPadding(d(16), 0, d(16), 0);
        row.setClickable(true);

        ComfortIcons icon = new ComfortIcons(iconKind, ON_SURFACE_VAR);
        View icView = new View(this);
        icView.setBackground(icon);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(d(20), d(20));
        ip.rightMargin = d(12);
        row.addView(icView, ip);

        TextView lbl = label(title, 14f, ON_SURFACE_VAR, false);
        row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(index);
            }
        });

        navItems[index] = row;
        navIcons[index] = icon;
        navLabels[index] = lbl;

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, d(44));
        if (index > 0) lp.topMargin = d(4);
        row.setLayoutParams(lp);
        return row;
    }

    private View buildRightHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(d(42));
        bar.setPadding(d(4), 0, 0, 0);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        pageTitle = label("Overview & Startup", 20f, ON_SURFACE, true);
        pageSubtitle = label("Live cabin status and automatic startup safety notice", 12.5f, ON_SURFACE_VAR, false);
        titles.addView(pageTitle);
        titles.addView(pageSubtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Tab 0 (Overview) has no header action buttons
        headerActions[TAB_OVERVIEW] = null;

        // Contextual status badge for Tab 1 (Climate)
        lblClimateSummaryChip = statusChip("Auto Climate Active", OK_CONT, ON_OK_CONT);
        lblClimateSummaryChip.setPadding(d(14), d(7), d(14), d(7));
        headerActions[TAB_CLIMATE] = lblClimateSummaryChip;
        bar.addView(lblClimateSummaryChip);

        // Contextual reset button for Tab 2 (Thresholds)
        LinearLayout resetBtn = ComfortWidgets.m3Button(
                this, ComfortIcons.RESET, "Reset Thresholds", ComfortWidgets.BTN_OUTLINED, 36,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        save(current.withDefaultThresholds());
                        Toast.makeText(MainActivity.this, "Reset thresholds to defaults", Toast.LENGTH_SHORT).show();
                    }
                });
        headerActions[TAB_THRESHOLDS] = resetBtn;
        bar.addView(resetBtn);

        return bar;
    }

    private void selectTab(int index) {
        if (index < 0 || index >= pages.length) index = TAB_OVERVIEW;
        activeTab = index;
        for (int i = 0; i < pages.length; i++) {
            boolean sel = (i == index);
            pages[i].setVisibility(sel ? View.VISIBLE : View.GONE);
            if (headerActions[i] != null) {
                headerActions[i].setVisibility(sel ? View.VISIBLE : View.GONE);
            }
            navItems[i].setBackground(interactiveShape(
                    sel ? SECONDARY_CONT : Color.TRANSPARENT, 0, 0, d(22), STATE_ON_SURFACE));
            navIcons[i].setColor(sel ? ON_SECONDARY_CONT : ON_SURFACE_VAR);
            navLabels[i].setTextColor(sel ? ON_SECONDARY_CONT : ON_SURFACE_VAR);
            navLabels[i].setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        updateHeaderText();
    }

    private void updateHeaderText() {
        if (pageTitle == null || pageSubtitle == null) return;
        boolean ventOk = latestCaps.isFrontVentSupported();
        if (activeTab == TAB_OVERVIEW) {
            pageTitle.setText("Overview & Startup");
            pageSubtitle.setText("Live cabin seat telemetry, recent actions, and startup safety screen dismissal");
        } else if (activeTab == TAB_CLIMATE) {
            pageTitle.setText("Climate & Seats");
            pageSubtitle.setText(ventOk
                    ? "Automatic heating, summer ventilation, and companion seat policies on start or charging"
                    : "Automatic seat & wheel heating and companion seat policies on start or charging");
        } else {
            String unitTag = latestMetric ? "(°C)" : "(°F)";
            pageTitle.setText("Temperature Thresholds");
            pageSubtitle.setText(ventOk
                    ? "Outside temperature triggers " + unitTag + " for multi-stage heating and ventilation"
                    : "Outside temperature triggers " + unitTag + " for multi-stage winter seat and wheel heating");
        }
    }

    // ---- Page 0: Overview & Startup ----

    private View buildOverviewPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // Top 2-column row: Cabin Seats Card | Recent Activity Card
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout cabinCard = m3Card();
        TextView[] cabinHdr = new TextView[2];
        cabinCard.addView(cardHeaderRefs(ComfortIcons.SEAT_WAVE, PRIMARY_CONT, ON_PRIMARY_CONT,
                "Cabin Climate State", "Live seat heat & ventilation levels", cabinHdr));
        lblCabinCardSub = cabinHdr[1];

        tileDriver = new ComfortWidgets.SeatTileView(this, "Driver");
        tilePassenger = new ComfortWidgets.SeatTileView(this, "Passenger");
        tileRearLeft = new ComfortWidgets.SeatTileView(this, "Rear Left");
        tileRearRight = new ComfortWidgets.SeatTileView(this, "Rear Right");

        LinearLayout frontSeats = new LinearLayout(this);
        frontSeats.setOrientation(LinearLayout.HORIZONTAL);
        frontSeats.addView(tileDriver, colWeight(0, 5));
        frontSeats.addView(tilePassenger, colWeight(5, 0));
        LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fsLp.topMargin = d(10);
        cabinCard.addView(frontSeats, fsLp);

        rearSeatsRow = new LinearLayout(this);
        rearSeatsRow.setOrientation(LinearLayout.HORIZONTAL);
        rearSeatsRow.addView(tileRearLeft, colWeight(0, 5));
        rearSeatsRow.addView(tileRearRight, colWeight(5, 0));
        LinearLayout.LayoutParams rsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rsLp.topMargin = d(8);
        cabinCard.addView(rearSeatsRow, rsLp);

        LinearLayout activityCard = m3Card();
        activityCard.addView(cardHeader(ComfortIcons.AUTO_CLIMATE, SECONDARY_CONT, ON_SECONDARY_CONT,
                "System Activity", "Latest startup & climate coordinator events"));

        lblWelcomeStatus = label("Armed for startup", 13.5f, ON_SURFACE, false);
        lblWelcomeStatus.setMaxLines(2);
        lblWelcomeStatus.setEllipsize(TextUtils.TruncateAt.END);
        lblWelcomeTime = statusChip("--:--", SURFACE_HIGH, ON_SURFACE_VAR);
        activityCard.addView(activityItem("Startup Notice", lblWelcomeStatus, lblWelcomeTime, 10));

        activityCard.addView(hairline(8));

        lblClimateStatus = label("Waiting for vehicle start", 13.5f, ON_SURFACE, false);
        lblClimateStatus.setMaxLines(2);
        lblClimateStatus.setEllipsize(TextUtils.TruncateAt.END);
        lblClimateTime = statusChip("--:--", SURFACE_HIGH, ON_SURFACE_VAR);
        activityCard.addView(activityItem("Auto Climate", lblClimateStatus, lblClimateTime, 0));

        topRow.addView(cabinCard, colWeight(0, 6));
        topRow.addView(activityCard, colWeight(6, 0));
        col.addView(topRow);

        // Bottom card: Startup Safety Notice Settings
        LinearLayout startupCard = m3Card();
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.topMargin = d(12);

        swWelcome = new ComfortWidgets.M3Switch(this, current.welcomeDismiss, new ComfortWidgets.ToggleListener() {
            @Override
            public void onToggle(boolean checked) {
                save(current.withWelcomeDismiss(checked));
            }
        });
        startupCard.addView(switchRow(
                ComfortIcons.SHIELD_CHECK, PRIMARY_CONT, ON_PRIMARY_CONT,
                "Auto-dismiss startup safety notice",
                "Confirms the Park safety screen on cold boot and QuickBoot wake (requires HMG-API startup notice permission)",
                swWelcome));

        startupCard.addView(hairline(10));

        welcomeDelayRow = new LinearLayout(this);
        welcomeDelayRow.setOrientation(LinearLayout.HORIZONTAL);
        welcomeDelayRow.setGravity(Gravity.CENTER_VERTICAL);
        welcomeDelayRow.addView(ComfortWidgets.iconBadge(
                this, ComfortIcons.TIMER, SURFACE_HIGH, ON_SURFACE_VAR, 36, 19));

        LinearLayout delayText = new LinearLayout(this);
        delayText.setOrientation(LinearLayout.VERTICAL);
        delayText.setPadding(d(12), 0, d(12), 0);
        delayText.addView(label("Wake dismiss delay", 15f, ON_SURFACE, true));
        delayText.addView(label("Wait after screen wake before sending dismiss (0 ms = instant fast-path)", 12.5f, ON_SURFACE_VAR, false));
        welcomeDelayRow.addView(delayText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        lblWelcomeDelay = new TextView(this);
        welcomeDelayRow.addView(inlineStepper(lblWelcomeDelay, 92, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustDelay(-200);
            }
        }, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustDelay(200);
            }
        }));
        startupCard.addView(welcomeDelayRow);

        col.addView(startupCard, scLp);
        return col;
    }

    private View activityItem(String category, TextView bodyView, TextView timeChip, int topDp) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        if (topDp > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = d(topDp);
            wrap.setLayoutParams(lp);
        }

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(label(category, 12.5f, ON_SURFACE_VAR, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(timeChip);
        wrap.addView(head);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = d(3);
        wrap.addView(bodyView, bp);
        return wrap;
    }

    // ---- Page 1: Climate & Seats ----

    private View buildClimatePage() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        // Left Card: Automatic Climate Feature Switches
        LinearLayout featCard = m3Card();
        featCard.addView(cardHeader(ComfortIcons.AUTO_CLIMATE, PRIMARY_CONT, ON_PRIMARY_CONT,
                "Automatic Climate", "Triggered on EV Ready & plugged-in cabin sessions"));

        swClimate = new ComfortWidgets.M3Switch(this, current.climateEnabled, new ComfortWidgets.ToggleListener() {
            @Override
            public void onToggle(boolean checked) {
                save(current.withClimateEnabled(checked));
            }
        });
        LinearLayout r1 = switchRow(ComfortIcons.PLAY_BOLT, PRIMARY_CONT, ON_PRIMARY_CONT,
                "Auto seat & wheel climate", "Evaluate ambient temp on EV Ready & plugged-in cabin power", swClimate);
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1Lp.topMargin = d(10);
        featCard.addView(r1, r1Lp);
        featCard.addView(hairline(8));

        swWheel = new ComfortWidgets.M3Switch(this, current.wheelEnabled, new ComfortWidgets.ToggleListener() {
            @Override
            public void onToggle(boolean checked) {
                save(current.withWheelEnabled(checked));
            }
        });
        featCard.addView(switchRow(ComfortIcons.WHEEL, SECONDARY_CONT, ON_SECONDARY_CONT,
                "Steering wheel heat", "Warm wheel at or below wheel threshold", swWheel));

        ventHairline = hairline(8);
        featCard.addView(ventHairline);

        swVent = new ComfortWidgets.M3Switch(this, current.ventEnabled, new ComfortWidgets.ToggleListener() {
            @Override
            public void onToggle(boolean checked) {
                save(current.withVentEnabled(checked));
            }
        });
        ventSwitchRow = switchRow(ComfortIcons.BREEZE, TERTIARY_CONT, ON_TERTIARY_CONT,
                "Summer seat ventilation", "Cool front seats above summer thresholds", swVent);
        featCard.addView(ventSwitchRow);
        featCard.addView(hairline(8));

        swToast = new ComfortWidgets.M3Switch(this, current.toastOnApply, new ComfortWidgets.ToggleListener() {
            @Override
            public void onToggle(boolean checked) {
                save(current.withToastOnApply(checked));
            }
        });
        featCard.addView(switchRow(ComfortIcons.BELL, SURFACE_HIGH, ON_SURFACE_VAR,
                "Confirmation banner", "Show head-unit toast when levels apply", swToast));

        // Right Card: Companion & Rear Seat Policies (M3 Segmented Buttons)
        LinearLayout polCard = m3Card();
        TextView[] polHdr = new TextView[2];
        polCard.addView(cardHeaderRefs(ComfortIcons.SEAT_WAVE, SECONDARY_CONT, ON_SECONDARY_CONT,
                "Companion & Rear Seats", "How other seats track the driver's target level", polHdr));
        lblPolCardTitle = polHdr[0];
        lblPolCardSub = polHdr[1];

        segPassenger = new ComfortWidgets.M3SegmentedGroup(
                this, POLICY_OPTIONS, current.passengerPolicy, 36, new ComfortWidgets.PickListener() {
                    @Override
                    public void onPick(String val) {
                        save(current.withPassengerPolicy(val));
                    }
                });
        TextView[] passSubOut = new TextView[1];
        polCard.addView(policyBlock("Front Passenger", "Heating & summer ventilation", segPassenger, 10, passSubOut));
        lblPassengerPolSub = passSubOut[0];

        swPassengerAuto = new ComfortWidgets.M3Switch(
                this, current.passengerAutoDetect, new ComfortWidgets.ToggleListener() {
                    @Override
                    public void onToggle(boolean checked) {
                        save(current.withPassengerAutoDetect(checked));
                    }
                });
        passengerAutoRow = new LinearLayout(this);
        passengerAutoRow.setOrientation(LinearLayout.HORIZONTAL);
        passengerAutoRow.setGravity(Gravity.CENTER_VERTICAL);
        passengerAutoRow.addView(ComfortWidgets.iconBadge(
                this, ComfortIcons.RADAR, SECONDARY_CONT, ON_SECONDARY_CONT, 32, 17));
        LinearLayout paText = new LinearLayout(this);
        paText.setOrientation(LinearLayout.VERTICAL);
        paText.setPadding(d(10), 0, d(8), 0);
        paText.addView(label("Only when passenger detected", 13.5f, ON_SURFACE, true));
        lblPassengerAutoSub = label(
                "Uses seat weight & buckle sensors (auto-applies on late boarding)",
                11.5f, ON_SURFACE_VAR, false);
        paText.addView(lblPassengerAutoSub);
        passengerAutoRow.addView(paText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        passengerAutoRow.addView(swPassengerAuto);
        passengerAutoRow.setClickable(true);
        passengerAutoRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                swPassengerAuto.performClick();
            }
        });
        LinearLayout.LayoutParams paLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paLp.topMargin = d(8);
        polCard.addView(passengerAutoRow, paLp);

        rearPolHairline = hairline(8);
        polCard.addView(rearPolHairline);

        segRearLeft = new ComfortWidgets.M3SegmentedGroup(
                this, POLICY_OPTIONS, current.rearLeftPolicy, 36, new ComfortWidgets.PickListener() {
                    @Override
                    public void onPick(String val) {
                        save(current.withRearLeftPolicy(val));
                    }
                });
        rearLeftBlock = policyBlock("Rear Left Seat", "Winter heating (2-step seats map Med → Low)", segRearLeft, 0, null);
        polCard.addView(rearLeftBlock);

        segRearRight = new ComfortWidgets.M3SegmentedGroup(
                this, POLICY_OPTIONS, current.rearRightPolicy, 36, new ComfortWidgets.PickListener() {
                    @Override
                    public void onPick(String val) {
                        save(current.withRearRightPolicy(val));
                    }
                });
        rearRightBlock = policyBlock("Rear Right Seat", "Winter heating (2-step seats map Med → Low)", segRearRight, 8, null);
        polCard.addView(rearRightBlock);

        row.addView(featCard, colWeight(0, 6));
        row.addView(polCard, colWeight(6, 0));
        return row;
    }

    private LinearLayout policyBlock(String title, String sub, ComfortWidgets.M3SegmentedGroup seg,
                                     int topDp, TextView[] outSub) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = d(topDp);
        block.setLayoutParams(lp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label(title, 14.5f, ON_SURFACE, true));
        TextView subView = label("  ·  " + sub, 12f, ON_SURFACE_VAR, false);
        subView.setSingleLine(true);
        subView.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(subView);
        if (outSub != null && outSub.length > 0) {
            outSub[0] = subView;
        }
        block.addView(header);

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = d(6);
        block.addView(seg, sp);
        return block;
    }

    // ---- Page 2: Temperature Thresholds ----

    private View buildThresholdsPage() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // Seasonal Spectrum Bar Card
        LinearLayout specCard = m3Card();
        specCard.setPadding(d(14), d(10), d(14), d(8));
        spectrumBar = new ComfortWidgets.SpectrumBarView(this);
        specCard.addView(spectrumBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(specCard);

        // Two-column thresholds split: Winter Heating | Summer Ventilation
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams splitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        splitLp.topMargin = d(10);

        winterCard = m3Card();
        winterCard.addView(cardHeader(ComfortIcons.HEAT_WAVES, PRIMARY_CONT, ON_PRIMARY_CONT,
                "Winter Heating", "Triggers when outside temp is at or below threshold"));

        lblHeatHigh = new TextView(this);
        lblHeatMed = new TextView(this);
        lblHeatLow = new TextView(this);
        lblWheelF = new TextView(this);

        winterCard.addView(thresholdRow("Driver High (Level 3)", "≤ Coldest tier", lblHeatHigh, 0, 10));
        winterCard.addView(hairline(6));
        winterCard.addView(thresholdRow("Driver Medium (Level 2)", "≤ Mid cold tier", lblHeatMed, 1, 0));
        winterCard.addView(hairline(6));
        winterCard.addView(thresholdRow("Driver Low (Level 1)", "≤ Mild chill tier", lblHeatLow, 2, 0));
        winterCard.addView(hairline(6));
        winterCard.addView(thresholdRow("Steering Wheel Heat", "≤ Independent trigger", lblWheelF, 3, 0));

        lblWinterBluelinkNote = label(
                "Active Bluelink remote climate is automatically preserved when starting or entering the cabin.",
                12f, ON_SURFACE_VAR, false);
        lblWinterBluelinkNote.setVisibility(View.GONE);
        LinearLayout.LayoutParams wnp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wnp.topMargin = d(12);
        winterCard.addView(lblWinterBluelinkNote, wnp);

        summerCard = m3Card();
        summerCard.addView(cardHeader(ComfortIcons.BREEZE, TERTIARY_CONT, ON_TERTIARY_CONT,
                "Summer Ventilation", "Triggers when outside temp is at or above threshold"));

        lblVentLow = new TextView(this);
        lblVentMed = new TextView(this);
        lblVentHigh = new TextView(this);

        summerCard.addView(thresholdRow("Front Low (Level 1)", "≥ Warm tier", lblVentLow, 4, 10));
        summerCard.addView(hairline(6));
        summerCard.addView(thresholdRow("Front Medium (Level 2)", "≥ Hot tier", lblVentMed, 5, 0));
        summerCard.addView(hairline(6));
        summerCard.addView(thresholdRow("Front High (Level 3)", "≥ Peak heat tier", lblVentHigh, 6, 0));

        TextView note = label(
                "Active Bluelink remote climate is automatically preserved when starting or entering the cabin.",
                12f, ON_SURFACE_VAR, false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = d(12);
        summerCard.addView(note, np);

        split.addView(winterCard, colWeight(0, 6));
        split.addView(summerCard, colWeight(6, 0));
        col.addView(split, splitLp);
        return col;
    }

    private View thresholdRow(String title, String sub, TextView valueOut, final int which, int topDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (topDp > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = d(topDp);
            row.setLayoutParams(lp);
        }

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.addView(label(title, 14f, ON_SURFACE, true));
        textCol.addView(label(sub, 11.5f, ON_SURFACE_VAR, false));
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(inlineStepper(valueOut, 70, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustThreshold(which, -1);
            }
        }, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustThreshold(which, 1);
            }
        }));
        return row;
    }

    private LinearLayout inlineStepper(TextView valueOut, int minWidthDp,
                                       View.OnClickListener onMinus, View.OnClickListener onPlus) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(shape(SURFACE_HIGH, OUTLINE_VAR, Math.max(1, d(1)), d(20)));
        box.setPadding(d(3), d(3), d(3), d(3));

        FrameLayout bMin = ComfortWidgets.circleIconButton(
                this, ComfortIcons.MINUS, SECONDARY_CONT, ON_SECONDARY_CONT, 0, 32, onMinus);
        box.addView(bMin);

        valueOut.setTextColor(ON_SURFACE);
        valueOut.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        valueOut.setTypeface(Typeface.DEFAULT_BOLD);
        valueOut.setGravity(Gravity.CENTER);
        valueOut.setSingleLine(true);
        valueOut.setMinWidth(d(minWidthDp));
        valueOut.setPadding(d(6), 0, d(6), 0);
        box.addView(valueOut);

        FrameLayout bPlus = ComfortWidgets.circleIconButton(
                this, ComfortIcons.PLUS, SECONDARY_CONT, ON_SECONDARY_CONT, 0, 32, onPlus);
        box.addView(bPlus);
        return box;
    }

    // ---- Mutations & Live Rendering ----

    private void adjustDelay(int deltaMs) {
        int nextDelay = ComfortConfig.clamp(current.welcomeDelayMs + deltaMs, 0, 10000);
        save(current.withWelcomeDelayMs(nextDelay));
    }

    private void adjustThreshold(int which, int delta) {
        save(current.withAdjustedThreshold(which, delta, latestMetric));
    }

    private void save(final ComfortConfig next) {
        current = next;
        updateControls(next);
        if (engine != null) {
            engine.applyUserConfig(next);
        }
    }

    private void applyHardwareCapabilities() {
        boolean ventOk = latestCaps.isFrontVentSupported();
        boolean rlOk = latestCaps.isRearLeftHeatSupported();
        boolean rrOk = latestCaps.isRearRightHeatSupported();
        boolean anyRearOk = rlOk || rrOk;

        // Overview & Startup tab
        if (lblCabinCardSub != null) {
            lblCabinCardSub.setText(ventOk
                    ? "Live seat heat & ventilation levels"
                    : "Live seat heat levels");
        }
        if (rearSeatsRow != null) {
            rearSeatsRow.setVisibility(anyRearOk ? View.VISIBLE : View.GONE);
            tileRearLeft.setVisibility(rlOk ? View.VISIBLE : View.GONE);
            tileRearRight.setVisibility(rrOk ? View.VISIBLE : View.GONE);
            ((LinearLayout.LayoutParams) tileRearLeft.getLayoutParams()).rightMargin =
                    (rlOk && rrOk) ? d(5) : 0;
            ((LinearLayout.LayoutParams) tileRearRight.getLayoutParams()).leftMargin =
                    (rlOk && rrOk) ? d(5) : 0;
        }

        // Climate & Seats tab
        if (ventHairline != null && ventSwitchRow != null) {
            int ventVis = ventOk ? View.VISIBLE : View.GONE;
            ventHairline.setVisibility(ventVis);
            ventSwitchRow.setVisibility(ventVis);
        }
        if (lblPolCardTitle != null && lblPolCardSub != null) {
            if (anyRearOk) {
                lblPolCardTitle.setText("Companion & Rear Seats");
                lblPolCardSub.setText("How other seats track the driver's target level");
            } else {
                lblPolCardTitle.setText("Front Passenger Seat");
                lblPolCardSub.setText("How the passenger seat tracks the driver's target level");
            }
        }
        if (lblPassengerPolSub != null) {
            lblPassengerPolSub.setText(ventOk
                    ? "  ·  Heating & summer ventilation"
                    : "  ·  Winter heating");
        }
        if (rearPolHairline != null && rearLeftBlock != null && rearRightBlock != null) {
            rearPolHairline.setVisibility(anyRearOk ? View.VISIBLE : View.GONE);
            rearLeftBlock.setVisibility(rlOk ? View.VISIBLE : View.GONE);
            rearRightBlock.setVisibility(rrOk ? View.VISIBLE : View.GONE);
            ((LinearLayout.LayoutParams) rearRightBlock.getLayoutParams()).topMargin =
                    (rlOk && rrOk) ? d(8) : 0;
        }

        // Temperature Thresholds tab
        if (summerCard != null && winterCard != null) {
            summerCard.setVisibility(ventOk ? View.VISIBLE : View.GONE);
            ((LinearLayout.LayoutParams) winterCard.getLayoutParams()).rightMargin =
                    ventOk ? d(6) : 0;
            if (lblWinterBluelinkNote != null) {
                lblWinterBluelinkNote.setVisibility(ventOk ? View.GONE : View.VISIBLE);
            }
        }

        updateHeaderText();
    }

    private void render(ComfortEngine.Snapshot s) {
        if (engine == null || s.configSeq >= engine.getConfigSeq()) {
            current = s.config;
        }
        Map<String, String> r = s.readings;
        latestPresence = s.passengerPresence == null
                ? OccupantSignalProbe.PassengerPresence.UNAVAILABLE
                : s.passengerPresence;
        latestCabin = s.cabinState == null
                ? OccupantSignalProbe.CabinState.UNAVAILABLE
                : s.cabinState;
        OccupantSignalProbe.SeatCapabilities nextCaps = s.seatCaps == null
                ? OccupantSignalProbe.SeatCapabilities.UNKNOWN
                : s.seatCaps;
        boolean capsChanged = !nextCaps.equals(latestCaps);
        boolean unitChanged = s.metricUnits != latestMetric;
        latestMetric = s.metricUnits;
        if (capsChanged) {
            latestCaps = nextCaps;
            applyHardwareCapabilities();
        } else if (unitChanged) {
            updateHeaderText();
        }
        boolean ventSupported = latestCaps.isFrontVentSupported();

        // HMG-API connection status pill
        if (s.connected) {
            styleChip(lblConnChip, "Connected · HMG-API", OK_CONT, ON_OK_CONT);
        } else {
            styleChip(lblConnChip, "Waiting · HMG-API", SURFACE_HIGH, ON_SURFACE_VAR);
        }

        // Ambient temperature (respecting vehicle °C vs °F unit setting)
        latestAmbientF = ComfortPolicy.readAmbientF(r);
        latestAmbientC = ComfortPolicy.readAmbientC(r);
        if (latestMetric) {
            if (!Float.isNaN(latestAmbientC)) {
                lblAmbientTemp.setText(ComfortPolicy.formatTenths(latestAmbientC) + "°C");
                lblAmbientSub.setText(Float.isNaN(latestAmbientF) ? "" : (Math.round(latestAmbientF) + "°F"));
            } else {
                lblAmbientTemp.setText("--°C");
                lblAmbientSub.setText("");
            }
        } else {
            if (!Float.isNaN(latestAmbientF)) {
                lblAmbientTemp.setText(Math.round(latestAmbientF) + "°F");
                lblAmbientSub.setText(Float.isNaN(latestAmbientC) ? "" : (ComfortPolicy.formatTenths(latestAmbientC) + "°C"));
            } else {
                lblAmbientTemp.setText("--°F");
                lblAmbientSub.setText("");
            }
        }

        // Powertrain & wheel chips
        boolean evReady = r != null && "1".equals(r.get("hv.ready"));
        boolean charging = ComfortPolicy.isChargingActive(r);
        boolean cabinOn = ComfortPolicy.isBcmSeatClimateReady(r) || latestCabin.ignOn == 1;
        boolean evOff = r != null && "0".equals(r.get("hv.ready"));
        if (evReady) {
            styleChip(chipEv, "EV Ready", OK_CONT, ON_OK_CONT);
        } else if (charging && cabinOn) {
            styleChip(chipEv, "Charge · ON", OK_CONT, ON_OK_CONT);
        } else if (charging && latestCabin.ignOn == 0) {
            styleChip(chipEv, "Charge · ACC", TERTIARY_CONT, ON_TERTIARY_CONT);
        } else if (charging) {
            styleChip(chipEv, "Charging", TERTIARY_CONT, ON_TERTIARY_CONT);
        } else if (cabinOn) {
            styleChip(chipEv, "Cabin On", SECONDARY_CONT, ON_SECONDARY_CONT);
        } else {
            styleChip(chipEv, evOff ? "Standby" : "EV --", SURFACE_HIGH, ON_SURFACE_VAR);
        }

        boolean inPark = r != null && "1".equals(r.get("gear.park"));
        boolean inDrive = r != null && "0".equals(r.get("gear.park"));
        styleChip(chipGear, inPark ? "Park" : (inDrive ? "Drive" : "Gear --"),
                inPark ? SECONDARY_CONT : SURFACE_HIGH,
                inPark ? ON_SECONDARY_CONT : ON_SURFACE_VAR);

        boolean wheelOn = r != null && "1".equals(r.get("wheel_heat"));
        boolean wheelOff = r != null && "0".equals(r.get("wheel_heat"));
        styleChip(chipWheel, wheelOn ? "Wheel On" : (wheelOff ? "Wheel Off" : "Wheel --"),
                wheelOn ? PRIMARY_CONT : SURFACE_HIGH,
                wheelOn ? ON_PRIMARY_CONT : ON_SURFACE_VAR);

        // Cabin live seat tiles + passenger occupancy badge
        updateSeatTile(tileDriver, r, "driver", ventSupported);
        updateSeatTile(tilePassenger, r, "passenger", ventSupported);
        updateSeatTile(tileRearLeft, r, "rear_left", false);
        updateSeatTile(tileRearRight, r, "rear_right", false);
        if (latestPresence.available && latestPresence.occupied) {
            tilePassenger.setBadge("Occupied", OK);
        } else if (latestPresence.available) {
            tilePassenger.setBadge("Empty", ON_SURFACE_VAR);
        } else {
            tilePassenger.setBadge("", ON_SURFACE_VAR);
        }

        // Activity timestamps & readable messages
        bindActivity(s.lastWelcomeStatus, lblWelcomeTime, lblWelcomeStatus);
        bindActivity(s.lastClimateStatus, lblClimateTime, lblClimateStatus);

        updateControls(current);
    }

    private void updateSeatTile(ComfortWidgets.SeatTileView tile, Map<String, String> r, String key, boolean hasVent) {
        int heat = parseIntSafe(r == null ? null : r.get("seat_heat." + key));
        int vent = hasVent ? parseIntSafe(r == null ? null : r.get("seat_vent." + key)) : 0;
        if (heat > 0) tile.update(1, Math.min(3, heat));
        else if (vent > 0) tile.update(2, Math.min(3, vent));
        else tile.update(0, 0);
    }

    private void bindActivity(String raw, TextView timeChip, TextView msgView) {
        if (raw == null || raw.isEmpty()) {
            timeChip.setText("Idle");
            msgView.setText("—");
            return;
        }
        if (raw.startsWith("[") && raw.length() > 10 && raw.charAt(9) == ']') {
            timeChip.setText(raw.substring(1, 9));
            msgView.setText(raw.substring(10).trim());
        } else {
            timeChip.setText("Ready");
            msgView.setText(raw);
        }
    }

    private void updateControls(ComfortConfig c) {
        swWelcome.setChecked(c.welcomeDismiss, false);
        welcomeDelayRow.setAlpha(c.welcomeDismiss ? 1f : 0.45f);
        lblWelcomeDelay.setText(c.welcomeDelayMs == 0 ? "Instant" : (c.welcomeDelayMs + " ms"));

        swClimate.setChecked(c.climateEnabled, false);
        swWheel.setChecked(c.wheelEnabled, false);
        swVent.setChecked(c.ventEnabled, false);
        swToast.setChecked(c.toastOnApply, false);

        if (c.climateEnabled) {
            styleChip(lblClimateSummaryChip, "Auto Climate Active", OK_CONT, ON_OK_CONT);
        } else {
            styleChip(lblClimateSummaryChip, "Auto Climate Off", SURFACE_HIGH, ON_SURFACE_VAR);
        }

        segPassenger.setValue(c.passengerPolicy);
        swPassengerAuto.setChecked(c.passengerAutoDetect, false);
        passengerAutoRow.setAlpha(!ComfortConfig.POLICY_OFF.equals(c.passengerPolicy) ? 1f : 0.45f);
        if (latestPresence.available) {
            lblPassengerAutoSub.setText("Seat sensor: "
                    + (latestPresence.occupied ? "Occupied" : "Empty")
                    + " · auto-applies on late boarding");
        } else {
            lblPassengerAutoSub.setText("Uses seat weight & buckle sensors (auto-applies on late boarding)");
        }
        segRearLeft.setValue(c.rearLeftPolicy);
        segRearRight.setValue(c.rearRightPolicy);

        if (latestMetric) {
            lblHeatHigh.setText(c.heatHighC + "°C");
            lblHeatMed.setText(c.heatMedC + "°C");
            lblHeatLow.setText(c.heatLowC + "°C");
            lblWheelF.setText(c.wheelC + "°C");
            lblVentLow.setText(c.ventLowC + "°C");
            lblVentMed.setText(c.ventMedC + "°C");
            lblVentHigh.setText(c.ventHighC + "°C");
        } else {
            lblHeatHigh.setText(c.heatHighF + "°F");
            lblHeatMed.setText(c.heatMedF + "°F");
            lblHeatLow.setText(c.heatLowF + "°F");
            lblWheelF.setText(c.wheelF + "°F");
            lblVentLow.setText(c.ventLowF + "°F");
            lblVentMed.setText(c.ventMedF + "°F");
            lblVentHigh.setText(c.ventHighF + "°F");
        }

        spectrumBar.update(
                c, latestAmbientF, latestAmbientC, latestCaps.isFrontVentSupported(), latestMetric);
    }

    // ---- Helpers ----

    private LinearLayout m3Card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(shape(SURFACE_CONT, OUTLINE_VAR, Math.max(1, d(1)), d(20)));
        int p = d(14);
        l.setPadding(p, d(12), p, d(12));
        return l;
    }

    private View cardHeader(int iconKind, int badgeBg, int badgeFg, String title, String subtitle) {
        return cardHeaderRefs(iconKind, badgeBg, badgeFg, title, subtitle, null);
    }

    private View cardHeaderRefs(int iconKind, int badgeBg, int badgeFg,
                                String title, String subtitle, TextView[] outTitleAndSub) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ComfortWidgets.iconBadge(this, iconKind, badgeBg, badgeFg, 36, 19));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(d(10), 0, 0, 0);
        TextView tvTitle = label(title, 15.5f, ON_SURFACE, true);
        col.addView(tvTitle);
        TextView tvSub = null;
        if (subtitle != null && !subtitle.isEmpty()) {
            tvSub = label(subtitle, 12f, ON_SURFACE_VAR, false);
            col.addView(tvSub);
        }
        if (outTitleAndSub != null) {
            if (outTitleAndSub.length > 0) outTitleAndSub[0] = tvTitle;
            if (outTitleAndSub.length > 1) outTitleAndSub[1] = tvSub;
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout switchRow(int iconKind, int badgeBg, int badgeFg,
                                   String title, String subtitle, ComfortWidgets.M3Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ComfortWidgets.iconBadge(this, iconKind, badgeBg, badgeFg, 36, 19));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(d(12), 0, d(10), 0);
        textCol.addView(label(title, 14.5f, ON_SURFACE, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            textCol.addView(label(subtitle, 12f, ON_SURFACE_VAR, false));
        }
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw);

        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.performClick();
            }
        });
        return row;
    }

    private View hairline(int marginDp) {
        View v = new View(this);
        v.setBackgroundColor(OUTLINE_VAR);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, d(1)));
        lp.topMargin = d(marginDp);
        lp.bottomMargin = d(marginDp);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView statusChip(String text, int bg, int fg) {
        TextView t = new TextView(this);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(d(7), d(4), d(7), d(4));
        styleChip(t, text, bg, fg);
        return t;
    }

    private void styleChip(TextView chip, String text, int bg, int fg) {
        chip.setText(text);
        chip.setTextColor(fg);
        chip.setBackground(shape(bg, 0, 0, d(14)));
    }

    private LinearLayout.LayoutParams colWeight(int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = d(leftDp);
        lp.rightMargin = d(rightDp);
        return lp;
    }

    private LinearLayout.LayoutParams chipWeight(float weight, int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.leftMargin = d(leftDp);
        lp.rightMargin = d(rightDp);
        return lp;
    }

    private static String val(Map<String, String> r, String k) {
        String v = r == null ? null : r.get(k);
        return v == null || v.isEmpty() ? "-" : v;
    }

    private static int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int d(float dpVal) {
        return ComfortTheme.dp(this, dpVal);
    }
}
