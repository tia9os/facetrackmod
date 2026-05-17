package com.facetrack.client2;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

final class VideoPanel extends JPanel {
    private volatile BufferedImage image;

    VideoPanel() {
        setBackground(new Color(18, 20, 24));
        setPreferredSize(new Dimension(960, 540));
    }

    void setImage(BufferedImage image) {
        this.image = image;
        repaint();
    }

    BufferedImage getImage() {
        return image;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            BufferedImage current = image;
            if (current == null) {
                g.setColor(new Color(220, 224, 230));
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 20f));
                g.drawString("Camera idle", 28, 42);
                return;
            }

            double scale = Math.min((double) getWidth() / current.getWidth(), (double) getHeight() / current.getHeight());
            int width = Math.max(1, (int) Math.round(current.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(current.getHeight() * scale));
            int x = (getWidth() - width) / 2;
            int y = (getHeight() - height) / 2;
            g.drawImage(current, x, y, width, height, null);
        } finally {
            g.dispose();
        }
    }
}
