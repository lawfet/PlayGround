package com.packrush;

import javax.swing.JFrame;
import java.awt.Dimension;

/** Main desktop window for PACKRUSH. Fixed 1280x720 playfield, keyboard + mouse. */
public class GameFrame extends JFrame {
    public GameFrame() {
        super("PACKRUSH — Sorting Parcel Packaging");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        PackRushGame panel = new PackRushGame();
        panel.setPreferredSize(new Dimension(1280, 720));
        add(panel);
        pack();
        setLocationRelativeTo(null);
    }
}
