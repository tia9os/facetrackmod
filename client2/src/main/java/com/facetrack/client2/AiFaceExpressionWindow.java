package com.facetrack.client2;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.opencv_core.Mat;

final class AiFaceExpressionWindow extends JFrame {
    private static final int AUTO_SAMPLE_COUNT = 20;

    private final VideoPanel videoPanel = new VideoPanel();
    private final JSpinner cameraSpinner = new JSpinner(new SpinnerNumberModel(Integer.getInteger("facetrack.camera", 0).intValue(), 0, 16, 1));
    private final JComboBox<ExpressionLabel> expressionSelector = new JComboBox<>(ExpressionLabel.TRAINABLE);
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final JButton calibrateButton = new JButton("Calibrate sample");
    private final JButton autoButton = new JButton("Auto 20");
    private final JButton resetButton = new JButton("Reset AI model");
    private final JLabel statusLabel = valueLabel("Idle");
    private final JLabel expressionLabel = valueLabel("-");
    private final JLabel confidenceLabel = valueLabel("-");
    private final JLabel fpsLabel = valueLabel("-");
    private final JLabel bridgeLabel = valueLabel("-");
    private final JLabel modelLabel = valueLabel("-");
    private final JLabel featuresLabel = valueLabel("-");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger analysisInFlight = new AtomicInteger();
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor(daemonThreadFactory("facetrack-client2-model"));
    private final MinecraftExpressionBridge minecraftBridge = new MinecraftExpressionBridge();
    private final Object autoCalibrationLock = new Object();
    private final List<ExpressionFeatures> autoCalibrationSamples = new ArrayList<>();

    private volatile NeuralExpressionModel model;
    private volatile AiExpressionTracker tracker;
    private volatile ExecutorService analysisExecutor;
    private volatile Thread captureThread;
    private volatile ExpressionEstimate latestEstimate;
    private volatile ExpressionLabel autoCalibrationLabel;

    AiFaceExpressionWindow() {
        super("FaceTrack Client 2");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(980, 620));
        setLayout(new BorderLayout(10, 10));
        getContentPane().setBackground(new Color(24, 26, 30));

        add(buildToolbar(), BorderLayout.NORTH);
        add(videoPanel, BorderLayout.CENTER);
        add(buildStatusPanel(), BorderLayout.EAST);

        startButton.addActionListener(event -> startCamera());
        stopButton.addActionListener(event -> stopCamera());
        calibrateButton.addActionListener(event -> calibrateSample());
        autoButton.addActionListener(event -> startAutoCalibration());
        resetButton.addActionListener(event -> resetModel());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeWindow();
            }
        });

        bridgeLabel.setText(minecraftBridge.status());
        updateControls();
        pack();
        setLocationRelativeTo(null);
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        toolbar.setBackground(new Color(32, 35, 40));
        toolbar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        toolbar.add(new JLabel("Camera"));
        toolbar.add(cameraSpinner);
        toolbar.add(startButton);
        toolbar.add(stopButton);
        toolbar.add(new JLabel("Expression"));
        toolbar.add(expressionSelector);
        toolbar.add(calibrateButton);
        toolbar.add(autoButton);
        toolbar.add(resetButton);
        return toolbar;
    }

    private JScrollPane buildStatusPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(new Color(32, 35, 40));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        addStatusRow(panel, 0, "Status", statusLabel);
        addStatusRow(panel, 1, "Expression", expressionLabel);
        addStatusRow(panel, 2, "Confidence", confidenceLabel);
        addStatusRow(panel, 3, "FPS", fpsLabel);
        addStatusRow(panel, 4, "Bridge", bridgeLabel);
        addStatusRow(panel, 5, "Model", modelLabel);
        addStatusRow(panel, 6, "Features", featuresLabel);

        JScrollPane scrollPane = new JScrollPane(panel);
        scrollPane.setPreferredSize(new Dimension(310, 0));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(panel.getBackground());
        return scrollPane;
    }

    private void addStatusRow(JPanel panel, int row, String name, JLabel value) {
        JLabel label = new JLabel(name);
        label.setForeground(new Color(190, 196, 205));
        label.setHorizontalAlignment(SwingConstants.LEFT);
        value.setVerticalAlignment(SwingConstants.TOP);

        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.NORTHWEST;
        labelConstraints.insets = new Insets(0, 0, 10, 12);
        panel.add(label, labelConstraints);

        GridBagConstraints valueConstraints = new GridBagConstraints();
        valueConstraints.gridx = 1;
        valueConstraints.gridy = row;
        valueConstraints.weightx = 1.0;
        valueConstraints.fill = GridBagConstraints.HORIZONTAL;
        valueConstraints.anchor = GridBagConstraints.NORTHWEST;
        valueConstraints.insets = new Insets(0, 0, 10, 0);
        panel.add(value, valueConstraints);
    }

    private void startCamera() {
        if (running.get()) {
            return;
        }

        int camera = (Integer) cameraSpinner.getValue();
        updateStatus("Loading AI model");
        updateControls();
        modelExecutor.submit(() -> {
            try {
                NeuralExpressionModel loadedModel = ensureModel();
                AiExpressionTracker preparedTracker = new AiExpressionTracker(loadedModel);
                SwingUtilities.invokeLater(() -> startCapture(camera, preparedTracker));
            } catch (RuntimeException exception) {
                SwingUtilities.invokeLater(() -> {
                    updateStatus("Model error");
                    updateControls();
                    showError("AI model error", exception);
                });
            }
        });
    }

    private void startCapture(int camera, AiExpressionTracker preparedTracker) {
        if (running.get()) {
            return;
        }

        tracker = preparedTracker;
        analysisExecutor = Executors.newSingleThreadExecutor(daemonThreadFactory("facetrack-client2-analysis"));
        running.set(true);
        captureThread = new Thread(() -> captureLoop(camera), "facetrack-client2-camera");
        captureThread.setDaemon(true);
        captureThread.start();
        updateStatus("Starting camera");
        updateModelLabel();
        updateControls();
    }

    private void stopCamera() {
        if (!running.getAndSet(false)) {
            return;
        }
        updateStatus("Stopping camera");
        updateControls();
        Thread thread = captureThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void calibrateSample() {
        ExpressionEstimate estimate = latestEstimate;
        if (estimate == null || estimate.features() == null) {
            updateStatus("No face sample available");
            return;
        }
        ExpressionLabel label = selectedExpression();
        trainAsync(label, List.of(estimate.features()), 45, "Trained " + label.displayName());
    }

    private void startAutoCalibration() {
        synchronized (autoCalibrationLock) {
            autoCalibrationSamples.clear();
            autoCalibrationLabel = selectedExpression();
        }
        updateStatus("Collecting " + autoCalibrationLabel.displayName() + " samples");
    }

    private void resetModel() {
        updateStatus("Resetting AI model");
        modelExecutor.submit(() -> {
            try {
                NeuralExpressionModel loadedModel = ensureModel();
                loadedModel.reset();
                tracker = new AiExpressionTracker(loadedModel);
                SwingUtilities.invokeLater(() -> {
                    updateStatus("AI model reset");
                    updateModelLabel();
                });
            } catch (RuntimeException exception) {
                SwingUtilities.invokeLater(() -> showError("Reset error", exception));
            }
        });
    }

    private void captureLoop(int camera) {
        OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
        int width = Integer.getInteger("facetrack.camera.width", 960);
        int height = Integer.getInteger("facetrack.camera.height", 540);

        try (OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(camera)) {
            grabber.setImageWidth(width);
            grabber.setImageHeight(height);
            grabber.start();

            long frames = 0;
            long started = System.nanoTime();
            double fps = 0.0;
            SwingUtilities.invokeLater(() -> updateStatus("Camera running"));

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Frame grabbed = grabber.grab();
                if (grabbed == null) {
                    continue;
                }

                Mat mat = converter.convert(grabbed);
                if (mat == null || mat.empty()) {
                    continue;
                }

                frames++;
                long now = System.nanoTime();
                double elapsed = (now - started) / 1_000_000_000.0;
                if (elapsed >= 0.5) {
                    fps = frames / elapsed;
                    frames = 0;
                    started = now;
                }

                submitAnalysisFrame(mat, fps);
            }
        } catch (Exception exception) {
            if (running.getAndSet(false)) {
                SwingUtilities.invokeLater(() -> {
                    updateStatus("Camera stopped");
                    updateControls();
                    showError("Camera error", exception);
                });
                return;
            }
        } finally {
            shutdownAnalysisExecutor();
        }

        SwingUtilities.invokeLater(() -> {
            updateStatus("Idle");
            updateControls();
        });
    }

    private void submitAnalysisFrame(Mat mat, double fps) {
        ExecutorService analyzer = analysisExecutor;
        AiExpressionTracker currentTracker = tracker;
        if (analyzer == null || analyzer.isShutdown() || currentTracker == null || !analysisInFlight.compareAndSet(0, 1)) {
            return;
        }

        Mat frame = mat.clone();
        try {
            analyzer.submit(() -> {
                try {
                    TrackingFrame tracked = currentTracker.track(frame, fps);
                    latestEstimate = tracked.estimate();
                    minecraftBridge.send(tracked.estimate());
                    collectAutoCalibration(tracked.estimate().features());
                    SwingUtilities.invokeLater(() -> showFrame(tracked));
                } catch (RuntimeException exception) {
                    running.set(false);
                    SwingUtilities.invokeLater(() -> {
                        updateStatus("Tracking stopped");
                        updateControls();
                        showError("Tracking error", exception);
                    });
                } finally {
                    frame.close();
                    analysisInFlight.set(0);
                }
            });
        } catch (RejectedExecutionException exception) {
            frame.close();
            analysisInFlight.set(0);
        }
    }

    private void collectAutoCalibration(ExpressionFeatures features) {
        if (features == null) {
            return;
        }

        ExpressionLabel readyLabel = null;
        List<ExpressionFeatures> readySamples = null;
        int remaining;
        synchronized (autoCalibrationLock) {
            if (autoCalibrationLabel == null) {
                return;
            }

            autoCalibrationSamples.add(features);
            remaining = AUTO_SAMPLE_COUNT - autoCalibrationSamples.size();
            if (remaining <= 0) {
                readyLabel = autoCalibrationLabel;
                readySamples = new ArrayList<>(autoCalibrationSamples);
                autoCalibrationSamples.clear();
                autoCalibrationLabel = null;
            }
        }

        if (readySamples == null) {
            int displayRemaining = Math.max(0, remaining);
            SwingUtilities.invokeLater(() -> updateStatus("Collecting samples: " + displayRemaining + " left"));
            return;
        }

        trainAsync(readyLabel, readySamples, 55, "Auto-trained " + readyLabel.displayName());
    }

    private void trainAsync(ExpressionLabel label, Collection<ExpressionFeatures> samples, int epochs, String doneStatus) {
        updateStatus("Training " + label.displayName());
        modelExecutor.submit(() -> {
            try {
                NeuralExpressionModel loadedModel = ensureModel();
                loadedModel.train(label, samples, epochs);
                SwingUtilities.invokeLater(() -> {
                    updateStatus(doneStatus);
                    updateModelLabel();
                });
            } catch (RuntimeException exception) {
                SwingUtilities.invokeLater(() -> showError("Training error", exception));
            }
        });
    }

    private synchronized NeuralExpressionModel ensureModel() {
        if (model == null) {
            model = NeuralExpressionModel.loadOrCreate();
        }
        return model;
    }

    private void showFrame(TrackingFrame tracked) {
        videoPanel.setImage(tracked.image());
        ExpressionEstimate estimate = tracked.estimate();
        expressionLabel.setText(estimate.expression().displayName());
        confidenceLabel.setText(Math.round(estimate.confidence() * 100.0) + "%");
        fpsLabel.setText(String.format(Locale.US, "%.1f", estimate.fps()));
        bridgeLabel.setText(minecraftBridge.status());
        if (estimate.features() == null) {
            featuresLabel.setText("-");
        } else {
            featuresLabel.setText("<html>" + estimate.features().compactText() + "</html>");
        }
    }

    private void shutdownAnalysisExecutor() {
        ExecutorService analyzer = analysisExecutor;
        analysisExecutor = null;
        analysisInFlight.set(0);
        if (analyzer != null) {
            analyzer.shutdownNow();
        }
    }

    private void closeWindow() {
        running.set(false);
        shutdownAnalysisExecutor();
        modelExecutor.shutdownNow();
        minecraftBridge.close();
        dispose();
    }

    private void updateControls() {
        boolean isRunning = running.get();
        startButton.setEnabled(!isRunning);
        stopButton.setEnabled(isRunning);
        cameraSpinner.setEnabled(!isRunning);
        calibrateButton.setEnabled(isRunning);
        autoButton.setEnabled(isRunning);
    }

    private void updateStatus(String status) {
        statusLabel.setText(status);
    }

    private void updateModelLabel() {
        NeuralExpressionModel loadedModel = model;
        if (loadedModel == null) {
            modelLabel.setText("-");
            return;
        }
        modelLabel.setText("<html>" + loadedModel.status() + "<br>" + loadedModel.modelPath() + "</html>");
    }

    private ExpressionLabel selectedExpression() {
        Object selected = expressionSelector.getSelectedItem();
        return selected instanceof ExpressionLabel label ? label : ExpressionLabel.NEUTRAL;
    }

    private void showError(String title, Exception exception) {
        JOptionPane.showMessageDialog(this, exception.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    private static JLabel valueLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(new Color(232, 235, 240));
        return label;
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
