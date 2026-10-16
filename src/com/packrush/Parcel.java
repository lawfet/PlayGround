package com.packrush;

/** One parcel riding the conveyor belt. */
public class Parcel {
    public static final String[] NAMES = {"RED", "BLU", "YEL"};
    public static final java.awt.Color[] COLORS = {
        new java.awt.Color(255, 77, 94),
        new java.awt.Color(61, 169, 255),
        new java.awt.Color(255, 207, 46)
    };

    public int color;          // 0=red, 1=blue, 2=yellow
    public double x;           // center x on belt
    public double y;           // center y (belt y + bob)
    public int size;           // 0=small,1=med,2=large -> box px
    public int tapsNeeded;     // 1 normal, 2 fragile
    public int tapsLeft;
    public boolean fragile;
    public boolean golden;
    public boolean dragging;
    public double wobble;
    public double bobPhase;

    public Parcel(int color, double x, double y, int size, boolean fragile, boolean golden) {
        this.color = color;
        this.x = x;
        this.y = y;
        this.size = size;
        this.fragile = fragile;
        this.golden = golden;
        this.tapsNeeded = golden ? 0 : (fragile ? 2 : 1);
        this.tapsLeft = tapsNeeded;
        this.bobPhase = Math.random() * Math.PI * 2;
    }

    public boolean isTaped() {
        return tapsLeft <= 0;
    }

    public int boxSize() {
        if (golden) return 64;
        return size == 0 ? 52 : (size == 1 ? 62 : 74);
    }
}
