package com.facetrack.client;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

final class VideoPanel extends JPanel {
    private BufferedImage image;

    VideoPanel() {
        setBackground(new Color(18, 21, 26));
        setPreferredSize(new Dimension(960, 540));
        setMinimumSize(new Dimension(480, 270));
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
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            if (image == null) {
                paintPlaceholder(g);
                return;
            }

            double scale = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int x = (getWidth() - width) / 2;
            int y = (getHeight() - height) / 2;

            g.drawImage(image, x, y, width, height, null);
        } finally {
            g.dispose();
        }
    }

    private void paintPlaceholder(Graphics2D g) {
        String text = "Camera idle";
        g.setColor(new Color(133, 145, 160));
        g.setFont(getFont().deriveFont(Font.BOLD, 18f));
        FontMetrics metrics = g.getFontMetrics();
        int x = (getWidth() - metrics.stringWidth(text)) / 2;
        int y = (getHeight() + metrics.getAscent()) / 2;
        g.drawString(text, x, y);
    }
}
