package com.packrush;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * PACKRUSH — full game panel: title, menu, how-to, settings, gameplay, pause, game-over.
 * Controls: SPACE/click = tape & pack, A S D / 1 2 3 = sort, drag & drop works too.
 */
public class PackRushGame extends JPanel implements ActionListener,
        KeyListener, MouseListener, MouseMotionListener {

    public static final int W = 1280, H = 720;
    private static final int BELT_Y = 330;
    private static final int PACK_X0 = 40, PACK_X1 = 560;
    private static final int SORT_X0 = 600, SORT_X1 = 1090;
    private static final int MISS_X = 1210;

    enum Screen { TITLE, MENU, HOWTO, SETTINGS, GAME, PAUSE, GAMEOVER }

    private Screen screen = Screen.TITLE;
    private final javax.swing.Timer timer;
    private long lastNano;
    private double animT = 0;

    // settings + save
    private int difficulty = 1; // 0 chill 1 standard 2 rush
    private boolean shakeOn = true;
    private int best = 0;
    private final Sound sound = new Sound();
    private final File saveFile = new File("packrush_save.properties");

    // run state
    private final List<Parcel> parcels = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<Floater> floaters = new ArrayList<>();
    private int score, combo, maxCombo, lives;
    private double timeLeft, timeTotal, elapsed, spawnTimer;
    private int sorted, missed, wrongCount, poorCount, tapedCount;
    private int wave = 1;
    private double waveTimer = 0;
    private String banner = "";
    private double bannerT = 0;
    private String hintMsg = "";
    private double hintT = 0;
    private double shakeT = 0;
    private boolean won;
    private String rank = "D";
    private boolean newBest = false;
    private final double[] truckBounce = {0, 0, 0};

    // mouse
    private int mx = -100, my = -100;
    private boolean mouseDown = false;
    private Parcel dragParcel = null;
    private int pressX, pressY;
    private boolean pressOnParcel = false;
    private boolean draggedFar = false;

    // title demo parcels
    private final List<Parcel> demo = new ArrayList<>();

    static class Particle {
        double x, y, vx, vy, life, maxLife; Color c; int s;
    }
    static class Floater {
        double x, y, life; String text; Color c; int size;
    }
    static class Btn {
        String id, label; int x, y, w, h; String sub;
        Btn(String id, String label, int x, int y, int w, int h, String sub) {
            this.id = id; this.label = label; this.x = x; this.y = y;
            this.w = w; this.h = h; this.sub = sub;
        }
        boolean hit(int px, int py) { return px >= x && px <= x + w && py >= y && py <= y + h; }
    }

    public PackRushGame() {
        setBackground(new Color(13, 16, 32));
        setFocusable(true);
        addKeyListener(this);
        addMouseListener(this);
        addMouseMotionListener(this);
        loadSave();
        sound.setMuted(loadMute());
        for (int i = 0; i < 6; i++) {
            demo.add(new Parcel(i % 3, 100 + i * 210, BELT_Y, i % 3, i == 4, i == 2));
            demo.get(i).tapsLeft = (i % 2 == 0) ? 0 : 1;
        }
        timer = new javax.swing.Timer(16, this);
        lastNano = System.nanoTime();
        timer.start();
    }

    // ---------- save ----------
    private void loadSave() {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(saveFile)) {
            p.load(in);
            best = Integer.parseInt(p.getProperty("best", "0"));
            difficulty = Integer.parseInt(p.getProperty("difficulty", "1"));
        } catch (Exception ignored) {}
    }
    private boolean loadMute() {
        try (FileInputStream in = new FileInputStream(saveFile)) {
            Properties p = new Properties(); p.load(in);
            return Boolean.parseBoolean(p.getProperty("muted", "false"));
        } catch (Exception e) { return false; }
    }
    private void save() {
        Properties p = new Properties();
        p.setProperty("best", String.valueOf(best));
        p.setProperty("difficulty", String.valueOf(difficulty));
        p.setProperty("muted", String.valueOf(sound.isMuted()));
        try (FileOutputStream out = new FileOutputStream(saveFile)) { p.store(out, "packrush"); }
        catch (Exception ignored) {}
    }

    private double beltSpeed() {
        double base = difficulty == 0 ? 130 : (difficulty == 1 ? 175 : 225);
        return base * (1 + Math.min(0.85, elapsed / 75.0 * 0.55 + (wave - 1) * 0.07));
    }
    private double spawnInterval() {
        double base = difficulty == 0 ? 1.5 : (difficulty == 1 ? 1.12 : 0.85);
        return Math.max(0.42, base * (1 - Math.min(0.45, elapsed / 90.0 * 0.4)));
    }
    private double fragileRate() {
        return difficulty == 0 ? 0.08 : (difficulty == 1 ? 0.16 : 0.24);
    }

    // ---------- flow ----------
    private void startGame() {
        parcels.clear(); particles.clear(); floaters.clear();
        score = 0; combo = 0; maxCombo = 0; lives = 3;
        sorted = 0; missed = 0; wrongCount = 0; poorCount = 0; tapedCount = 0;
        elapsed = 0; wave = 1; waveTimer = 0;
        timeTotal = difficulty == 0 ? 120 : (difficulty == 1 ? 100 : 80);
        timeLeft = timeTotal;
        spawnTimer = 0.4;
        newBest = false; won = false; rank = "D";
        Arrays.fill(truckBounce, 0);
        banner("SHIFT START! TAPE, THEN SORT!", 2.0);
        sound.go();
        screen = Screen.GAME;
    }

    private void endGame(boolean win) {
        won = win && lives > 0;
        rank = score >= 2500 ? "S" : score >= 1800 ? "A" : score >= 1200 ? "B" : score >= 700 ? "C" : "D";
        if (!won && lives <= 0) rank = "F";
        if (score > best) { best = score; newBest = true; }
        save();
        if (won) sound.fanfare(); else sound.bad();
        screen = Screen.GAMEOVER;
    }

    private void banner(String t, double dur) { banner = t; bannerT = dur; }
    private void hint(String t) { hintMsg = t; hintT = 1.6; }
    private void shake(double dur) { if (shakeOn) shakeT = Math.max(shakeT, dur); }

    private void burst(double x, double y, Color c, int n) {
        Random r = new Random();
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            p.x = x; p.y = y;
            p.vx = (r.nextDouble() - 0.5) * 380;
            p.vy = -120 - r.nextDouble() * 260;
            p.life = p.maxLife = 0.5 + r.nextDouble() * 0.5;
            p.c = c; p.s = 3 + r.nextInt(5);
            particles.add(p);
        }
    }
    private void floater(double x, double y, String text, Color c, int size) {
        Floater f = new Floater();
        f.x = x; f.y = y; f.text = text; f.c = c; f.size = size; f.life = 1.2;
        floaters.add(f);
    }

    // ---------- gameplay ops ----------
    private void spawnParcel() {
        Random r = new Random();
        int color = r.nextInt(3);
        int size = r.nextInt(3);
        boolean golden = r.nextDouble() < 0.05;
        boolean fragile = !golden && r.nextDouble() < fragileRate();
        Parcel p = new Parcel(color, -70, BELT_Y, size, fragile, golden);
        parcels.add(p);
        sound.spawn();
    }

    private Parcel oldestIn(int x0, int x1) {
        Parcel bestP = null;
        for (Parcel p : parcels) {
            if (p.dragging) continue;
            if (p.x >= x0 && p.x <= x1) {
                if (bestP == null || p.x < bestP.x) bestP = p;
            }
        }
        return bestP;
    }

    private void tapeFront() {
        Parcel p = oldestIn(PACK_X0 - 60, SORT_X1);
        if (p == null) p = oldestIn(-200, MISS_X);
        if (p == null) { hint("NO PARCEL TO TAPE YET"); return; }
        tapeParcel(p);
    }

    private void tapeParcel(Parcel p) {
        if (p.golden || p.isTaped()) {
            hint("ALREADY PACKED — SORT IT!");
            return;
        }
        p.tapsLeft--;
        p.wobble = 1.0;
        tapedCount++;
        sound.tape();
        burst(p.x, p.y - 30, new Color(255, 176, 32), 8);
        if (p.isTaped()) floater(p.x, p.y - 55, p.fragile ? "PACKED x2!" : "PACKED!", new Color(61, 255, 138), 18);
        else floater(p.x, p.y - 55, "TAPE AGAIN! FRAGILE", new Color(255, 207, 46), 16);
    }

    private void sortKey(int bin) {
        Parcel p = oldestIn(SORT_X0, SORT_X1);
        if (p == null) {
            Parcel upcoming = oldestIn(PACK_X0, SORT_X0);
            hint(upcoming != null ? "WAIT FOR THE SORT ZONE →" : "NO PARCEL IN SORT ZONE");
            sound.poor();
            return;
        }
        resolveSort(p, bin);
    }

    private void resolveSort(Parcel parcel, int bin) {
        parcels.remove(parcel);
        if (dragParcel == parcel) dragParcel = null;
        double bx = binCenterX(bin), by = 610;
        if (parcel.color == bin) {
            if (parcel.golden) {
                score += 500; combo++;
                floater(bx, by - 110, "+500 GOLDEN!", new Color(255, 215, 80), 24);
                burst(bx, by - 60, new Color(255, 215, 80), 26);
                sound.good();
            } else if (parcel.isTaped()) {
                int pts = 100 + combo * 15 + (parcel.fragile ? 50 : 0);
                score += pts; combo++; sorted++;
                maxCombo = Math.max(maxCombo, combo);
                floater(bx, by - 110, "+" + pts + (combo > 1 ? "  x" + combo : ""), new Color(61, 255, 138), 22);
                burst(bx, by - 60, new Color(61, 255, 138), 18);
                sound.good();
            } else {
                score += 30; poorCount++; combo = 0;
                floater(bx, by - 110, "+30 NEEDS TAPE!", new Color(255, 207, 46), 20);
                burst(bx, by - 60, new Color(255, 207, 46), 12);
                sound.poor();
                hint("TAPE IT FIRST! (SPACE / CLICK)");
            }
            maxCombo = Math.max(maxCombo, combo);
            truckBounce[bin] = 1.0;
        } else {
            lives--; wrongCount++; combo = 0;
            floater(bx, by - 110, "WRONG TRUCK!", new Color(255, 77, 94), 22);
            burst(bx, by - 60, new Color(255, 77, 94), 20);
            shake(0.35);
            sound.bad();
            hint("THAT WAS " + Parcel.NAMES[parcel.color] + " — NOT " + Parcel.NAMES[bin] + "!");
            if (lives <= 0) { endGame(false); }
        }
    }

    private double binCenterX(int bin) {
        return bin == 0 ? 250 : (bin == 1 ? 640 : 1030);
    }

    private int binAt(int px, int py) {
        int[][] r = binRects();
        for (int i = 0; i < 3; i++) {
            if (px >= r[i][0] && px <= r[i][0] + r[i][2] && py >= r[i][1] && py <= r[i][1] + r[i][3]) return i;
        }
        return -1;
    }
    private int[][] binRects() {
        return new int[][]{{80, 520, 340, 150}, {470, 520, 340, 150}, {860, 520, 340, 150}};
    }

    // ---------- update ----------
    @Override
    public void actionPerformed(ActionEvent e) {
        long now = System.nanoTime();
        double dt = Math.min(0.05, (now - lastNano) / 1e9);
        lastNano = now;
        animT += dt;
        update(dt);
        repaint();
    }

    private void update(double dt) {
        for (Parcel p : demo) {
            p.x += 120 * dt;
            if (p.x > W + 80) p.x = -80;
        }
        if (bannerT > 0) bannerT -= dt;
        if (hintT > 0) hintT -= dt;
        if (shakeT > 0) shakeT -= dt;
        for (int i = 0; i < 3; i++) if (truckBounce[i] > 0) truckBounce[i] = Math.max(0, truckBounce[i] - dt * 3);

        Iterator<Particle> pi = particles.iterator();
        while (pi.hasNext()) {
            Particle p = pi.next();
            p.life -= dt;
            if (p.life <= 0) { pi.remove(); continue; }
            p.x += p.vx * dt; p.y += p.vy * dt; p.vy += 700 * dt;
        }
        Iterator<Floater> fi = floaters.iterator();
        while (fi.hasNext()) {
            Floater f = fi.next();
            f.life -= dt; f.y -= 45 * dt;
            if (f.life <= 0) fi.remove();
        }
        if (screen != Screen.GAME) return;

        elapsed += dt;
        timeLeft -= dt;
        waveTimer += dt;
        if (waveTimer >= 20) { waveTimer = 0; wave++; banner("SPEED UP! — WAVE " + wave, 1.8); sound.count(); }

        spawnTimer -= dt;
        if (spawnTimer <= 0) { spawnParcel(); spawnTimer = spawnInterval(); }

        double sp = beltSpeed();
        Iterator<Parcel> it = parcels.iterator();
        while (it.hasNext()) {
            Parcel p = it.next();
            if (p.dragging) continue;
            p.x += sp * dt;
            p.bobPhase += dt * 4;
            if (p.wobble > 0) p.wobble = Math.max(0, p.wobble - dt * 4);
            if (p.x > MISS_X) {
                it.remove();
                missed++; combo = 0; lives--;
                floater(MISS_X - 60, BELT_Y - 70, "MISSED!", new Color(255, 77, 94), 22);
                burst(MISS_X - 60, BELT_Y, new Color(255, 77, 94), 16);
                shake(0.35);
                sound.bad();
                if (lives <= 0) { endGame(false); return; }
            }
        }
        if (timeLeft <= 0) { timeLeft = 0; endGame(true); }
        if (timeLeft <= 10.5 && timeLeft > 0) {
            // countdown beep each whole second
            double prev = timeLeft + dt;
            if ((int) Math.ceil(prev) != (int) Math.ceil(timeLeft)) sound.count();
        }
    }

    // ================= RENDER =================
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        if (shakeT > 0) {
            Random r = new Random();
            g2.translate((r.nextDouble() - 0.5) * 10 * shakeT * 3, (r.nextDouble() - 0.5) * 10 * shakeT * 3);
        }
        drawWarehouse(g2);
        switch (screen) {
            case TITLE -> drawTitle(g2);
            case MENU -> drawMenu(g2);
            case HOWTO -> drawHowto(g2);
            case SETTINGS -> drawSettings(g2);
            case GAME, PAUSE -> { drawGame(g2); if (screen == Screen.PAUSE) drawPause(g2); }
            case GAMEOVER -> { drawGame(g2); drawGameOver(g2); }
        }
        g2.dispose();
    }

    private void drawWarehouse(Graphics2D g) {
        GradientPaint bg = new GradientPaint(0, 0, new Color(19, 24, 52), 0, H, new Color(10, 12, 28));
        g.setPaint(bg);
        g.fillRect(-20, -20, W + 40, H + 40);
        g.setColor(new Color(255, 255, 255, 12));
        for (int i = 0; i < 5; i++) {
            int y = 60 + i * 130;
            g.fillRect(0, y, W, 2);
        }
        g.setColor(new Color(255, 176, 32, 22));
        g.setFont(new Font("SansSerif", Font.BOLD, 88));
        g.drawString("PACKRUSH", 60, 130);
    }

    private void drawBelt(Graphics2D g, List<Parcel> list) {
        // zone washes
        g.setColor(new Color(61, 220, 255, 26));
        g.fillRect(PACK_X0, BELT_Y - 70, PACK_X1 - PACK_X0, 150);
        g.setColor(new Color(61, 255, 138, 22));
        g.fillRect(SORT_X0, BELT_Y - 70, SORT_X1 - SORT_X0, 150);
        // labels
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.setColor(new Color(61, 220, 255, 220));
        g.drawString("◉ PACK ZONE — SPACE / CLICK TO TAPE", PACK_X0 + 12, BELT_Y - 52);
        g.setColor(new Color(61, 255, 138, 220));
        g.drawString("▼ SORT ZONE — A S D / DRAG TO TRUCK ▼", SORT_X0 + 12, BELT_Y - 52);
        // belt body
        g.setColor(new Color(24, 30, 58));
        roundFill(g, 20, BELT_Y - 45, W - 40, 95, 18);
        g.setColor(new Color(48, 58, 105));
        g.fillRect(30, BELT_Y - 12, W - 60, 26);
        g.setColor(new Color(255, 176, 32, 160));
        double off = (animT * beltSpeed()) % 46;
        for (double x = 30 - off; x < W - 20; x += 46) {
            g.fillRect((int) x, BELT_Y - 12, 22, 26);
        }
        g.setColor(new Color(10, 13, 30));
        g.fillRect(20, BELT_Y - 45, 14, 95);
        // end chute
        g.setColor(new Color(255, 77, 94, 200));
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString("MISS ↓", MISS_X - 30, BELT_Y + 70);
        g.setColor(new Color(255, 77, 94, 90));
        g.fillRect(MISS_X - 40, BELT_Y + 40, 90, 8);
        for (Parcel p : list) drawParcel(g, p);
    }

    private void drawParcel(Graphics2D g, Parcel p) {
        int s = p.boxSize();
        int bx = (int) (p.x - s / 2), by = (int) (p.y - s / 2 + Math.sin(p.bobPhase) * 3);
        if (p.dragging) { bx = mx - s / 2; by = my - s / 2 - 10; }
        double wob = p.wobble * Math.sin(animT * 30) * 4;
        g.translate(wob, 0);
        // shadow
        g.setColor(new Color(0, 0, 0, 90));
        g.fillOval(bx + 6, by + s - 2, s - 12, 12);
        // golden glow
        if (p.golden) {
            g.setColor(new Color(255, 215, 80, 60 + (int) (30 * Math.sin(animT * 6))));
            roundFill(g, bx - 8, by - 8, s + 16, s + 16, 14);
        }
        // box
        Color base = p.golden ? new Color(255, 200, 60) : new Color(196, 148, 92);
        Color dark = p.golden ? new Color(200, 140, 20) : new Color(150, 108, 62);
        g.setColor(dark);
        roundFill(g, bx + 3, by + 4, s, s, 10);
        g.setColor(base);
        roundFill(g, bx, by, s, s, 10);
        // color tag
        g.setColor(Parcel.COLORS[p.color]);
        g.fillRect(bx + 6, by + 6, s - 12, 12);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 11));
        centerStr(g, Parcel.NAMES[p.color], bx + s / 2, by + 16);
        // flaps / tape
        if (p.isTaped()) {
            g.setColor(new Color(255, 255, 255, 210));
            g.fillRect(bx + s / 2 - 7, by, 14, s);
            g.setColor(new Color(61, 220, 255, 220));
            g.fillRect(bx + s / 2 - 7, by + s / 2 - 9, 14, 18);
            g.setColor(new Color(61, 255, 138));
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.drawString("✓", bx + s - 20, by + 24);
        } else {
            g.setColor(new Color(90, 60, 30));
            int flap = 10 + (p.tapsLeft > 1 ? 6 : 0);
            g.fillRect(bx + 4, by - 2, s / 2 - 6, 8);
            g.fillRect(bx + s / 2 + 2, by - 2, s / 2 - 6, 8);
            g.setColor(new Color(255, 77, 94));
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            centerStr(g, "TAPE:" + p.tapsLeft, bx + s / 2, by + s - 8);
            g.translate(-wob, 0);
            return;
        }
        // fragile
        if (p.fragile) {
            g.setColor(new Color(20, 20, 40, 220));
            roundFill(g, bx + s / 2 - 34, by + s - 24, 68, 18, 9);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            centerStr(g, "⚠ FRAGILE", bx + s / 2, by + s - 11);
        }
        if (p.golden) {
            g.setColor(new Color(90, 60, 0));
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            centerStr(g, "★ GOLD ★", bx + s / 2, by + s - 10);
        }
        g.translate(-wob, 0);
    }

    private void drawBins(Graphics2D g) {
        int[][] r = binRects();
        String[] keys = {"A / 1", "S / 2", "D / 3"};
        for (int i = 0; i < 3; i++) {
            double b = truckBounce[i];
            int dy = (int) (-8 * b);
            int x = r[i][0], y = r[i][1] + dy, w = r[i][2], h = r[i][3];
            boolean hov = dragParcel != null && binAt(mx, my) == i;
            Color c = Parcel.COLORS[i];
            if (hov) { g.setColor(new Color(255, 255, 255, 50)); roundFill(g, x - 6, y - 6, w + 12, h + 12, 20); }
            g.setColor(new Color(16, 21, 45));
            roundFill(g, x, y, w, h, 18);
            g.setColor(c);
            g.setStroke(new BasicStroke(hov ? 4 : 3));
            roundDraw(g, x, y, w, h, 18);
            // truck cab look
            g.setColor(c.darker());
            roundFill(g, x + 18, y + 18, w - 36, 56, 10);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 30));
            centerStr(g, Parcel.NAMES[i] + " TRUCK", x + w / 2, y + 54);
            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            g.setColor(new Color(255, 255, 255, 200));
            centerStr(g, "press  " + keys[i] + "   or   drag parcel here", x + w / 2, y + 102);
            // wheels
            g.setColor(new Color(8, 10, 24));
            g.fillOval(x + 40, y + h - 30, 44, 44);
            g.fillOval(x + w - 84, y + h - 30, 44, 44);
            g.setColor(new Color(120, 130, 170));
            g.fillOval(x + 52, y + h - 18, 20, 20);
            g.fillOval(x + w - 72, y + h - 18, 20, 20);
        }
    }

    // ----- screens -----
    private void drawTitle(Graphics2D g) {
        drawBelt(g, demo);
        dim(g, 0.45);
        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        g.setColor(new Color(61, 220, 255));
        centerStr(g, "★  SORT • PACK • SHIP  ★", W / 2, 190);
        // logo
        g.setFont(new Font("SansSerif", Font.BOLD, 130));
        FontMetrics fm = g.getFontMetrics();
        String logo = "PACKRUSH";
        int lw = fm.stringWidth(logo);
        g.setColor(new Color(0, 0, 0, 120));
        g.drawString(logo, W / 2 - lw / 2 + 6, 306);
        GradientPaint gp = new GradientPaint(0, 210, new Color(255, 200, 60), 0, 310, new Color(255, 110, 40));
        g.setPaint(gp);
        g.drawString(logo, W / 2 - lw / 2, 300);
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.setColor(Color.WHITE);
        centerStr(g, "THE SORTING PARCEL PACKAGING GAME", W / 2, 348);
        // best pill
        pill(g, W / 2 - 130, 372, 260, 40, "BEST SCORE: " + best);
        // blinking prompt
        if ((animT * 2) % 2 < 1.4) {
            g.setFont(new Font("SansSerif", Font.BOLD, 28));
            g.setColor(new Color(61, 255, 138));
            centerStr(g, "PRESS ENTER OR CLICK TO START", W / 2, 470);
        }
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g.setColor(new Color(170, 180, 210));
        centerStr(g, "SPACE tape  •  A S D sort  •  drag & drop works  •  M mute", W / 2, 510);
        centerStr(g, "Desktop Java Edition — 1280 × 720", W / 2, 536);
        drawFooter(g);
    }

    private void drawMenu(Graphics2D g) {
        drawBelt(g, demo);
        dim(g, 0.55);
        logoSmall(g, 120);
        pill(g, W / 2 - 130, 190, 260, 36, "BEST: " + best + "   •   " + diffName());
        for (Btn b : menuButtons()) drawBtn(g, b);
        drawFooter(g);
    }

    private void drawHowto(Graphics2D g) {
        dim(g, 0.0);
        logoSmall(g, 84);
        panel(g, 140, 150, 1000, 440);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        g.drawString("HOW TO PLAY", 180, 200);
        String[][] steps = {
            {"1. TAPE IT", "Press SPACE or CLICK a parcel to tape it. Fragile = tape TWICE."},
            {"2. SORT IT", "In the SORT ZONE press  A / S / D  (or 1/2/3) for RED / BLUE / YELLOW."},
            {"3. OR DRAG IT", "Grab any parcel with the mouse and drop it on the right truck."},
            {"4. DON'T BLOW IT", "Wrong truck or parcel falling off = 1 strike. 3 strikes = shift failed."},
            {"★ BONUS", "Golden parcels = +500. Taped + correct = combo bonus. Un-taped = only +30."},
        };
        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        int y = 244;
        for (String[] s : steps) {
            g.setFont(new Font("SansSerif", Font.BOLD, 19));
            g.setColor(new Color(255, 176, 32));
            g.drawString(s[0], 180, y);
            g.setFont(new Font("SansSerif", Font.PLAIN, 18));
            g.setColor(new Color(225, 230, 250));
            drawWrapped(g, s[1], 180, y + 24, 920, 22);
            y += 68;
        }
        for (Btn b : howtoButtons()) drawBtn(g, b);
    }

    private void drawSettings(Graphics2D g) {
        dim(g, 0.0);
        logoSmall(g, 84);
        panel(g, 340, 150, 600, 430);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        g.drawString("SETTINGS", 380, 205);
        g.setFont(new Font("SansSerif", Font.BOLD, 17));
        g.setColor(new Color(170, 180, 210));
        g.drawString("DIFFICULTY", 380, 255);
        for (Btn b : settingsButtons()) drawBtn(g, b);
        String[] names = {"CHILL", "STANDARD", "RUSH"};
        String[] descs = {"slow + 120s", "normal + 100s", "fast + 80s"};
        for (int i = 0; i < 3; i++) {
            if (difficulty == i) {
                g.setColor(new Color(61, 255, 138));
                g.setFont(new Font("SansSerif", Font.BOLD, 14));
                g.drawString("● " + descs[i], 400 + i * 175, 350);
            } else {
                g.setColor(new Color(140, 150, 180));
                g.setFont(new Font("SansSerif", Font.PLAIN, 14));
                g.drawString(descs[i], 400 + i * 175, 350);
            }
            names[i] = names[i] + "";
        }
    }

    private void drawGame(Graphics2D g) {
        drawBelt(g, parcels);
        drawBins(g);
        // HUD top
        panel(g, 20, 12, W - 40, 64);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        g.drawString("SCORE " + score, 44, 52);
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        g.setColor(new Color(170, 180, 210));
        g.drawString("BEST " + Math.max(best, score), 44, 68 - 12 + 24);
        // time bar
        int bx = 330, bw = 420;
        g.setColor(new Color(255, 255, 255, 60));
        roundFill(g, bx, 28, bw, 16, 8);
        double frac = Math.max(0, timeLeft / timeTotal);
        Color tc = timeLeft < 15 ? new Color(255, 77, 94) : new Color(61, 220, 255);
        g.setColor(tc);
        roundFill(g, bx, 28, (int) (bw * frac), 16, 8);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        centerStr(g, fmtTime(timeLeft), bx + bw / 2, 62);
        // lives
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        String hearts = "♥".repeat(Math.max(0, lives)) + "♡".repeat(Math.max(0, 3 - lives));
        g.setColor(new Color(255, 77, 94));
        g.drawString(hearts, 790, 54);
        // combo + wave
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        if (combo > 1) {
            g.setColor(new Color(255, 176, 32));
            g.drawString("COMBO x" + combo, 950, 52);
        } else {
            g.setColor(new Color(140, 150, 180));
            g.drawString("WAVE " + wave, 950, 52);
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.setColor(new Color(170, 180, 210));
        g.drawString("QUOTA 1200", 1130, 54);
        // banner
        if (bannerT > 0) {
            g.setFont(new Font("SansSerif", Font.BOLD, 34));
            int a = (int) (255 * Math.min(1, bannerT));
            g.setColor(new Color(255, 255, 255, a));
            centerStr(g, banner, W / 2, 160);
        }
        if (hintT > 0) {
            g.setFont(new Font("SansSerif", Font.BOLD, 18));
            int a = (int) (255 * Math.min(1, hintT));
            g.setColor(new Color(255, 207, 46, a));
            centerStr(g, hintMsg, W / 2, 470);
        }
        drawParticles(g);
        // drag ghost on top
        if (dragParcel != null) {
            drawParcel(g, dragParcel);
            int b = binAt(mx, my);
            if (b >= 0) {
                g.setColor(new Color(255, 255, 255, 180));
                g.setFont(new Font("SansSerif", Font.BOLD, 16));
                centerStr(g, "DROP TO " + Parcel.NAMES[b], mx, my - 50);
            }
        }
    }

    private void drawPause(Graphics2D g) {
        dim(g, 0.6);
        panel(g, W / 2 - 260, 170, 520, 360);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 44));
        centerStr(g, "PAUSED", W / 2, 240);
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g.setColor(new Color(170, 180, 210));
        centerStr(g, "Take a breath. The belt is waiting.", W / 2, 272);
        for (Btn b : pauseButtons()) drawBtn(g, b);
    }

    private void drawGameOver(Graphics2D g) {
        dim(g, 0.62);
        panel(g, W / 2 - 330, 90, 660, 540);
        g.setFont(new Font("SansSerif", Font.BOLD, 52));
        g.setColor(won ? new Color(61, 255, 138) : new Color(255, 77, 94));
        centerStr(g, won ? "SHIFT COMPLETE!" : "SHIFT FAILED", W / 2, 165);
        // rank badge
        g.setColor(new Color(255, 176, 32));
        g.fillOval(W / 2 - 40, 185, 80, 80);
        g.setColor(new Color(20, 16, 8));
        g.setFont(new Font("SansSerif", Font.BOLD, 48));
        centerStr(g, rank, W / 2, 240);
        if (newBest) {
            g.setFont(new Font("SansSerif", Font.BOLD, 18));
            g.setColor(new Color(255, 215, 80));
            centerStr(g, "★ NEW BEST! ★", W / 2, 290);
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        g.setColor(Color.WHITE);
        centerStr(g, "SCORE  " + score + "      BEST  " + best, W / 2, 325);
        // stats grid
        g.setFont(new Font("SansSerif", Font.PLAIN, 17));
        g.setColor(new Color(200, 208, 235));
        int total = sorted + wrongCount + missed + poorCount;
        double acc = total == 0 ? 0 : 100.0 * sorted / total;
        centerStr(g, String.format("sorted %d   •   wrong %d   •   poor-pack %d   •   missed %d", sorted, wrongCount, poorCount, missed), W / 2, 360);
        centerStr(g, String.format("accuracy %.0f%%   •   max combo x%d   •   taped %d", acc, maxCombo, tapedCount), W / 2, 388);
        for (Btn b : overButtons()) drawBtn(g, b);
    }

    private void drawParticles(Graphics2D g) {
        for (Particle p : particles) {
            float a = (float) Math.max(0, p.life / p.maxLife);
            g.setColor(new Color(p.c.getRed(), p.c.getGreen(), p.c.getBlue(), (int) (a * 255)));
            g.fillOval((int) p.x, (int) p.y, p.s, p.s);
        }
        for (Floater f : floaters) {
            float a = (float) Math.min(1, f.life);
            g.setColor(new Color(f.c.getRed(), f.c.getGreen(), f.c.getBlue(), (int) (a * 255)));
            g.setFont(new Font("SansSerif", Font.BOLD, f.size));
            centerStr(g, f.text, (int) f.x, (int) f.y);
        }
    }

    // ----- widgets -----
    private void dim(Graphics2D g, double a) {
        g.setColor(new Color(5, 7, 18, (int) (a * 255)));
        g.fillRect(-20, -20, W + 40, H + 40);
    }
    private void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(10, 13, 30, 200));
        roundFill(g, x, y, w, h, 16);
        g.setColor(new Color(255, 255, 255, 30));
        g.setStroke(new BasicStroke(1.5f));
        roundDraw(g, x, y, w, h, 16);
    }
    private void pill(Graphics2D g, int x, int y, int w, int h, String t) {
        g.setColor(new Color(255, 255, 255, 18));
        roundFill(g, x, y, w, h, h / 2);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        centerStr(g, t, x + w / 2, y + h / 2 + 6);
    }
    private void logoSmall(Graphics2D g, int y) {
        g.setFont(new Font("SansSerif", Font.BOLD, 54));
        FontMetrics fm = g.getFontMetrics();
        String logo = "PACKRUSH";
        int lw = fm.stringWidth(logo);
        g.setColor(new Color(0, 0, 0, 120));
        g.drawString(logo, W / 2 - lw / 2 + 3, y + 3);
        g.setColor(new Color(255, 190, 50));
        g.drawString(logo, W / 2 - lw / 2, y);
    }
    private void roundFill(Graphics2D g, int x, int y, int w, int h, int r) {
        if (w <= 0 || h <= 0) return;
        g.fill(new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2));
    }
    private void roundDraw(Graphics2D g, int x, int y, int w, int h, int r) {
        g.draw(new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2));
    }
    private void centerStr(Graphics2D g, String t, int cx, int cy) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(t, cx - fm.stringWidth(t) / 2, cy);
    }
    private void drawWrapped(Graphics2D g, String t, int x, int y, int maxW, int lh) {
        FontMetrics fm = g.getFontMetrics();
        String[] words = t.split(" ");
        StringBuilder line = new StringBuilder();
        int cy = y;
        for (String w : words) {
            String test = line.length() == 0 ? w : line + " " + w;
            if (fm.stringWidth(test) > maxW && line.length() > 0) {
                g.drawString(line.toString(), x, cy);
                cy += lh;
                line = new StringBuilder(w);
            } else line = new StringBuilder(test);
        }
        g.drawString(line.toString(), x, cy);
    }

    private void drawBtn(Graphics2D g, Btn b) {
        boolean hov = b.hit(mx, my);
        boolean sel = b.id.startsWith("DIFF") && diffId() .equals(b.id);
        Color fill = sel ? new Color(61, 255, 138, 60)
            : hov ? new Color(255, 176, 32, 70) : new Color(255, 255, 255, 16);
        g.setColor(fill);
        roundFill(g, b.x, b.y, b.w, b.h, 12);
        g.setColor(hov || sel ? new Color(255, 176, 32) : new Color(255, 255, 255, 70));
        g.setStroke(new BasicStroke(2));
        roundDraw(g, b.x, b.y, b.w, b.h, 12);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        centerStr(g, b.label, b.x + b.w / 2, b.y + b.h / 2 + (b.sub == null ? 7 : 2));
        if (b.sub != null) {
            g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            g.setColor(new Color(170, 180, 210));
            centerStr(g, b.sub, b.x + b.w / 2, b.y + b.h / 2 + 22);
        }
    }

    private String diffName() {
        return difficulty == 0 ? "CHILL" : difficulty == 1 ? "STANDARD" : "RUSH";
    }
    private String diffId() { return "DIFF" + difficulty; }

    private List<Btn> menuButtons() {
        return List.of(
            new Btn("START", "▶  START SHIFT", W/2-220, 260, 440, 64, diffName() + "  •  best " + best),
            new Btn("HOWTO", "HOW TO PLAY", W/2-220, 336, 440, 56, "tape → sort → ship"),
            new Btn("SETTINGS", "SETTINGS", W/2-220, 404, 440, 56, (sound.isMuted() ? "muted" : "sound on") + "  •  " + diffName()),
            new Btn("TITLE", "← BACK TO TITLE", W/2-220, 472, 440, 48, null)
        );
    }
    private List<Btn> howtoButtons() {
        return List.of(new Btn("BACK", "← BACK", W/2-110, 606, 220, 52, null));
    }
    private List<Btn> settingsButtons() {
        return List.of(
            new Btn("DIFF0", "CHILL", 380, 270, 160, 60, null),
            new Btn("DIFF1", "STANDARD", 555, 270, 160, 60, null),
            new Btn("DIFF2", "RUSH", 730, 270, 160, 60, null),
            new Btn("MUTE", sound.isMuted() ? "SOUND: OFF" : "SOUND: ON", 380, 380, 250, 54, "M to toggle"),
            new Btn("SHAKE", shakeOn ? "SHAKE: ON" : "SHAKE: OFF", 640, 380, 250, 54, null),
            new Btn("BACK", "← BACK", 380, 450, 510, 52, null)
        );
    }
    private List<Btn> pauseButtons() {
        return List.of(
            new Btn("RESUME", "▶  RESUME  (P)", W/2-200, 300, 400, 56, null),
            new Btn("RESTART", "↻  RESTART SHIFT  (R)", W/2-200, 366, 400, 52, null),
            new Btn("QUIT", "QUIT TO MENU  (Q)", W/2-200, 428, 400, 52, null)
        );
    }
    private List<Btn> overButtons() {
        return List.of(
            new Btn("RETRY", "↻  RETRY  (R)", W/2-260, 420, 250, 60, "100s shift"),
            new Btn("MENU", "MENU  (Q)", W/2+10, 420, 250, 60, null)
        );
    }
    private List<Btn> currentButtons() {
        return switch (screen) {
            case MENU -> menuButtons();
            case HOWTO -> howtoButtons();
            case SETTINGS -> settingsButtons();
            case PAUSE -> pauseButtons();
            case GAMEOVER -> overButtons();
            default -> List.of();
        };
    }

    private void drawFooter(Graphics2D g) {
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(new Color(140, 150, 180));
        centerStr(g, "Java Swing desktop build  •  ENTER start   H help   M mute   ESC back", W / 2, H - 18);
    }

    private String fmtTime(double t) {
        int s = (int) Math.max(0, Math.ceil(t));
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private void pressButton(Btn b) {
        sound.click();
        switch (b.id) {
            case "START" -> startGame();
            case "HOWTO" -> screen = Screen.HOWTO;
            case "SETTINGS" -> screen = Screen.SETTINGS;
            case "TITLE" -> screen = Screen.TITLE;
            case "BACK" -> screen = (screen == Screen.SETTINGS || screen == Screen.HOWTO) ? Screen.MENU : screen;
            case "DIFF0" -> { difficulty = 0; save(); }
            case "DIFF1" -> { difficulty = 1; save(); }
            case "DIFF2" -> { difficulty = 2; save(); }
            case "MUTE" -> { sound.toggle(); save(); }
            case "SHAKE" -> shakeOn = !shakeOn;
            case "RESUME" -> screen = Screen.GAME;
            case "RESTART" -> startGame();
            case "QUIT" -> screen = Screen.MENU;
            case "RETRY" -> startGame();
            case "MENU" -> screen = Screen.MENU;
        }
    }

    // ================= INPUT =================
    @Override public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_M) { sound.toggle(); save(); return; }
        switch (screen) {
            case TITLE -> {
                if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) { sound.click(); screen = Screen.MENU; }
            }
            case MENU -> {
                if (k == KeyEvent.VK_ENTER) startGame();
                else if (k == KeyEvent.VK_H) screen = Screen.HOWTO;
                else if (k == KeyEvent.VK_ESCAPE) screen = Screen.TITLE;
            }
            case HOWTO, SETTINGS -> {
                if (k == KeyEvent.VK_ESCAPE || k == KeyEvent.VK_Q) { sound.click(); screen = Screen.MENU; }
            }
            case GAME -> {
                if (k == KeyEvent.VK_SPACE) tapeFront();
                else if (k == KeyEvent.VK_A || k == KeyEvent.VK_1) sortKey(0);
                else if (k == KeyEvent.VK_S || k == KeyEvent.VK_2) sortKey(1);
                else if (k == KeyEvent.VK_D || k == KeyEvent.VK_3) sortKey(2);
                else if (k == KeyEvent.VK_P || k == KeyEvent.VK_ESCAPE) screen = Screen.PAUSE;
            }
            case PAUSE -> {
                if (k == KeyEvent.VK_P || k == KeyEvent.VK_ESCAPE) screen = Screen.GAME;
                else if (k == KeyEvent.VK_R) startGame();
                else if (k == KeyEvent.VK_Q) screen = Screen.MENU;
            }
            case GAMEOVER -> {
                if (k == KeyEvent.VK_R || k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) startGame();
                else if (k == KeyEvent.VK_Q || k == KeyEvent.VK_ESCAPE) screen = Screen.MENU;
            }
        }
    }
    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}

    private Parcel parcelAt(int px, int py) {
        for (int i = parcels.size() - 1; i >= 0; i--) {
            Parcel p = parcels.get(i);
            int s = p.boxSize();
            if (px >= p.x - s/2 - 6 && px <= p.x + s/2 + 6 && py >= p.y - s/2 - 10 && py <= p.y + s/2 + 10) return p;
        }
        return null;
    }

    @Override public void mousePressed(MouseEvent e) {
        requestFocusInWindow();
        mouseDown = true;
        pressX = e.getX(); pressY = e.getY();
        draggedFar = false;
        if (screen == Screen.GAME) {
            Parcel p = parcelAt(pressX, pressY);
            if (p != null) { pressOnParcel = true; dragParcel = p; p.dragging = true; }
            else pressOnParcel = false;
        }
    }

    @Override public void mouseDragged(MouseEvent e) {
        mx = e.getX(); my = e.getY();
        if (mouseDown && Math.hypot(mx - pressX, my - pressY) > 8) draggedFar = true;
    }

    @Override public void mouseMoved(MouseEvent e) { mx = e.getX(); my = e.getY(); }

    @Override public void mouseReleased(MouseEvent e) {
        mouseDown = false;
        mx = e.getX(); my = e.getY();
        // buttons first (except game screen has none)
        for (Btn b : currentButtons()) {
            if (b.hit(mx, my)) { pressButton(b); clearDrag(); return; }
        }
        if (screen == Screen.TITLE) { sound.click(); screen = Screen.MENU; clearDrag(); return; }
        if (screen == Screen.GAME && dragParcel != null) {
            int bin = binAt(mx, my);
            Parcel p = dragParcel;
            p.dragging = false;
            dragParcel = null;
            if (bin >= 0) resolveSort(p, bin);
            else if (!draggedFar) tapeParcel(p); // simple click = tape
            // else: dropped nowhere -> stays on belt
            return;
        }
        clearDrag();
    }

    private void clearDrag() {
        if (dragParcel != null) dragParcel.dragging = false;
        dragParcel = null;
        pressOnParcel = false;
    }

    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}
}
