package com.graal.exec;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

public class GraalExec {

    // ── Native bridge ────────────────────────────────────────────────────────────
    static boolean sNativeLoaded = false;
    static String  sNativeError  = null;
    static {
        try {
            System.loadLibrary("graalexec");
            sNativeLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            sNativeError = e.getMessage();
            android.util.Log.e("GraalExec", "native lib failed: " + e.getMessage());
        }
    }
    public static native boolean  nativeIsReady();
    public static native byte[]   nativeCompile(String source);
    public static native String   nativeInject(byte[] bytecode, int len);

    // ── Built-in snippets ─────────────────────────────────────────────────────────
    private static final String SNIP_GUNMOD =
        "//#CLIENTSIDE\n" +
        "function onCreated() {\n" +
        "    (@ \"global\").infAmmoActive = false;\n" +
        "    (@ \"global\").rapidFireActive = false;\n" +
        "    (@ \"global\").noSpreadActive = false;\n" +
        "    if ((@ \"global\").gunCache == nil) {\n" +
        "        (@ \"global\").gunCache = {};\n" +
        "    }\n" +
        "    hookfunction(this, \"\", \"setClipStored\", \"onClipHook\");\n" +
        "    showGui();\n" +
        "    echo(\">>> Weapon System Hooked!\");\n" +
        "}\n" +
        "function showGui() {\n" +
        "    if (findobject(\"WeaponWin\") != nil) findobject(\"WeaponWin\").destroy();\n" +
        "    new GuiWindowCtrl(\"WeaponWin\") {\n" +
        "        profile = GuiBlueWindowProfile;\n" +
        "        title = \"Weapon Mod v2\";\n" +
        "        x = 200; y = 200;\n" +
        "        width = 180; height = 160;\n" +
        "        new GuiButtonCtrl(\"BtnInfAmmo\") {\n" +
        "            profile = GuiBlueButtonProfile;\n" +
        "            x = 10; y = 30; width = 160; height = 30;\n" +
        "            text = \"Inf Ammo: OFF\";\n" +
        "        }\n" +
        "        new GuiButtonCtrl(\"BtnRapid\") {\n" +
        "            profile = GuiBlueButtonProfile;\n" +
        "            x = 10; y = 70; width = 160; height = 30;\n" +
        "            text = \"Rapid/NoFreeze: OFF\";\n" +
        "        }\n" +
        "        new GuiButtonCtrl(\"BtnNoSpread\") {\n" +
        "            profile = GuiBlueButtonProfile;\n" +
        "            x = 10; y = 110; width = 160; height = 30;\n" +
        "            text = \"No Spread: OFF\";\n" +
        "        }\n" +
        "    }\n" +
        "}\n" +
        "function BtnInfAmmo.onAction() {\n" +
        "    (@ \"global\").infAmmoActive = !(@ \"global\").infAmmoActive;\n" +
        "    BtnInfAmmo.text = ((@ \"global\").infAmmoActive ? \"Inf Ammo: ON\" : \"Inf Ammo: OFF\");\n" +
        "}\n" +
        "function BtnRapid.onAction() {\n" +
        "    (@ \"global\").rapidFireActive = !(@ \"global\").rapidFireActive;\n" +
        "    BtnRapid.text = ((@ \"global\").rapidFireActive ? \"Rapid/NoFreeze: ON\" : \"Rapid/NoFreeze: OFF\");\n" +
        "}\n" +
        "function BtnNoSpread.onAction() {\n" +
        "    (@ \"global\").noSpreadActive = !(@ \"global\").noSpreadActive;\n" +
        "    BtnNoSpread.text = ((@ \"global\").noSpreadActive ? \"No Spread: ON\" : \"No Spread: OFF\");\n" +
        "}\n" +
        "public function onClipHook() {\n" +
        "    temp.gunName = client.gun_weapon;\n" +
        "    temp.cur = findobject(temp.gunName);\n" +
        "    if (temp.cur == nil) return;\n" +
        "    temp.orig = nil;\n" +
        "    for (temp.item : (@ \"global\").gunCache) {\n" +
        "        if (temp.item[0] == temp.gunName) {\n" +
        "            temp.orig = temp.item;\n" +
        "            break;\n" +
        "        }\n" +
        "    }\n" +
        "    if (temp.orig == nil) {\n" +
        "        temp.newData = {\n" +
        "            temp.gunName,\n" +
        "            temp.cur.gun_spread,\n" +
        "            temp.cur.gun_repeatfire,\n" +
        "            temp.cur.player_freezereload,\n" +
        "            temp.cur.player_freezefire,\n" +
        "            temp.cur.player_freezefiredual\n" +
        "        };\n" +
        "        (@ \"global\").gunCache.add(temp.newData);\n" +
        "        temp.orig = temp.newData;\n" +
        "    }\n" +
        "    if ((@ \"global\").noSpreadActive) {\n" +
        "        temp.cur.gun_spread = 0;\n" +
        "    } else {\n" +
        "        temp.cur.gun_spread = temp.orig[1];\n" +
        "    }\n" +
        "    if ((@ \"global\").rapidFireActive) {\n" +
        "        temp.cur.gun_repeatfire = true;\n" +
        "        temp.cur.player_freezereload = {0, 0};\n" +
        "        temp.cur.player_freezefire = {0, 0};\n" +
        "        temp.cur.player_freezefiredual = {0, 0};\n" +
        "    } else {\n" +
        "        temp.cur.gun_repeatfire = temp.orig[2];\n" +
        "        temp.cur.player_freezereload = temp.orig[3];\n" +
        "        temp.cur.player_freezefire = temp.orig[4];\n" +
        "        temp.cur.player_freezefiredual = temp.orig[5];\n" +
        "    }\n" +
        "    if ((@ \"global\").infAmmoActive) {\n" +
        "        return;\n" +
        "    }\n" +
        "    temp.cur.setClipStored(params[0], params[1], params[2], params[3], params[4], params[5], params[6], params[7]);\n" +
        "}\n";

    private static final String SNIP_FARM =
        "//#CLIENTSIDE\n" +
        "function onCreated() {\n" +
        "    this.active     = false;\n" +
        "    this.espActive  = false;\n" +
        "    this.mushActive = false;\n" +
        "    this.maxDist    = 10;\n" +
        "    this.walkStep   = 0.5;\n" +
        "    showGui();\n" +
        "    setTimer(0.1);\n" +
        "}\n" +
        "function showGui() {\n" +
        "    if (findobject(\"TrashWin\") != nil) findobject(\"TrashWin\").destroy();\n" +
        "    new GuiWindowCtrl(\"TrashWin\") {\n" +
        "        profile = GuiBlueWindowProfile;\n" +
        "        title = \"Utility Tool\";\n" +
        "        x = 20; y = 200;\n" +
        "        width = 160; height = 160;\n" +
        "        new GuiButtonCtrl(\"BtnToggle\") {\n" +
        "            profile = GuiBlueButtonProfile;\n" +
        "            x = 10; y = 30; width = 140; height = 30;\n" +
        "            text = \"Auto-Farm: OFF\";\n" +
        "        }\n" +
        "        new GuiButtonCtrl(\"BtnEspToggle\") {\n" +
        "            profile = GuiBlueButtonProfile;\n" +
        "            x = 10; y = 70; width = 140; height = 30;\n" +
        "            text = \"Trash ESP: OFF\";\n" +
        "        }\n" +
        "        new GuiButtonCtrl(\"BtnMushToggle\") {\n" +
        "            profile = GuiBlueButtonProfile;\n" +
        "            x = 10; y = 110; width = 140; height = 30;\n" +
        "            text = \"Mushroom ESP: OFF\";\n" +
        "        }\n" +
        "    }\n" +
        "}\n" +
        "function BtnToggle.onAction() {\n" +
        "    this.active = !this.active;\n" +
        "    BtnToggle.text = (this.active ? \"Auto-Farm: ON\" : \"Auto-Farm: OFF\");\n" +
        "}\n" +
        "function BtnEspToggle.onAction() {\n" +
        "    this.espActive = !this.espActive;\n" +
        "    BtnEspToggle.text = (this.espActive ? \"Trash ESP: ON\" : \"Trash ESP: OFF\");\n" +
        "    if (!this.espActive && !this.mushActive) clearEsp();\n" +
        "}\n" +
        "function BtnMushToggle.onAction() {\n" +
        "    this.mushActive = !this.mushActive;\n" +
        "    BtnMushToggle.text = (this.mushActive ? \"Mushroom ESP: ON\" : \"Mushroom ESP: OFF\");\n" +
        "    if (!this.espActive && !this.mushActive) clearEsp();\n" +
        "}\n" +
        "public function DrawLine(ind, sx, sy, tx, ty, wid, r, g, b, alph) {\n" +
        "    temp.ang = getangle(tx - sx, ty - sy) + (3.14159 / 2);\n" +
        "    with (findimg(ind)) {\n" +
        "        image = \"\";\n" +
        "        polygon = {\n" +
        "            sx, sy,\n" +
        "            tx, ty,\n" +
        "            tx + cos(temp.ang) * wid, ty - sin(temp.ang) * wid,\n" +
        "            sx + cos(temp.ang) * wid, sy - sin(temp.ang) * wid\n" +
        "        };\n" +
        "        red = r; green = g; blue = b; alpha = alph;\n" +
        "        layer = 3;\n" +
        "    }\n" +
        "}\n" +
        "function clearEsp() {\n" +
        "    for (temp.i = 500; temp.i < 800; temp.i++) {\n" +
        "        hideimg(temp.i);\n" +
        "        showtext(temp.i, 0, 0, \"\", \"\", \"\");\n" +
        "    }\n" +
        "}\n" +
        "public function walkToward(tx, ty) {\n" +
        "    temp.dx = tx - (player.x + 1.5);\n" +
        "    temp.dy = ty - (player.y + 1.5);\n" +
        "    temp.d  = (temp.dx^2 + temp.dy^2)^0.5;\n" +
        "    if (temp.d <= 0.01) return;\n" +
        "    temp.step = (this.walkStep < temp.d ? this.walkStep : temp.d);\n" +
        "    temp.nx = player.x + (temp.dx / temp.d) * temp.step;\n" +
        "    temp.ny = player.y + (temp.dy / temp.d) * temp.step;\n" +
        "    if (abs(temp.dx) > abs(temp.dy)) player.dir = (temp.dx > 0 ? 3 : 1);\n" +
        "    else player.dir = (temp.dy > 0 ? 2 : 0);\n" +
        "    if (!onwall(temp.nx + 1.5, temp.ny + 1.5)) {\n" +
        "        player.x = temp.nx;\n" +
        "        player.y = temp.ny;\n" +
        "    }\n" +
        "}\n" +
        "function onTimeout() {\n" +
        "    if (this.espActive || this.active || this.mushActive) {\n" +
        "        clearEsp();\n" +
        "        temp.idx = 500;\n" +
        "        temp.pX = player.x + 1.5;\n" +
        "        temp.pY = player.y + 1.5;\n" +
        "        temp.hasTarget = false;\n" +
        "        temp.bestDist  = 999999;\n" +
        "        temp.bestX = 0; temp.bestY = 0;\n" +
        "        for (temp.n : npcs) {\n" +
        "            temp.isTrash = (\"object_trash\" in temp.n.joinedclasses);\n" +
        "            temp.isMush  = (\"object_mushroom\" in temp.n.joinedclasses);\n" +
        "            if (temp.isTrash || temp.isMush) {\n" +
        "                temp.cX = temp.n.x + (temp.n.width / 2);\n" +
        "                temp.cY = temp.n.y + (temp.n.height / 2);\n" +
        "                temp.dx = temp.cX - temp.pX;\n" +
        "                temp.dy = temp.cY - temp.pY;\n" +
        "                temp.dist = (temp.dx^2 + temp.dy^2)^0.5;\n" +
        "                if ((temp.isTrash && this.espActive) || (temp.isMush && this.mushActive)) {\n" +
        "                    if (temp.isMush) {\n" +
        "                        this.DrawLine(temp.idx, temp.pX, temp.pY, temp.cX, temp.cY, 0.2, 1, 0, 1, 0.4);\n" +
        "                    } else if (temp.dist <= this.maxDist) {\n" +
        "                        this.DrawLine(temp.idx, temp.pX, temp.pY, temp.cX, temp.cY, 0.4, 0, 1, 0, 0.5);\n" +
        "                    } else {\n" +
        "                        this.DrawLine(temp.idx, temp.pX, temp.pY, temp.cX, temp.cY, 0.15, 1, 1, 1, 0.2);\n" +
        "                    }\n" +
        "                    temp.ratio = (temp.dist > 12.5 ? 10 / temp.dist : 0.8);\n" +
        "                    showtext(temp.idx + 1,\n" +
        "                             temp.pX + (temp.dx * temp.ratio),\n" +
        "                             temp.pY + (temp.dy * temp.ratio),\n" +
        "                             \"Arial\", \"b\", int(temp.dist) @ \"m\");\n" +
        "                    temp.idx += 2;\n" +
        "                }\n" +
        "                if (this.active && temp.isTrash && temp.dist < temp.bestDist) {\n" +
        "                    temp.bestDist = temp.dist;\n" +
        "                    temp.bestX = temp.cX; temp.bestY = temp.cY;\n" +
        "                    temp.hasTarget = true;\n" +
        "                }\n" +
        "            }\n" +
        "        }\n" +
        "        if (this.active && temp.hasTarget) {\n" +
        "            if (temp.bestDist <= this.maxDist) {\n" +
        "                triggeraction(temp.bestX, temp.bestY, \"TrashPick\", \"Trash_Pick\");\n" +
        "            } else {\n" +
        "                this.walkToward(temp.bestX, temp.bestY);\n" +
        "            }\n" +
        "        }\n" +
        "    }\n" +
        "    for (temp.p : players) {\n" +
        "        if (temp.p.alpha < 1 || temp.p.zoom == 0) {\n" +
        "            temp.p.alpha = 0.9;\n" +
        "            temp.p.zoom = 1;\n" +
        "        }\n" +
        "    }\n" +
        "    setTimer(0.1);\n" +
        "}\n";

    // ── UI state ─────────────────────────────────────────────────────────────────
    private static EditText  sEditor;
    private static TextView  sOutput;
    private static LinearLayout sPanel;
    private static boolean   sPanelOpen = true;

    // ── Entry point called from UnityPlayerActivity.onCreate ─────────────────────
    public static void init(final Activity activity) {
        activity.runOnUiThread(new Runnable() { public void run() { buildOverlay(activity); } });
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable roundRect(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    // ── Build overlay ─────────────────────────────────────────────────────────────
    private static void buildOverlay(Activity activity) {
        Context ctx = activity.getApplicationContext();
        FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();

        // ── FAB (M / ✕ toggle button) ─────────────────────────────────────────
        final TextView fab = new TextView(ctx);
        fab.setText("✕");
        fab.setTextColor(Color.parseColor("#2ecc71"));
        fab.setBackground(roundRect(Color.parseColor("#111111"), dp(ctx,20)));
        fab.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        fab.setTextSize(13);
        fab.setPadding(dp(ctx,10), dp(ctx,8), dp(ctx,10), dp(ctx,8));
        fab.setGravity(Gravity.CENTER);

        // ── Main panel ────────────────────────────────────────────────────────
        final LinearLayout panel = new LinearLayout(ctx);
        sPanel = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(roundRect(Color.parseColor("#161616"), dp(ctx,10)));
        panel.setPadding(dp(ctx,10), dp(ctx,10), dp(ctx,10), dp(ctx,10));

        // Title row
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(ctx);
        title.setText("GS2 Executor");
        title.setTextColor(Color.parseColor("#e0e0e0"));
        title.setTextSize(14);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleRow.addView(title, titleLp);

        // Close button
        final TextView closeBtn = new TextView(ctx);
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.parseColor("#e74c3c"));
        closeBtn.setTextSize(16);
        closeBtn.setPadding(dp(ctx,8), dp(ctx,2), dp(ctx,2), dp(ctx,2));
        titleRow.addView(closeBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        // Divider
        View div = new View(ctx);
        div.setBackgroundColor(Color.parseColor("#2a2a2a"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, 1);
        divLp.setMargins(0, dp(ctx,6), 0, dp(ctx,6));
        panel.addView(div, divLp);

        // Script snippet buttons
        HorizontalScrollView snippetScroll = new HorizontalScrollView(ctx);
        LinearLayout snippetRow = new LinearLayout(ctx);
        snippetRow.setOrientation(LinearLayout.HORIZONTAL);
        final String[][] snippets = {
            {"chat",    "player.chat = \"Hello!\";"},
            {"speed",   "player.defaultwalkspeed = 5;"},
            {"hearts",  "player.hearts = 20;"},
            {"freeze",  "player.freezetime = 999;"},
            {"bombs",   "player.bombs = 99;\nplayer.darts = 99;"},
            {"collect", "//#CLIENTSIDE\nfunction onCreated() {\n  setTimer(0.05);\n}\nfunction onTimeout() {\n  triggeraction(player.x, player.y, \"TrashPick\", \"Trash_Pick\");\n  setTimer(0.05);\n}"},
            {"echo",    "echo(\"debug\");"},
            {"gunmod",  SNIP_GUNMOD},
            {"farm",    SNIP_FARM},
        };
        for (final String[] snip : snippets) {
            final TextView btn = new TextView(ctx);
            btn.setText(snip[0]);
            btn.setTextColor(Color.parseColor("#3498db"));
            btn.setBackground(roundRect(Color.parseColor("#0d1a2a"), dp(ctx,4)));
            btn.setTextSize(11);
            btn.setPadding(dp(ctx,8), dp(ctx,4), dp(ctx,8), dp(ctx,4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(0, 0, dp(ctx,6), 0);
            btn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { if (sEditor != null) sEditor.setText(snip[1]); }
            });
            snippetRow.addView(btn, lp);
        }
        snippetScroll.addView(snippetRow, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(snippetScroll, new LinearLayout.LayoutParams(-1, -2));

        // Editor
        EditText editor = new EditText(ctx);
        sEditor = editor;
        editor.setHint("GS2 source...");
        editor.setTextColor(Color.parseColor("#e0e0e0"));
        editor.setHintTextColor(Color.parseColor("#555555"));
        editor.setBackground(roundRect(Color.parseColor("#0d0d0d"), dp(ctx,4)));
        editor.setTextSize(12);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setPadding(dp(ctx,8), dp(ctx,8), dp(ctx,8), dp(ctx,8));
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setLines(4);
        editor.setMaxLines(8);
        editor.setHorizontalScrollBarEnabled(false);
        editor.setText("player.chat = \"Hello from GS2!\";");
        LinearLayout.LayoutParams editorLp = new LinearLayout.LayoutParams(-1, -2);
        editorLp.setMargins(0, dp(ctx,6), 0, dp(ctx,6));
        panel.addView(editor, editorLp);

        // Button row
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button execBtn = new Button(ctx);
        execBtn.setText("Execute");
        execBtn.setTextColor(Color.WHITE);
        execBtn.setBackground(roundRect(Color.parseColor("#2ecc71"), dp(ctx,4)));
        execBtn.setAllCaps(false);
        execBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (sEditor == null) return;
                String src = sEditor.getText().toString().trim();
                if (!src.isEmpty()) executeGS2(src);
            }
        });
        LinearLayout.LayoutParams execLp = new LinearLayout.LayoutParams(0, -2, 1f);
        execLp.setMargins(0, 0, dp(ctx,6), 0);
        btnRow.addView(execBtn, execLp);

        Button clearBtn = new Button(ctx);
        clearBtn.setText("Clear");
        clearBtn.setTextColor(Color.WHITE);
        clearBtn.setBackground(roundRect(Color.parseColor("#333333"), dp(ctx,4)));
        clearBtn.setAllCaps(false);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (sOutput != null) sOutput.setText(""); }
        });
        btnRow.addView(clearBtn, new LinearLayout.LayoutParams(dp(ctx,70), -2));
        panel.addView(btnRow, new LinearLayout.LayoutParams(-1, -2));

        // Output log
        ScrollView outScroll = new ScrollView(ctx);
        TextView outputTv = new TextView(ctx);
        sOutput = outputTv;
        outputTv.setText("Ready. Tap Execute to run GS2.");
        outputTv.setTextColor(Color.parseColor("#2ecc71"));
        outputTv.setTextSize(11);
        outputTv.setTypeface(Typeface.MONOSPACE);
        outputTv.setPadding(dp(ctx,4), dp(ctx,4), dp(ctx,4), dp(ctx,4));
        outScroll.addView(outputTv, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(-1, dp(ctx,80));
        outLp.setMargins(0, dp(ctx,4), 0, 0);
        panel.addView(outScroll, outLp);

        // ── Attach both views to decor ─────────────────────────────────────────
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(ctx,44), dp(ctx,34));
        fabLp.gravity = Gravity.TOP | Gravity.END;
        fabLp.setMargins(0, dp(ctx,60), dp(ctx,10), 0);
        decor.addView(fab, fabLp);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(dp(ctx,340), -2);
        panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        panelLp.setMargins(0, dp(ctx,40), 0, 0);
        decor.addView(panel, panelLp);

        // Drag + toggle for FAB
        fab.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY, downRawX, downRawY;
            boolean dragged;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX(); downRawY = e.getRawY();
                        startX = fab.getTranslationX(); startY = fab.getTranslationY();
                        dragged = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) dragged = true;
                        fab.setTranslationX(startX + dx);
                        fab.setTranslationY(startY + dy);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) {
                            sPanelOpen = !sPanelOpen;
                            panel.setVisibility(sPanelOpen ? View.VISIBLE : View.GONE);
                            fab.setText(sPanelOpen ? "✕" : "M");
                        }
                        return true;
                }
                return false;
            }
        });

        closeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sPanelOpen = false;
                panel.setVisibility(View.GONE);
                fab.setText("M");
            }
        });

        log(sNativeLoaded ? "Executor ready." : "Native load FAILED: " + sNativeError);
    }

    // ── Execute GS2 source ────────────────────────────────────────────────────────
    public static void executeGS2(final String source) {
        new Thread(new Runnable() { public void run() {
            try {
                if (!sNativeLoaded) { log("Native lib not loaded — injection unavailable."); return; }

                // Ensure #CLIENTSIDE header is present
                String src = source;
                if (!src.startsWith("//#CLIENTSIDE") && !src.startsWith("#CLIENTSIDE")) {
                    src = "//#CLIENTSIDE\n" + src;
                }

                log("Compiling...");
                byte[] bytecode = nativeCompile(src);
                if (bytecode == null || bytecode.length == 0) {
                    log("Compile failed — check logcat for GS2 errors.");
                    return;
                }
                log(bytecode.length + " bytes compiled.");

                if (!nativeIsReady()) { log("Engine not in world yet — try after login."); return; }
                String result = nativeInject(bytecode, bytecode.length);
                log(result);
            } catch (Exception e) { log("Error: " + e.getMessage()); }
        }}).start();
    }

    // ── Log to output TextView (thread-safe) ──────────────────────────────────────
    public static void log(final String msg) {
        final TextView tv = sOutput;
        if (tv == null) return;
        tv.post(new Runnable() { public void run() {
            CharSequence cur = tv.getText();
            String next = (cur.length() > 0 ? cur + "\n" : "") + msg;
            String[] lines = next.split("\n");
            if (lines.length > 20) {
                StringBuilder sb = new StringBuilder();
                for (int i = lines.length - 20; i < lines.length; i++) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(lines[i]);
                }
                next = sb.toString();
            }
            tv.setText(next);
        }});
    }
}
