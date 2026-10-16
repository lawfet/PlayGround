package com.packrush;

import javax.sound.sampled.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny procedural sound engine — no audio files needed. */
public class Sound {
    private boolean muted = false;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "packrush-sound");
        t.setDaemon(true);
        return t;
    });

    public void setMuted(boolean m) { this.muted = m; }
    public boolean isMuted() { return muted; }
    public void toggle() { muted = !muted; }

    public void click()  { tone(660, 0.06, 0.25); }
    public void tape()   { noiseBurst(0.09); tone(880, 0.05, 0.2); }
    public void good()   { tone(523, 0.08, 0.3); toneDelayed(659, 0.08, 0.3, 80); toneDelayed(784, 0.12, 0.3, 160); }
    public void poor()   { tone(392, 0.12, 0.25); }
    public void bad()    { tone(160, 0.25, 0.35); toneDelayed(110, 0.25, 0.35, 120); }
    public void spawn()  { tone(300, 0.04, 0.12); }
    public void count()  { tone(440, 0.09, 0.3); }
    public void go()     { tone(880, 0.2, 0.35); }
    public void fanfare(){ int[] n = {523,659,784,1046}; for (int i=0;i<n.length;i++) toneDelayed(n[i],0.14,0.3,i*130); }

    private void toneDelayed(int freq, double dur, double vol, int delayMs) {
        pool.submit(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
            playTone(freq, dur, vol);
        });
    }

    private void tone(int freq, double dur, double vol) {
        pool.submit(() -> playTone(freq, dur, vol));
    }

    private void playTone(int freq, double durSec, double vol) {
        if (muted) return;
        try {
            float rate = 22050f;
            int n = (int) (rate * durSec);
            byte[] buf = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                double t = i / rate;
                double env = 1.0 - (double) i / n;
                double s = Math.sin(2 * Math.PI * freq * t) * vol * env;
                short v = (short) (s * 32767);
                buf[i*2] = (byte)(v & 0xff);
                buf[i*2+1] = (byte)((v >> 8) & 0xff);
            }
            AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
            try (SourceDataLine line = AudioSystem.getSourceDataLine(fmt)) {
                line.open(fmt);
                line.start();
                line.write(buf, 0, buf.length);
                line.drain();
            }
        } catch (Exception ignored) {}
    }

    private void noiseBurst(double durSec) {
        pool.submit(() -> {
            if (muted) return;
            try {
                float rate = 22050f;
                int n = (int) (rate * durSec);
                byte[] buf = new byte[n * 2];
                java.util.Random r = new java.util.Random();
                for (int i = 0; i < n; i++) {
                    double env = 1.0 - (double) i / n;
                    short v = (short) ((r.nextDouble()*2-1) * 9000 * env);
                    buf[i*2] = (byte)(v & 0xff);
                    buf[i*2+1] = (byte)((v >> 8) & 0xff);
                }
                AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(fmt)) {
                    line.open(fmt);
                    line.start();
                    line.write(buf, 0, buf.length);
                    line.drain();
                }
            } catch (Exception ignored) {}
        });
    }
}
