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
    public static native boolean nativeIsReady();
    public static native String  nativeInject(byte[] bytecode, int len);

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
        String[][] snippets = {
            {"chat",    "player.chat = \"Hello!\";"},
            {"label",   "this.x = player.x;\nthis.y = player.y;\nthis.showtext(1, 0, -2, \"[EXEC]\", \"\", \"arial\");"},
            {"speed",   "player.defaultwalkspeed = 5;"},
            {"hearts",  "player.hearts = 20;"},
            {"freeze",  "player.freezetime = 999;"},
            {"bombs",   "player.bombs = 99;\nplayer.darts = 99;"},
            {"collect", "function onCreated() {\n  setTimer(0.05);\n}\nfunction onTimeout() {\n  triggeraction(player.x, player.y, \"TrashPick\", \"Trash_Pick\");\n  setTimer(0.05);\n}"},
            {"echo",    "echo(\"debug — press F2 to see\");"},
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
        editor.setHint("say(\"Hello!\");");
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
                log("Compiling...");
                byte[] bytecode = compileGS2(source);
                if (bytecode == null || bytecode.length == 0) { log("Compile error."); return; }
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
            // Keep last 20 lines
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

    // ── GS2 Compiler ─────────────────────────────────────────────────────────────

    private static final int OP_CALL      = 0x06;
    private static final int OP_RET       = 0x07;
    private static final int OP_NUMBER    = 0x14;
    private static final int OP_STRING    = 0x15;
    private static final int OP_VAR       = 0x16;
    private static final int OP_ARRAY     = 0x17;
    private static final int OP_TRUE      = 0x18;
    private static final int OP_FALSE     = 0x19;
    private static final int OP_NULL      = 0x1A;
    private static final int OP_INDEX_DEC = 0x20;
    private static final int OP_ASSIGN    = 0x32;

    private static final int T_STR = 1, T_NUM = 2, T_ID = 3, T_KW = 4, T_OP = 5, T_EOF = 6;

    private static class Token {
        int type; String val; double num;
        Token(int t, String v) { type = t; val = v; }
        Token(double n) { type = T_NUM; num = n; val = String.valueOf(n); }
    }

    private static List<String>  gStrings;
    private static List<int[]>   gFuncs;
    private static List<String>  gFuncNames;
    private static List<Integer> gBc;
    private static List<Token>   gTokens;
    private static int           gTi;

    public static byte[] compileGS2(String src) {
        src = src.replaceAll("//#?CLIENTSIDE\\s*", "");
        src = src.replaceAll("//[^\n]*", "").replaceAll("/\\*[\\s\\S]*?\\*/", "").trim();
        src = src.replaceAll("(?m)^\\s*public\\s+(function)", "$1");
        if (!src.matches("(?s).*\\bfunction\\s+.*")) {
            src = "function onCreated() {\n" + src + "\n}";
        }

        gStrings   = new ArrayList<>();
        gFuncs     = new ArrayList<>();
        gFuncNames = new ArrayList<>();
        gBc        = new ArrayList<>();
        gTokens    = new ArrayList<>();
        gTi        = 0;

        tokenize(src);
        while (gTi < gTokens.size()) parseStatement();

        List<Integer> out = new ArrayList<>();

        // Segment 2: function name table (type 2 per GS2 spec)
        List<Integer> fnSeg = new ArrayList<>();
        for (int i = 0; i < gFuncs.size(); i++) {
            i32be(fnSeg, gFuncs.get(i)[0]);
            for (char c : gFuncNames.get(i).toCharArray()) fnSeg.add((int)c);
            fnSeg.add(0);
        }
        if (!fnSeg.isEmpty()) { i32be(out, 2); i32be(out, fnSeg.size()); out.addAll(fnSeg); }

        // Segment 3: string table
        List<Integer> strSeg = new ArrayList<>();
        for (String s : gStrings) {
            for (char c : s.toCharArray()) strSeg.add((int)c & 0xFF);
            strSeg.add(0);
        }
        if (!strSeg.isEmpty()) { i32be(out, 3); i32be(out, strSeg.size()); out.addAll(strSeg); }

        // Segment 4: bytecode
        i32be(out, 4); i32be(out, gBc.size()); out.addAll(gBc);

        byte[] result = new byte[out.size()];
        for (int i = 0; i < out.size(); i++) result[i] = (byte)(out.get(i) & 0xFF);
        return result;
    }

    private static void i32be(List<Integer> out, int n) {
        out.add((n >> 24) & 0xFF); out.add((n >> 16) & 0xFF);
        out.add((n >>  8) & 0xFF); out.add( n        & 0xFF);
    }

    private static int strIdx(String s) {
        int i = gStrings.indexOf(s);
        if (i >= 0) return i;
        gStrings.add(s); return gStrings.size() - 1;
    }

    private static void encStrRef(int idx) {
        if (idx < 256) { gBc.add(0xF0); gBc.add(idx); }
        else           { gBc.add(0xF1); gBc.add((idx >> 8) & 0xFF); gBc.add(idx & 0xFF); }
    }

    private static void emitStr(int op, String s) { gBc.add(op); encStrRef(strIdx(s)); }

    private static void emitNum(double n) {
        gBc.add(OP_NUMBER);
        long l = (long)n;
        if (n == l && l >= -128 && l <= 127) {
            gBc.add(0xF3); gBc.add((int)(l < 0 ? l + 256 : l));
        } else if (n == l && l >= -32768 && l <= 32767) {
            gBc.add(0xF4); gBc.add((int)((l >> 8) & 0xFF)); gBc.add((int)(l & 0xFF));
        } else {
            gBc.add(0xF6);
            for (char c : String.valueOf(n).toCharArray()) gBc.add((int)c);
            gBc.add(0);
        }
    }

    // ── Tokenizer ────────────────────────────────────────────────────────────────
    private static final java.util.Set<String> KEYWORDS = new java.util.HashSet<>(
        java.util.Arrays.asList("function","if","else","while","for","return","break",
            "true","false","null","this","temp","player","level","client","params","npcs"));

    private static void tokenize(String src) {
        int p = 0, len = src.length();
        while (p < len) {
            char c = src.charAt(p);
            if (Character.isWhitespace(c)) { p++; continue; }

            if (c == '"' || c == '\'') {
                char q = c; p++; StringBuilder sb = new StringBuilder();
                while (p < len && src.charAt(p) != q) {
                    if (src.charAt(p) == '\\' && p+1 < len) {
                        p++; char e = src.charAt(p);
                        if (e == 'n') sb.append('\n');
                        else if (e == 't') sb.append('\t');
                        else sb.append(e);
                    } else sb.append(src.charAt(p));
                    p++;
                }
                p++;
                gTokens.add(new Token(T_STR, sb.toString())); continue;
            }

            if (Character.isDigit(c) || (c == '-' && p+1 < len && Character.isDigit(src.charAt(p+1)))) {
                StringBuilder sb = new StringBuilder();
                if (c == '-') { sb.append(c); p++; }
                while (p < len && (Character.isDigit(src.charAt(p)) || src.charAt(p) == '.'))
                    sb.append(src.charAt(p++));
                gTokens.add(new Token(Double.parseDouble(sb.toString()))); continue;
            }

            if (Character.isLetter(c) || c == '_' || c == '$') {
                StringBuilder sb = new StringBuilder();
                while (p < len && (Character.isLetterOrDigit(src.charAt(p)) || src.charAt(p) == '_' || src.charAt(p) == '$'))
                    sb.append(src.charAt(p++));
                String word = sb.toString();
                gTokens.add(new Token(KEYWORDS.contains(word) ? T_KW : T_ID, word)); continue;
            }

            if (p+1 < len) {
                String two = "" + c + src.charAt(p+1);
                if (two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                    || two.equals("&&") || two.equals("||") || two.equals("++") || two.equals("--")) {
                    gTokens.add(new Token(T_OP, two)); p += 2; continue;
                }
            }
            gTokens.add(new Token(T_OP, String.valueOf(c))); p++;
        }
    }

    private static Token peek()          { return gTi < gTokens.size() ? gTokens.get(gTi) : new Token(T_EOF,""); }
    private static Token next()          { return gTi < gTokens.size() ? gTokens.get(gTi++) : new Token(T_EOF,""); }
    private static boolean eat(String v) { if (peek().val.equals(v)) { gTi++; return true; } return false; }
    private static void expect(String v) { if (peek().val.equals(v)) gTi++; }

    // ── Parser / Code Generator ───────────────────────────────────────────────────

    // Consume a dotted identifier chain: "player", "player.x", "temp.n.x", etc.
    // `first` is the already-consumed first segment.
    private static String parseDottedName(String first) {
        StringBuilder sb = new StringBuilder(first);
        while (peek().val.equals(".")) {
            next();
            Token t = next();
            sb.append('.').append(t.val);
        }
        return sb.toString();
    }

    // Emit a single expression value onto the GS2 stack.
    private static void emitExpr() {
        Token tok = peek();
        if (tok.type == T_STR) {
            emitStr(OP_STRING, next().val);
        } else if (tok.type == T_NUM) {
            emitNum(next().num);
        } else if (tok.type == T_KW && tok.val.equals("true"))  { next(); gBc.add(OP_TRUE); }
        else if  (tok.type == T_KW && tok.val.equals("false")) { next(); gBc.add(OP_FALSE); }
        else if  (tok.type == T_KW && tok.val.equals("null"))  { next(); gBc.add(OP_NULL); }
        else if  (tok.type == T_ID || tok.type == T_KW) {
            String name = parseDottedName(next().val);
            if (peek().val.equals("(")) {
                // function/method call as expression (result stays on stack)
                next();
                gBc.add(OP_ARRAY);
                emitArgList();
                expect(")");
                emitStr(OP_VAR, name);
                gBc.add(OP_CALL);
            } else {
                emitStr(OP_VAR, name);
            }
        }
    }

    // Emit comma-separated argument expressions (used inside function calls).
    private static void emitArgList() {
        while (!peek().val.equals(")") && peek().type != T_EOF) {
            emitExpr();
            eat(",");
        }
    }

    private static void parseStatement() {
        Token tok = peek();
        if (tok.type == T_EOF || tok.val.equals("}")) return;

        // function declaration — supports both "function name()" and "function obj.event()"
        if (tok.type == T_KW && tok.val.equals("function")) {
            next();
            String name = next().val;
            if (peek().val.equals(".")) { next(); name = name + "." + next().val; }
            expect("(");
            while (!peek().val.equals(")") && peek().type != T_EOF) next();
            expect(")"); expect("{");
            int pos = gBc.size();
            gFuncs.add(new int[]{pos}); gFuncNames.add(name);
            while (!peek().val.equals("}") && peek().type != T_EOF) parseStatement();
            eat("}"); gBc.add(OP_RET); return;
        }

        // return statement
        if (tok.type == T_KW && tok.val.equals("return")) {
            next();
            if (!peek().val.equals(";") && !peek().val.equals("}") && peek().type != T_EOF)
                emitExpr();
            eat(";"); gBc.add(OP_RET); return;
        }

        // identifier statement: assignment or call
        // handles: name = expr, name.a.b = expr, name(...), name.a.b(...)
        if (tok.type == T_ID || tok.type == T_KW) {
            String name = parseDottedName(next().val);

            if (peek().val.equals("=") && !peekTwo().equals("=")) {
                // assignment
                next();
                emitExpr();
                emitStr(OP_VAR, name); gBc.add(OP_ASSIGN);
            } else if (peek().val.equals("(")) {
                // call as statement — discard return value
                next();
                gBc.add(OP_ARRAY);
                emitArgList();
                expect(")");
                emitStr(OP_VAR, name);
                gBc.add(OP_CALL); gBc.add(OP_INDEX_DEC);
            }
            eat(";"); return;
        }

        next(); // skip unrecognised token
    }

    private static String peekTwo() {
        if (gTi + 1 < gTokens.size()) return gTokens.get(gTi + 1).val;
        return "";
    }
}
