package com.packrush;

/** Entry point for PACKRUSH — sorting parcel packaging game (Java Swing, desktop). */
public class Main {
    public static void main(String[] args) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            GameFrame frame = new GameFrame();
            frame.setVisible(true);
        });
    }
}
