package com.facetrack.client2;

import javax.swing.SwingUtilities;

public final class AiFaceExpressionClient {
    private AiFaceExpressionClient() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            AiFaceExpressionWindow window = new AiFaceExpressionWindow();
            window.setVisible(true);
        });
    }
}
