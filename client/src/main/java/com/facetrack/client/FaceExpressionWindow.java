package com.facetrack.client;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.opencv.opencv_core.Mat;

final class FaceExpressionWindow extends JFrame {
    private final VideoPanel videoPanel = new VideoPanel();
    private final JLabel statusLabel = valueLabel("Idle");
    private final JLabel expressionLabel = valueLabel("No data");
    private final JLabel faceLabel = valueLabel("0");
    private final JLabel fpsLabel = valueLabel("0.0");
    private final JLabel calibrationLabel = valueLabel("Learning neutral baseline");
    private final JLabel profileLabel = valueLabel("No profile loaded");
    private final JLabel recordingLabel = valueLabel("Not recording");
    private final JLabel bridgeLabel = valueLabel("UDP 127.0.0.1:34321");
    private final JLabel microphoneLabel = valueLabel("Disabled");
    private final JLabel performanceLabel = valueLabel("Balanced");
    private final JProgressBar confidenceBar = new JProgressBar(0, 100);
    private final JButton startButton = new JButton("Start camera");
    private final JButton stopButton = new JButton("Stop");
    private final JComboBox<TrackerBackendMode> trackerBackend = new JComboBox<>(TrackerBackendMode.values());
    private final JComboBox<RecognitionQuality> qualityMode = new JComboBox<>(RecognitionQuality.values());
    private final JComboBox<HardwareAccelerationMode> accelerationMode = new JComboBox<>(HardwareAccelerationMode.values());
    private final JComboBox<String> calibrationExpression = new JComboBox<>(FaceExpressionTracker.calibratableExpressions());
    private final JButton calibrateButton = new JButton("Calibrate expression");
    private final JButton recordButton = new JButton("Start recording");
    private final JButton snapshotButton = new JButton("Snapshot");
    private final JTextField profileName = new JTextField("default", 10);
    private final JSpinner cameraIndex = new JSpinner(new SpinnerNumberModel(0, 0, 16, 1));
    private final JSpinner openCvThreads = new JSpinner(new SpinnerNumberModel(defaultOpenCvThreads(), 1, 32, 1));
    private final JComboBox<OffAxisMode> offAxisMode = new JComboBox<>(OffAxisMode.values());
    private final JSpinner offAxisHoldMillis = new JSpinner(new SpinnerNumberModel(700, 0, 5000, 50));
    private final JSpinner minFrontalQuality = new JSpinner(new SpinnerNumberModel(45, 0, 100, 5));
    private final JCheckBox microphoneSpeech = new JCheckBox("Mic talking");
    private final JComboBox<MicrophoneSpeechDetector.MicrophoneDevice> microphoneDevice = new JComboBox<>();
    private final JSlider microphoneSensitivity = new JSlider(0, 100, MicrophoneSpeechDetector.DEFAULT_SENSITIVITY_PERCENT);
    private final JSlider microphoneAmplification = new JSlider(
            MicrophoneSpeechDetector.MIN_AMPLIFICATION_PERCENT,
            MicrophoneSpeechDetector.MAX_AMPLIFICATION_PERCENT,
            MicrophoneSpeechDetector.DEFAULT_AMPLIFICATION_PERCENT
    );
    private final JProgressBar microphoneLevel = new JProgressBar(0, 100);
    private final JProgressBar microphoneNoise = new JProgressBar(0, 100);
    private final JProgressBar microphoneThreshold = new JProgressBar(0, 100);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicInteger analysisInFlight = new AtomicInteger();
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "face-expression-camera");
        thread.setDaemon(true);
        return thread;
    });
    private final SessionRecorder recorder;
    private final CalibrationProfileStore profileStore;
    private final ClientSettingsStore settingsStore;
    private final MinecraftExpressionBridge minecraftBridge = new MinecraftExpressionBridge();
    private final MicrophoneSpeechDetector microphone = new MicrophoneSpeechDetector();
    private volatile Future<?> cameraTask;
    private volatile ExecutorService analysisExecutor;
    private volatile ExpressionTrackerBackend tracker;
    private volatile TrackerBackendMode activeTrackerBackend = TrackerBackendMode.OPENCV;
    private volatile RecognitionQuality activeQuality = RecognitionQuality.BALANCED;
    private volatile OpenCvRuntime activeRuntime = new OpenCvRuntime("Balanced");
    private volatile CalibrationProfileStore.ProfileKey activeProfileKey;
    private volatile long savedCalibrationRevision;
    private volatile boolean settingsReady;
    private volatile boolean microphoneSpeechEnabled;
    private volatile String onnxModelPath = "";
    private volatile String landmarkModelPath = "";

    FaceExpressionWindow() {
        super("Face Expression Client");
        Path baseDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        recorder = new SessionRecorder(baseDirectory);
        profileStore = new CalibrationProfileStore(baseDirectory);
        settingsStore = new ClientSettingsStore(baseDirectory);
        bridgeLabel.setText(minecraftBridge.status());
        trackerBackend.setSelectedItem(TrackerBackendMode.OPENCV);
        qualityMode.setSelectedItem(RecognitionQuality.BALANCED);
        accelerationMode.setSelectedItem(HardwareAccelerationMode.AUTO);
        OffAxisStabilityOptions offAxisDefaults = OffAxisStabilityOptions.defaults();
        offAxisMode.setSelectedItem(offAxisDefaults.mode());
        offAxisHoldMillis.setValue(offAxisDefaults.holdMillis());
        minFrontalQuality.setValue((int) Math.round(offAxisDefaults.minFrontalQuality() * 100.0));
        performanceLabel.setText("OpenCV, Balanced, Auto");
        ClientSettingsStore.ClientSettings settings = loadClientSettings();
        applyModelPathProperties(settings);
        configureMicrophoneControls(settings.microphoneDeviceId());
        applyClientSettings(settings);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(760, 560));
        setLayout(new BorderLayout());

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildContent(), BorderLayout.CENTER);
        wireActions();
        wireSettingsPersistence();
        applyWindowSettings(settings);
        settingsReady = true;
        updateControls();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                shutdown();
            }
        });
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new WrappingFlowLayout(FlowLayout.LEFT, 8, 8));
        toolbar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        toolbar.add(labeledControl("Profile", profileName));
        toolbar.add(labeledControl("Camera", cameraIndex));
        toolbar.add(startButton);
        toolbar.add(stopButton);
        toolbar.add(labeledControl("Tracker", trackerBackend));
        toolbar.add(labeledControl("Quality", qualityMode));
        toolbar.add(labeledControl("Backend", accelerationMode));
        toolbar.add(labeledControl("Threads", openCvThreads));
        toolbar.add(labeledControl("Off-axis", offAxisMode));
        toolbar.add(labeledControl("Hold ms", offAxisHoldMillis));
        toolbar.add(labeledControl("Min Q", minFrontalQuality));
        toolbar.add(microphoneSpeech);
        toolbar.add(labeledControl("Mic", microphoneDevice));
        toolbar.add(labeledControl("Expression", calibrationExpression));
        toolbar.add(calibrateButton);
        toolbar.add(recordButton);
        toolbar.add(snapshotButton);
        return toolbar;
    }

    private static JPanel labeledControl(String labelText, Component control) {
        JPanel group = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        group.setOpaque(false);
        group.add(new JLabel(labelText));
        group.add(control);
        return group;
    }

    private JPanel buildMicrophonePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 8, 0);
        panel.add(microphoneLabel, constraints);

        constraints.gridwidth = 1;
        constraints.insets = new Insets(0, 0, 4, 8);
        addMicrophoneSlider(panel, constraints, "Sensitivity", microphoneSensitivity);
        addMicrophoneSlider(panel, constraints, "Amplify", microphoneAmplification);
        addMicrophoneGauge(panel, constraints, "Level", microphoneLevel);
        addMicrophoneGauge(panel, constraints, "Noise", microphoneNoise);
        addMicrophoneGauge(panel, constraints, "Gate", microphoneThreshold);
        return panel;
    }

    private static void addMicrophoneGauge(JPanel panel, GridBagConstraints constraints, String label, JProgressBar gauge) {
        addMicrophoneControl(panel, constraints, label, gauge);
    }

    private static void addMicrophoneSlider(JPanel panel, GridBagConstraints constraints, String label, JSlider slider) {
        addMicrophoneControl(panel, constraints, label, slider);
    }

    private static void addMicrophoneControl(JPanel panel, GridBagConstraints constraints, String label, Component control) {
        constraints.gridx = 0;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), constraints);

        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(control, constraints);
        constraints.gridy++;
    }

    private JSplitPane buildContent() {
        JPanel side = new JPanel(new GridBagLayout());
        side.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(210, 214, 220)),
                BorderFactory.createEmptyBorder(18, 18, 18, 18)
        ));
        side.setPreferredSize(new Dimension(300, 540));

        confidenceBar.setStringPainted(true);
        confidenceBar.setValue(0);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, 14, 0);

        addMetric(side, constraints, "Status", statusLabel);
        addMetric(side, constraints, "Expression", expressionLabel);
        addMetric(side, constraints, "Confidence", confidenceBar);
        addMetric(side, constraints, "Faces", faceLabel);
        addMetric(side, constraints, "FPS", fpsLabel);
        addMetric(side, constraints, "Calibration", calibrationLabel);
        addMetric(side, constraints, "Profile", profileLabel);
        addMetric(side, constraints, "Performance", performanceLabel);
        addMetric(side, constraints, "Microphone", buildMicrophonePanel());
        addMetric(side, constraints, "Minecraft", bridgeLabel);
        addMetric(side, constraints, "Recording", recordingLabel);

        constraints.weighty = 1;
        side.add(new JPanel(), constraints);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, videoPanel, side);
        splitPane.setResizeWeight(1.0);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        return splitPane;
    }

    private static void addMetric(JPanel panel, GridBagConstraints constraints, String title, java.awt.Component component) {
        JLabel label = new JLabel(title);
        label.setForeground(new Color(91, 100, 112));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        panel.add(label, constraints);

        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 22, 0);
        panel.add(component, constraints);

        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 14, 0);
    }

    private static JLabel valueLabel(String value) {
        JLabel label = new JLabel(value);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 18f));
        return label;
    }

    private void wireActions() {
        startButton.addActionListener(event -> startCamera());
        stopButton.addActionListener(event -> stopCamera());
        calibrateButton.addActionListener(event -> calibrateExpression());
        recordButton.addActionListener(event -> toggleRecording());
        snapshotButton.addActionListener(event -> saveSnapshot());
        microphoneSpeech.addActionListener(event -> {
            microphoneSpeechEnabled = microphoneSpeech.isSelected();
            updateMicrophoneCapture();
            saveClientSettingsQuietly();
        });
        microphoneDevice.addActionListener(event -> {
            if (!settingsReady) {
                return;
            }
            if (microphoneSpeechEnabled && running.get()) {
                microphone.stop();
                updateMicrophoneCapture();
            }
            saveClientSettingsQuietly();
        });
        microphoneSensitivity.addChangeListener(event -> {
            microphone.setSensitivityPercent(selectedMicrophoneSensitivity());
            if (settingsReady && !microphoneSensitivity.getValueIsAdjusting()) {
                saveClientSettingsQuietly();
            }
        });
        microphoneAmplification.addChangeListener(event -> {
            microphone.setAmplificationPercent(selectedMicrophoneAmplification());
            if (settingsReady && !microphoneAmplification.getValueIsAdjusting()) {
                saveClientSettingsQuietly();
            }
        });
    }

    private void wireSettingsPersistence() {
        profileName.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                saveClientSettingsQuietly();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                saveClientSettingsQuietly();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                saveClientSettingsQuietly();
            }
        });

        cameraIndex.addChangeListener(event -> saveClientSettingsQuietly());
        trackerBackend.addActionListener(event -> saveClientSettingsQuietly());
        qualityMode.addActionListener(event -> saveClientSettingsQuietly());
        accelerationMode.addActionListener(event -> saveClientSettingsQuietly());
        openCvThreads.addChangeListener(event -> saveClientSettingsQuietly());
        offAxisMode.addActionListener(event -> saveClientSettingsQuietly());
        offAxisHoldMillis.addChangeListener(event -> saveClientSettingsQuietly());
        minFrontalQuality.addChangeListener(event -> saveClientSettingsQuietly());
        calibrationExpression.addActionListener(event -> saveClientSettingsQuietly());
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent event) {
                saveClientSettingsQuietly();
            }

            @Override
            public void componentResized(ComponentEvent event) {
                saveClientSettingsQuietly();
            }
        });
    }

    private ClientSettingsStore.ClientSettings loadClientSettings() {
        try {
            return settingsStore.load();
        } catch (IOException exception) {
            statusLabel.setText("Settings load failed");
            return ClientSettingsStore.defaults();
        }
    }

    private void applyClientSettings(ClientSettingsStore.ClientSettings settings) {
        ClientSettingsStore.ClientSettings value = settings == null ? ClientSettingsStore.defaults() : settings;
        profileName.setText(value.profileName());
        cameraIndex.setValue(value.cameraIndex());
        trackerBackend.setSelectedItem(value.trackerBackend());
        qualityMode.setSelectedItem(value.quality());
        accelerationMode.setSelectedItem(value.accelerationMode());
        openCvThreads.setValue(value.openCvThreads());
        offAxisMode.setSelectedItem(value.offAxisMode());
        offAxisHoldMillis.setValue(value.offAxisHoldMillis());
        minFrontalQuality.setValue(value.minFrontalQualityPercent());
        microphoneSpeech.setSelected(value.microphoneSpeechEnabled());
        microphoneSpeechEnabled = value.microphoneSpeechEnabled();
        selectMicrophoneDevice(value.microphoneDeviceId());
        microphoneSensitivity.setValue(value.microphoneSensitivityPercent());
        microphone.setSensitivityPercent(value.microphoneSensitivityPercent());
        microphoneAmplification.setValue(value.microphoneAmplificationPercent());
        microphone.setAmplificationPercent(value.microphoneAmplificationPercent());
        selectCalibrationExpression(value.calibrationExpression());
        onnxModelPath = value.onnxModelPath();
        landmarkModelPath = value.landmarkModelPath();
        performanceLabel.setText(value.trackerBackend() + ", " + value.quality() + ", " + value.accelerationMode());
    }

    private static void applyModelPathProperties(ClientSettingsStore.ClientSettings settings) {
        ClientSettingsStore.ClientSettings value = settings == null ? ClientSettingsStore.defaults() : settings;
        applyModelPathProperty(ClientSettingsStore.ONNX_MODEL_PROPERTY, value.onnxModelPath());
        applyModelPathProperty(ClientSettingsStore.LANDMARK_MODEL_PROPERTY, value.landmarkModelPath());
    }

    private static void applyModelPathProperty(String property, String configuredPath) {
        if (System.getProperty(property) != null || configuredPath == null || configuredPath.isBlank()) {
            return;
        }
        System.setProperty(property, configuredPath.trim());
    }

    private void selectCalibrationExpression(String expression) {
        String fallback = "Neutral";
        String target = expression == null || expression.isBlank() ? fallback : expression.trim();
        for (int index = 0; index < calibrationExpression.getItemCount(); index++) {
            String item = calibrationExpression.getItemAt(index);
            if (item.equalsIgnoreCase(target)) {
                calibrationExpression.setSelectedIndex(index);
                return;
            }
        }
        calibrationExpression.setSelectedItem(fallback);
    }

    private void applyWindowSettings(ClientSettingsStore.ClientSettings settings) {
        ClientSettingsStore.ClientSettings value = settings == null ? ClientSettingsStore.defaults() : settings;
        setSize(value.windowWidth(), value.windowHeight());
        if (value.hasWindowLocation()) {
            setLocation(value.windowX(), value.windowY());
        } else {
            setLocationByPlatform(true);
        }
        if (value.windowMaximized()) {
            setExtendedState(getExtendedState() | MAXIMIZED_BOTH);
        }
    }

    private ClientSettingsStore.ClientSettings currentClientSettings() {
        boolean maximized = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        return new ClientSettingsStore.ClientSettings(
                profileName.getText(),
                (Integer) cameraIndex.getValue(),
                selectedTrackerBackend(),
                selectedQuality(),
                selectedAccelerationMode(),
                (Integer) openCvThreads.getValue(),
                selectedOffAxisMode(),
                (Integer) offAxisHoldMillis.getValue(),
                (Integer) minFrontalQuality.getValue(),
                microphoneSpeech.isSelected(),
                selectedMicrophoneDeviceId(),
                selectedMicrophoneSensitivity(),
                selectedMicrophoneAmplification(),
                (String) calibrationExpression.getSelectedItem(),
                onnxModelPath,
                landmarkModelPath,
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                maximized
        );
    }

    private void saveClientSettingsQuietly() {
        if (!settingsReady) {
            return;
        }

        try {
            settingsStore.save(currentClientSettings());
        } catch (IOException exception) {
            statusLabel.setText("Settings save failed");
        }
    }

    private void startCamera() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        int index = (Integer) cameraIndex.getValue();
        TrackerBackendMode backendMode = selectedTrackerBackend();
        RecognitionQuality quality = selectedQuality();
        HardwareAccelerationMode acceleration = selectedAccelerationMode();
        int threads = (Integer) openCvThreads.getValue();
        OffAxisStabilityOptions stabilityOptions = selectedOffAxisOptions();
        CalibrationProfileStore.ProfileKey profileKey = new CalibrationProfileStore.ProfileKey(profileName.getText(), index, quality);
        activeProfileKey = profileKey;
        activeTrackerBackend = backendMode;
        activeQuality = quality;
        activeRuntime = acceleration.apply(threads);
        tracker = createTracker(backendMode, quality, stabilityOptions);
        loadCalibrationProfile(tracker, profileKey);
        microphoneSpeechEnabled = microphoneSpeech.isSelected();
        updateMicrophoneCapture();
        analysisInFlight.set(0);
        analysisExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "face-expression-analysis");
            thread.setDaemon(true);
            return thread;
        });
        performanceLabel.setText(performanceStatus());
        updateStatus("Starting camera " + index);
        updateControls();
        cameraTask = cameraExecutor.submit(() -> captureLoop(index, quality));
    }

    private void stopCamera() {
        running.set(false);
        ExpressionTrackerBackend currentTracker = tracker;
        if (currentTracker != null) {
            saveCalibrationProfileIfNeeded(currentTracker);
        }
        stopRecording();
        microphone.stop();
        microphoneLabel.setText("Disabled");
        updateMicrophoneGauges(MicrophoneSpeechDetector.SpeechSample.disabled());
        updateStatus("Stopping");
        updateControls();
    }

    private void toggleRecording() {
        if (recording.get()) {
            stopRecording();
            updateControls();
            return;
        }

        try {
            Path file = recorder.start();
            recording.set(true);
            recordingLabel.setText(shortPath(file));
            recordButton.setText("Stop recording");
        } catch (IOException exception) {
            showError("Recording error", exception);
        }
        updateControls();
    }

    private void stopRecording() {
        if (!recording.getAndSet(false)) {
            return;
        }

        try {
            Path file = recorder.currentFile();
            recorder.close();
            recordingLabel.setText(file == null ? "Not recording" : "Saved " + file.getFileName());
        } catch (IOException exception) {
            showError("Recording error", exception);
        } finally {
            recordButton.setText("Start recording");
        }
    }

    private void saveSnapshot() {
        BufferedImage image = videoPanel.getImage();
        try {
            Path file = recorder.saveSnapshot(image);
            updateStatus("Saved " + shortPath(file));
        } catch (Exception exception) {
            showError("Snapshot error", exception);
        }
    }

    private void calibrateExpression() {
        ExpressionTrackerBackend currentTracker = tracker;
        if (currentTracker == null) {
            updateStatus("Start camera first");
            return;
        }

        String expression = (String) calibrationExpression.getSelectedItem();
        if (expression == null) {
            expression = "Neutral";
        }

        boolean started = currentTracker.requestCalibration(expression);
        calibrationLabel.setText(currentTracker.calibrationStatus());
        updateStatus(started ? "Hold " + expression.toLowerCase(Locale.ROOT) + " expression" : currentTracker.calibrationStatus());
    }

    private void captureLoop(int camera, RecognitionQuality quality) {
        OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
        ExecutorService analyzer = analysisExecutor;

        try (OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(camera)) {
            if (tracker == null) {
                tracker = createTracker(activeTrackerBackend, quality, selectedOffAxisOptions());
            }

            grabber.setImageWidth(quality.cameraWidth());
            grabber.setImageHeight(quality.cameraHeight());
            grabber.start();

            long frames = 0;
            long windowStarted = System.nanoTime();
            double fps = 0.0;
            SwingUtilities.invokeLater(() -> updateStatus("Camera running"));

            while (running.get()) {
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
                double elapsedSeconds = (now - windowStarted) / 1_000_000_000.0;
                if (elapsedSeconds >= 0.5) {
                    fps = frames / elapsedSeconds;
                    frames = 0;
                    windowStarted = now;
                }

                submitAnalysisFrame(analyzer, mat, fps);
            }
        } catch (Exception exception) {
            running.set(false);
            SwingUtilities.invokeLater(() -> {
                stopRecording();
                updateStatus("Camera stopped");
                updateControls();
                showError("Camera error", exception);
            });
            return;
        } finally {
            shutdownAnalysisExecutor();
        }

        SwingUtilities.invokeLater(() -> {
            updateStatus("Idle");
            updateControls();
        });
    }

    private void submitAnalysisFrame(ExecutorService analyzer, Mat mat, double fps) {
        if (analyzer == null || analyzer.isShutdown() || !analysisInFlight.compareAndSet(0, 1)) {
            return;
        }

        Mat frame = mat.clone();
        ExpressionTrackerBackend currentTracker = tracker;
        if (currentTracker == null) {
            frame.close();
            analysisInFlight.set(0);
            return;
        }
        try {
            analyzer.submit(() -> {
                try {
                    MicrophoneSpeechDetector.SpeechSample speech = microphoneSpeechEnabled
                            ? microphone.sample()
                            : MicrophoneSpeechDetector.SpeechSample.disabled();
                    TrackingFrame tracked = currentTracker.track(frame, fps, speech);
                    if (recording.get()) {
                        recorder.record(tracked.estimate());
                    }
                    minecraftBridge.send(tracked.estimate());
                    SwingUtilities.invokeLater(() -> showFrame(tracked));
                } catch (Exception exception) {
                    running.set(false);
                    SwingUtilities.invokeLater(() -> {
                        stopRecording();
                        updateStatus("Camera stopped");
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

    private void showFrame(TrackingFrame frame) {
        videoPanel.setImage(frame.image());

        ExpressionEstimate estimate = frame.estimate();
        expressionLabel.setText(estimate.expression());
        int confidence = (int) Math.round(estimate.confidence() * 100.0);
        confidenceBar.setValue(confidence);
        confidenceBar.setString(confidence + "%");
        faceLabel.setText(Integer.toString(estimate.faceCount()));
        fpsLabel.setText(String.format(Locale.US, "%.1f", estimate.fps()));
        ExpressionTrackerBackend currentTracker = tracker;
        if (currentTracker != null) {
            calibrationLabel.setText(currentTracker.calibrationStatus());
            saveCalibrationProfileIfNeeded(currentTracker);
        }
        performanceLabel.setText(performanceStatus());
        MicrophoneSpeechDetector.SpeechSample speech = microphoneSpeechEnabled
                ? microphone.sample()
                : MicrophoneSpeechDetector.SpeechSample.disabled();
        microphoneLabel.setText(speech.status());
        updateMicrophoneGauges(speech);
        bridgeLabel.setText(minecraftBridge.status());
        snapshotButton.setEnabled(true);
    }

    private void updateControls() {
        boolean active = running.get();
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);
        calibrateButton.setEnabled(active);
        calibrationExpression.setEnabled(active);
        profileName.setEnabled(!active);
        cameraIndex.setEnabled(!active);
        trackerBackend.setEnabled(!active);
        qualityMode.setEnabled(!active);
        accelerationMode.setEnabled(!active);
        openCvThreads.setEnabled(!active);
        offAxisMode.setEnabled(!active);
        offAxisHoldMillis.setEnabled(!active);
        minFrontalQuality.setEnabled(!active);
        microphoneSpeech.setEnabled(true);
        microphoneDevice.setEnabled(true);
        microphoneSensitivity.setEnabled(true);
        microphoneAmplification.setEnabled(true);
        recordButton.setEnabled(active);
        snapshotButton.setEnabled(videoPanel.getImage() != null);
    }

    private void updateStatus(String text) {
        statusLabel.setText(text);
    }

    private void updateMicrophoneCapture() {
        if (microphoneSpeechEnabled && running.get()) {
            microphone.start(selectedMicrophoneDeviceId(), selectedMicrophoneSensitivity(), selectedMicrophoneAmplification());
            microphoneLabel.setText(microphone.status());
            return;
        }

        microphone.stop();
        microphoneLabel.setText("Disabled");
        updateMicrophoneGauges(MicrophoneSpeechDetector.SpeechSample.disabled());
    }

    private void configureMicrophoneControls(String preferredDeviceId) {
        configureMicrophoneSensitivity();
        configureMicrophoneAmplification();
        configureMicrophoneGauge(microphoneLevel);
        configureMicrophoneGauge(microphoneNoise);
        configureMicrophoneGauge(microphoneThreshold);
        microphoneDevice.setMaximumRowCount(12);
        microphoneDevice.setPreferredSize(new Dimension(220, microphoneDevice.getPreferredSize().height));
        reloadMicrophoneDevices(preferredDeviceId);
    }

    private void configureMicrophoneSensitivity() {
        microphoneSensitivity.setMajorTickSpacing(50);
        microphoneSensitivity.setMinorTickSpacing(10);
        microphoneSensitivity.setPaintTicks(true);
        microphoneSensitivity.setPaintLabels(true);
        microphoneSensitivity.setToolTipText("Higher sensitivity aggressively lowers the speech gate; lower sensitivity ignores more background noise.");
    }

    private void configureMicrophoneAmplification() {
        microphoneAmplification.setMajorTickSpacing(100);
        microphoneAmplification.setMinorTickSpacing(50);
        microphoneAmplification.setPaintTicks(true);
        microphoneAmplification.setPaintLabels(true);
        microphoneAmplification.setToolTipText("Boosts the microphone level before speech detection. 100% is no boost.");
    }

    private static void configureMicrophoneGauge(JProgressBar gauge) {
        gauge.setStringPainted(true);
        gauge.setValue(0);
        gauge.setString("0%");
    }

    private void reloadMicrophoneDevices(String preferredDeviceId) {
        microphoneDevice.removeAllItems();
        for (MicrophoneSpeechDetector.MicrophoneDevice device : MicrophoneSpeechDetector.availableDevices()) {
            microphoneDevice.addItem(device);
        }
        selectMicrophoneDevice(preferredDeviceId);
    }

    private void selectMicrophoneDevice(String deviceId) {
        String target = deviceId == null || deviceId.isBlank()
                ? MicrophoneSpeechDetector.DEFAULT_DEVICE_ID
                : deviceId.trim();
        for (int index = 0; index < microphoneDevice.getItemCount(); index++) {
            MicrophoneSpeechDetector.MicrophoneDevice item = microphoneDevice.getItemAt(index);
            if (item.id().equals(target)) {
                microphoneDevice.setSelectedIndex(index);
                return;
            }
        }
        microphoneDevice.setSelectedItem(MicrophoneSpeechDetector.deviceForId(target));
    }

    private String selectedMicrophoneDeviceId() {
        Object selected = microphoneDevice.getSelectedItem();
        if (selected instanceof MicrophoneSpeechDetector.MicrophoneDevice device) {
            return device.id();
        }
        return MicrophoneSpeechDetector.DEFAULT_DEVICE_ID;
    }

    private int selectedMicrophoneSensitivity() {
        return microphoneSensitivity.getValue();
    }

    private int selectedMicrophoneAmplification() {
        return microphoneAmplification.getValue();
    }

    private void updateMicrophoneGauges(MicrophoneSpeechDetector.SpeechSample speech) {
        MicrophoneSpeechDetector.SpeechSample value = speech == null
                ? MicrophoneSpeechDetector.SpeechSample.disabled()
                : speech;
        setMicrophoneGauge(microphoneLevel, value.level());
        setMicrophoneGauge(microphoneNoise, value.noiseFloor());
        setMicrophoneGauge(microphoneThreshold, value.threshold());
    }

    private static void setMicrophoneGauge(JProgressBar gauge, double value) {
        int percent = audioPercent(value);
        gauge.setValue(percent);
        gauge.setString(percent + "%");
    }

    private static int audioPercent(double value) {
        return (int) Math.round(Math.max(0.0, Math.min(100.0, value * 500.0)));
    }

    private static ExpressionTrackerBackend createTracker(
            TrackerBackendMode backend,
            RecognitionQuality quality,
            OffAxisStabilityOptions stabilityOptions
    ) {
        return switch (backend == null ? TrackerBackendMode.OPENCV : backend) {
            case MODERN_ONNX -> ModernOnnxExpressionTracker.create(quality, stabilityOptions);
            case OPENCV -> new FaceExpressionTracker(quality, stabilityOptions);
        };
    }

    private String performanceStatus() {
        ExpressionTrackerBackend currentTracker = tracker;
        String trackerStatus = currentTracker == null ? "" : ", " + currentTracker.runtimeStatus();
        return activeTrackerBackend + ", " + activeQuality + ", " + activeRuntime.status() + trackerStatus;
    }

    private RecognitionQuality selectedQuality() {
        Object selected = qualityMode.getSelectedItem();
        return selected instanceof RecognitionQuality quality ? quality : RecognitionQuality.BALANCED;
    }

    private TrackerBackendMode selectedTrackerBackend() {
        Object selected = trackerBackend.getSelectedItem();
        return selected instanceof TrackerBackendMode backend ? backend : TrackerBackendMode.OPENCV;
    }

    private HardwareAccelerationMode selectedAccelerationMode() {
        Object selected = accelerationMode.getSelectedItem();
        return selected instanceof HardwareAccelerationMode mode ? mode : HardwareAccelerationMode.AUTO;
    }

    private OffAxisStabilityOptions selectedOffAxisOptions() {
        OffAxisMode mode = selectedOffAxisMode();
        int holdMillis = (Integer) offAxisHoldMillis.getValue();
        int minQualityPercent = (Integer) minFrontalQuality.getValue();
        return new OffAxisStabilityOptions(mode, holdMillis, minQualityPercent / 100.0);
    }

    private OffAxisMode selectedOffAxisMode() {
        Object selected = offAxisMode.getSelectedItem();
        return selected instanceof OffAxisMode value ? value : OffAxisMode.HOLD_LAST;
    }

    private void loadCalibrationProfile(ExpressionTrackerBackend currentTracker, CalibrationProfileStore.ProfileKey profileKey) {
        try {
            CalibrationProfile profile = profileStore.load(profileKey);
            if (profile == null) {
                savedCalibrationRevision = currentTracker.calibrationRevision();
                profileLabel.setText("No saved " + profileKey.displayName());
                return;
            }

            currentTracker.applyCalibrationProfile(profile);
            savedCalibrationRevision = currentTracker.calibrationRevision();
            profileLabel.setText("Loaded " + profile.readyExpressionCount() + " expressions");
            calibrationLabel.setText(currentTracker.calibrationStatus());
        } catch (IOException exception) {
            savedCalibrationRevision = currentTracker.calibrationRevision();
            profileLabel.setText("Profile load failed");
            updateStatus("Profile load failed");
        }
    }

    private void saveCalibrationProfileIfNeeded(ExpressionTrackerBackend currentTracker) {
        long revision = currentTracker.calibrationRevision();
        if (revision == savedCalibrationRevision) {
            return;
        }
        CalibrationProfileStore.ProfileKey profileKey = activeProfileKey;
        if (profileKey == null) {
            return;
        }

        try {
            Path file = profileStore.save(profileKey, currentTracker.calibrationProfile());
            savedCalibrationRevision = revision;
            profileLabel.setText("Saved " + shortPath(file));
        } catch (IOException exception) {
            profileLabel.setText("Profile save failed");
            updateStatus("Profile save failed");
        }
    }

    private void shutdown() {
        running.set(false);
        saveClientSettingsQuietly();
        ExpressionTrackerBackend currentTracker = tracker;
        if (currentTracker != null) {
            saveCalibrationProfileIfNeeded(currentTracker);
        }
        stopRecording();

        Future<?> task = cameraTask;
        if (task != null) {
            try {
                task.get(1500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                task.cancel(true);
            }
        }

        shutdownAnalysisExecutor();
        if (currentTracker != null) {
            currentTracker.close();
            tracker = null;
        }
        cameraExecutor.shutdownNow();
        microphone.close();
        minecraftBridge.close();
        dispose();
    }

    private void shutdownAnalysisExecutor() {
        ExecutorService analyzer = analysisExecutor;
        analysisExecutor = null;
        analysisInFlight.set(0);
        if (analyzer == null) {
            return;
        }

        analyzer.shutdownNow();
        try {
            analyzer.awaitTermination(750, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void showError(String title, Exception exception) {
        JOptionPane.showMessageDialog(this, exception.getMessage(), title, JOptionPane.ERROR_MESSAGE);
    }

    private static String shortPath(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private static int defaultOpenCvThreads() {
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    private static final class WrappingFlowLayout extends FlowLayout {
        private WrappingFlowLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            Dimension minimum = layoutSize(target, false);
            minimum.width = Math.max(1, minimum.width - getHgap());
            return minimum;
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getWidth();
                if (targetWidth <= 0) {
                    Container parent = target.getParent();
                    targetWidth = parent == null ? Integer.MAX_VALUE : parent.getWidth();
                }
                if (targetWidth <= 0) {
                    targetWidth = Integer.MAX_VALUE;
                }

                Insets insets = target.getInsets();
                int horizontalInset = insets.left + insets.right + getHgap() * 2;
                int maxWidth = Math.max(1, targetWidth - horizontalInset);
                Dimension size = new Dimension(0, 0);
                int rowWidth = 0;
                int rowHeight = 0;

                for (Component component : target.getComponents()) {
                    if (!component.isVisible()) {
                        continue;
                    }

                    Dimension componentSize = preferred ? component.getPreferredSize() : component.getMinimumSize();
                    int nextWidth = rowWidth == 0 ? componentSize.width : rowWidth + getHgap() + componentSize.width;
                    if (nextWidth > maxWidth && rowWidth > 0) {
                        addRow(size, rowWidth, rowHeight);
                        rowWidth = componentSize.width;
                        rowHeight = componentSize.height;
                    } else {
                        rowWidth = nextWidth;
                        rowHeight = Math.max(rowHeight, componentSize.height);
                    }
                }

                addRow(size, rowWidth, rowHeight);
                size.width += horizontalInset;
                size.height += insets.top + insets.bottom + getVgap() * 2;
                return size;
            }
        }

        private void addRow(Dimension size, int rowWidth, int rowHeight) {
            if (rowWidth <= 0) {
                return;
            }
            size.width = Math.max(size.width, rowWidth);
            if (size.height > 0) {
                size.height += getVgap();
            }
            size.height += rowHeight;
        }
    }
}
