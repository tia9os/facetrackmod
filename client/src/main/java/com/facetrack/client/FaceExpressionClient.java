package com.facetrack.client;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class FaceExpressionClient {
    private FaceExpressionClient() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Keep the default look and feel if the system theme is unavailable.
            }

            FaceExpressionWindow window = new FaceExpressionWindow();
            window.setVisible(true);
        });
    }
}
