package com.facetrack.client;

import static org.bytedeco.opencv.global.opencv_core.BORDER_CONSTANT;
import static org.bytedeco.opencv.global.opencv_core.copyMakeBorder;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2RGB;
import static org.bytedeco.opencv.global.opencv_imgproc.FONT_HERSHEY_SIMPLEX;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_AA;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_8;
import static org.bytedeco.opencv.global.opencv_imgproc.circle;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;
import static org.bytedeco.opencv.global.opencv_imgproc.putText;
import static org.bytedeco.opencv.global.opencv_imgproc.rectangle;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.awt.image.BufferedImage;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bytedeco.javacpp.indexer.UByteIndexer;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

final class ModernOnnxExpressionTracker implements ExpressionTrackerBackend {
    private static final int DEFAULT_INPUT_SIZE = 112;
    private static final int NEUTRAL_BASELINE_FRAMES = 45;
    private static final double BLINK_THRESHOLD = 0.52;
    private static final double WINK_THRESHOLD = 0.50;
    private static final double SURPRISED_OPEN_THRESHOLD = 0.34;
    private static final double SURPRISED_BROW_THRESHOLD = 0.12;
    private static final double TALKING_ACTIVITY_THRESHOLD = 0.36;
    private static final double FUNNY_SMILE_THRESHOLD = 0.30;
    private static final double TONGUE_FUNNY_THRESHOLD = 0.46;
    private static final double HAPPY_SMILE_THRESHOLD = 0.28;
    private static final double SAD_MOUTH_THRESHOLD = 0.36;
    private static final double SAD_BROW_THRESHOLD = 0.16;
    private static final double FOCUSED_BROW_THRESHOLD = 0.28;
    private static final Size FACE_MIN_SIZE = new Size(90, 90);
    private static final Scalar FACE_COLOR = new Scalar(55, 220, 180, 0);
    private static final Scalar LANDMARK_COLOR = new Scalar(90, 245, 255, 0);
    private static final Scalar TEXT_BACKDROP = new Scalar(24, 26, 30, 0);
    private static final CalibrationProfile EMPTY_PROFILE = new CalibrationProfile(
            new CalibrationProfile.Baseline(1, 0, 0.0, 0.0, 0.0, 0.0),
            Map.of()
    );

    private final FaceExpressionTracker fallback;
    private final CascadeClassifier faceCascade;
    private final OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
    private final Java2DFrameConverter imageConverter = new Java2DFrameConverter();
    private final OnnxNeutralBaseline neutralBaseline = new OnnxNeutralBaseline(NEUTRAL_BASELINE_FRAMES);
    private final RollingMetric mouthOpenHistory = new RollingMetric(8);
    private final RollingMetric mouthWideHistory = new RollingMetric(8);
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String inputName;
    private final ModelInput input;
    private volatile String status;
    private double previousMouthOpenScore;
    private double previousMouthWideScore;
    private double mouthActivityScore;

    private ModernOnnxExpressionTracker(
            RecognitionQuality quality,
            OffAxisStabilityOptions offAxisOptions,
            OrtEnvironment environment,
            OrtSession session,
            String inputName,
            ModelInput input,
            String status
    ) {
        this.fallback = new FaceExpressionTracker(quality, offAxisOptions);
        this.faceCascade = loadCascade();
        this.environment = environment;
        this.session = session;
        this.inputName = inputName;
        this.input = input;
        this.status = status;
    }

    static ExpressionTrackerBackend create(RecognitionQuality quality, OffAxisStabilityOptions offAxisOptions) {
        String configuredPath = System.getProperty("facetrack.onnx.model", "").trim();
        if (configuredPath.isEmpty()) {
            return new ModernOnnxExpressionTracker(
                    quality,
                    offAxisOptions,
                    null,
                    null,
                    "",
                    null,
                    "Modern ONNX unavailable: set -Dfacetrack.onnx.model=/path/to/model.onnx"
            );
        }

        Path modelPath = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(modelPath)) {
            return new ModernOnnxExpressionTracker(
                    quality,
                    offAxisOptions,
                    null,
                    null,
                    "",
                    null,
                    "Modern ONNX unavailable: model file not found"
            );
        }

        try {
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            OrtSession session = environment.createSession(modelPath.toString(), options);
            String inputName = session.getInputNames().iterator().next();
            ModelInput input = ModelInput.from(session.getInputInfo().get(inputName));
            return new ModernOnnxExpressionTracker(
                    quality,
                    offAxisOptions,
                    environment,
                    session,
                    inputName,
                    input,
                    "Modern ONNX landmarks, " + input.width() + "x" + input.height() + ", " + (input.nchw() ? "NCHW" : "NHWC")
            );
        } catch (RuntimeException | OrtException exception) {
            return new ModernOnnxExpressionTracker(
                    quality,
                    offAxisOptions,
                    null,
                    null,
                    "",
                    null,
                    "Modern ONNX unavailable: " + exception.getMessage()
            );
        }
    }

    @Override
    public boolean requestCalibration(String expression) {
        if (!available()) {
            return fallback.requestCalibration(expression);
        }
        if (expression != null && expression.trim().equalsIgnoreCase("Neutral")) {
            neutralBaseline.reset();
            resetMotion();
            status = "Modern ONNX learning neutral baseline";
            return true;
        }

        status = "Modern ONNX uses neutral calibration only";
        return false;
    }

    @Override
    public String calibrationStatus() {
        return available() ? neutralBaseline.status() : fallback.calibrationStatus();
    }

    @Override
    public long calibrationRevision() {
        return available() ? neutralBaseline.revision() : fallback.calibrationRevision();
    }

    @Override
    public CalibrationProfile calibrationProfile() {
        return available() ? neutralBaseline.profile() : fallback.calibrationProfile();
    }

    @Override
    public void applyCalibrationProfile(CalibrationProfile profile) {
        if (!available()) {
            fallback.applyCalibrationProfile(profile);
            return;
        }
        neutralBaseline.apply(profile);
    }

    @Override
    public TrackingFrame track(Mat cameraFrame, double fps, MicrophoneSpeechDetector.SpeechSample speech) {
        if (!available()) {
            return fallback.track(cameraFrame, fps, speech);
        }

        try {
            return trackWithOnnx(cameraFrame, fps, speech);
        } catch (RuntimeException | OrtException exception) {
            status = "Modern ONNX failed, using OpenCV fallback: " + exception.getMessage();
            return fallback.track(cameraFrame, fps, speech);
        }
    }

    @Override
    public String runtimeStatus() {
        return status;
    }

    @Override
    public void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (OrtException ignored) {
            // Session cleanup failure is not actionable for the UI.
        }
        fallback.close();
    }

    private boolean available() {
        return session != null && input != null && inputName != null && !inputName.isBlank();
    }

    private TrackingFrame trackWithOnnx(Mat cameraFrame, double fps, MicrophoneSpeechDetector.SpeechSample speech) throws OrtException {
        Mat frame = cameraFrame.clone();
        Mat gray = new Mat();
        try {
            cvtColor(frame, gray, COLOR_BGR2GRAY);
            equalizeHist(gray, gray);

            Rect face = detectFace(gray);
            if (face == null) {
                resetMotion();
                ExpressionEstimate estimate = ExpressionEstimate.noFace(Instant.now(), fps);
                return new TrackingFrame(toImage(frame), estimate);
            }

            Rect crop = paddedRect(face, frame.cols(), frame.rows());
            LandmarkSet landmarks = runLandmarkModel(frame, crop);
            LandmarkFeatures rawFeatures = LandmarkFeatures.from(landmarks, face)
                    .withTongue(tongueScore(frame, landmarks, face));
            if (!neutralBaseline.ready()) {
                neutralBaseline.add(rawFeatures);
                ExpressionEstimate estimate = neutralEstimate(rawFeatures, face, fps).withMicrophoneSpeech(speech);
                annotate(frame, estimate, face, landmarks);
                return new TrackingFrame(toImage(frame), estimate);
            }

            LandmarkFeatures features = rawFeatures.adjusted(neutralBaseline.snapshot());
            updateMouthActivity(features.mouth().openScore(), features.mouth().wideScore());

            ExpressionEstimate estimate = estimateExpression(features, face, fps).withMicrophoneSpeech(speech);
            annotate(frame, estimate, face, landmarks);
            return new TrackingFrame(toImage(frame), estimate);
        } finally {
            gray.close();
            frame.close();
        }
    }

    private Rect detectFace(Mat gray) {
        RectVector faces = new RectVector();
        try {
            faceCascade.detectMultiScale(gray, faces, 1.08, 4, 0, FACE_MIN_SIZE, new Size());
            if (faces.size() == 0) {
                return null;
            }

            Rect best = faces.get(0);
            int bestArea = best.width() * best.height();
            for (long index = 1; index < faces.size(); index++) {
                Rect candidate = faces.get(index);
                int area = candidate.width() * candidate.height();
                if (area > bestArea) {
                    best = candidate;
                    bestArea = area;
                }
            }
            return new Rect(best.x(), best.y(), best.width(), best.height());
        } finally {
            faces.close();
        }
    }

    private LandmarkSet runLandmarkModel(Mat frame, Rect crop) throws OrtException {
        Mat face = new Mat(frame, crop);
        Mat resized = new Mat();
        Mat rgb = new Mat();
        try {
            resize(face, resized, new Size(input.width(), input.height()), 0.0, 0.0, INTER_LINEAR);
            cvtColor(resized, rgb, COLOR_BGR2RGB);
            OnnxTensor tensor = inputTensor(rgb);
            try (tensor; OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
                if (result.size() <= 0) {
                    throw new IllegalStateException("Model returned no outputs");
                }
                List<Float> values = new ArrayList<>();
                for (int index = 0; index < result.size(); index++) {
                    OnnxValue output = result.get(index);
                    Object raw = output.getValue();
                    values.clear();
                    flatten(raw, values);
                    if (values.size() >= 136 && values.size() <= 512) {
                        return LandmarkSet.from(values, crop, input.width(), input.height());
                    }
                }
                throw new IllegalStateException("Expected 68 or 98 landmark coordinate output");
            }
        } finally {
            rgb.close();
            resized.close();
            face.close();
        }
    }

    private OnnxTensor inputTensor(Mat rgb) throws OrtException {
        UByteIndexer pixels = rgb.createIndexer();
        try {
            if (input.nchw()) {
                float[][][][] data = new float[1][3][input.height()][input.width()];
                for (int y = 0; y < input.height(); y++) {
                    for (int x = 0; x < input.width(); x++) {
                        data[0][0][y][x] = pixels.get(y, x, 0) / 255.0F;
                        data[0][1][y][x] = pixels.get(y, x, 1) / 255.0F;
                        data[0][2][y][x] = pixels.get(y, x, 2) / 255.0F;
                    }
                }
                return OnnxTensor.createTensor(environment, data);
            }

            float[][][][] data = new float[1][input.height()][input.width()][3];
            for (int y = 0; y < input.height(); y++) {
                for (int x = 0; x < input.width(); x++) {
                    data[0][y][x][0] = pixels.get(y, x, 0) / 255.0F;
                    data[0][y][x][1] = pixels.get(y, x, 1) / 255.0F;
                    data[0][y][x][2] = pixels.get(y, x, 2) / 255.0F;
                }
            }
            return OnnxTensor.createTensor(environment, data);
        } finally {
            pixels.release();
        }
    }

    private ExpressionEstimate estimateExpression(LandmarkFeatures features, Rect face, double fps) {
        MouthMetrics mouth = features.mouth();
        EyeMetrics eyes = features.eyes();
        BrowMetrics brows = features.brows();
        double tongueCue = tongueCue(mouth);
        boolean tongueFunny = tongueCue >= TONGUE_FUNNY_THRESHOLD;

        String expression = "Neutral";
        double confidence = 0.42;
        if (eyes.blinkScore() >= BLINK_THRESHOLD) {
            expression = "Blinking";
            confidence = eyes.blinkScore();
        } else if (tongueFunny || (mouth.smileScore() >= FUNNY_SMILE_THRESHOLD
                && (eyes.winkScore() >= 0.28 || brows.raiseScore() >= 0.25 || mouth.wideScore() >= 0.34))) {
            expression = "Funny";
            confidence = clamp(Math.max(tongueCue * 0.88,
                    (mouth.smileScore() * 0.65) + Math.max(eyes.winkScore(), brows.raiseScore()) * 0.35), 0.46, 0.96);
        } else if (mouth.openScore() >= SURPRISED_OPEN_THRESHOLD
                && (brows.raiseScore() >= SURPRISED_BROW_THRESHOLD || mouth.openScore() >= 0.48)
                && mouth.smileScore() < 0.24
                && mouthActivityScore < 0.42) {
            expression = "Surprised";
            confidence = clamp((mouth.openScore() * 0.72) + (brows.raiseScore() * 0.28), 0.45, 0.96);
        } else if (eyes.winkScore() >= WINK_THRESHOLD) {
            expression = "Winking";
            confidence = eyes.winkScore();
        } else if (mouth.smileScore() >= HAPPY_SMILE_THRESHOLD) {
            expression = "Happy";
            confidence = clamp(mouth.smileScore(), 0.45, 0.95);
        } else if ((mouth.sadScore() >= SAD_MOUTH_THRESHOLD && brows.sadScore() >= SAD_BROW_THRESHOLD)
                || (brows.sadScore() >= 0.44 && mouth.smileScore() < 0.24)) {
            expression = "Sad";
            confidence = clamp(Math.max(mouth.sadScore(), brows.sadScore()), 0.42, 0.94);
        } else if (brows.furrowScore() >= FOCUSED_BROW_THRESHOLD) {
            expression = "Focused";
            confidence = clamp(brows.furrowScore(), 0.42, 0.92);
        } else if (mouthActivityScore >= TALKING_ACTIVITY_THRESHOLD && mouth.openScore() < 0.86) {
            expression = "Talking";
            confidence = clamp(0.42 + mouthActivityScore * 0.62, 0.42, 0.95);
        }

        return new ExpressionEstimate(
                Instant.now(),
                expression,
                confidence,
                1,
                new Rect(face.x(), face.y(), face.width(), face.height()),
                eyes.eyeCount(),
                mouth.smileScore() >= HAPPY_SMILE_THRESHOLD ? 1 : 0,
                mouth.smileScore(),
                mouth.openScore(),
                mouth.wideScore(),
                mouth.sadScore(),
                eyes.blinkScore(),
                eyes.winkScore(),
                partsFor(expression, mouth, eyes, brows),
                fps,
                clamp(1.0 - features.headYawScore() * 0.84, 0.0, 1.0)
        );
    }

    private ExpressionEstimate neutralEstimate(LandmarkFeatures features, Rect face, double fps) {
        double completion = neutralBaseline.completion();
        return new ExpressionEstimate(
                Instant.now(),
                "Neutral",
                clamp(0.24 + completion * 0.30, 0.24, 0.54),
                1,
                new Rect(face.x(), face.y(), face.width(), face.height()),
                features.eyes().eyeCount(),
                0,
                0.0,
                0.0,
                0.0,
                0.0,
                features.eyes().blinkScore(),
                features.eyes().winkScore(),
                FaceParts.DEFAULT,
                fps,
                clamp(1.0 - features.headYawScore() * 0.84, 0.0, 1.0)
        );
    }

    private FaceParts partsFor(String expression, MouthMetrics mouth, EyeMetrics eyes, BrowMetrics brows) {
        if ("Funny".equals(expression)) {
            return new FaceParts(FaceParts.Mouth.FUNNY, FaceParts.Eye.CLOSED, FaceParts.Eye.OPEN,
                    FaceParts.Eyebrow.RAISED, FaceParts.Eyebrow.RAISED);
        }

        FaceParts.Mouth mouthPart = FaceParts.Mouth.NEUTRAL;
        if ("Talking".equals(expression)) {
            mouthPart = FaceParts.Mouth.TALKING;
        } else if ("Surprised".equals(expression) || (mouth.openScore() >= SURPRISED_OPEN_THRESHOLD && mouthActivityScore < 0.42)) {
            mouthPart = FaceParts.Mouth.SURPRISED;
        } else if ("Happy".equals(expression) || mouth.smileScore() >= HAPPY_SMILE_THRESHOLD) {
            mouthPart = FaceParts.Mouth.HAPPY;
        } else if ("Sad".equals(expression) || mouth.sadScore() >= SAD_MOUTH_THRESHOLD) {
            mouthPart = FaceParts.Mouth.SAD;
        } else if (mouthActivityScore >= TALKING_ACTIVITY_THRESHOLD && mouth.openScore() < 0.86) {
            mouthPart = FaceParts.Mouth.TALKING;
        }

        FaceParts.Eye leftEye = eyePart(eyes.leftClosedScore(), expression);
        FaceParts.Eye rightEye = eyePart(eyes.rightClosedScore(), expression);
        FaceParts.Eyebrow leftBrow = browPart(brows.leftRaiseScore(), brows.leftFurrowScore(), brows.leftSadScore(), expression);
        FaceParts.Eyebrow rightBrow = browPart(brows.rightRaiseScore(), brows.rightFurrowScore(), brows.rightSadScore(), expression);
        return new FaceParts(mouthPart, leftEye, rightEye, leftBrow, rightBrow);
    }

    private static FaceParts.Eye eyePart(double closedScore, String expression) {
        if (closedScore >= 0.45) {
            return FaceParts.Eye.CLOSED;
        }
        return "Focused".equals(expression) ? FaceParts.Eye.FOCUSED : FaceParts.Eye.OPEN;
    }

    private static FaceParts.Eyebrow browPart(double raiseScore, double furrowScore, double sadScore, String expression) {
        if ("Focused".equals(expression)) {
            return FaceParts.Eyebrow.FOCUSED;
        }
        if ("Sad".equals(expression)) {
            return FaceParts.Eyebrow.SAD;
        }
        if ("Surprised".equals(expression)) {
            return FaceParts.Eyebrow.RAISED;
        }
        if (furrowScore >= FOCUSED_BROW_THRESHOLD && furrowScore >= sadScore + 0.08 && furrowScore >= raiseScore + 0.08) {
            return FaceParts.Eyebrow.FOCUSED;
        }
        if (sadScore >= SAD_BROW_THRESHOLD && sadScore >= furrowScore + 0.04 && sadScore >= raiseScore + 0.02) {
            return FaceParts.Eyebrow.SAD;
        }
        if (raiseScore >= SURPRISED_BROW_THRESHOLD && raiseScore >= sadScore + 0.05) {
            return FaceParts.Eyebrow.RAISED;
        }
        return FaceParts.Eyebrow.NEUTRAL;
    }

    private void updateMouthActivity(double openScore, double wideScore) {
        boolean hasHistory = mouthOpenHistory.hasSamples();
        mouthOpenHistory.add(openScore);
        mouthWideHistory.add(wideScore);
        double deltaOpen = hasHistory ? Math.abs(openScore - previousMouthOpenScore) : 0.0;
        double deltaWide = hasHistory ? Math.abs(wideScore - previousMouthWideScore) : 0.0;
        double variation = Math.max(0.0, mouthOpenHistory.standardDeviation() - 0.020) * 3.6
                + Math.max(0.0, mouthWideHistory.standardDeviation() - 0.016) * 2.4;
        double activity = clamp((deltaOpen * 2.5) + (deltaWide * 2.0) + variation, 0.0, 1.0);
        mouthActivityScore += (activity - mouthActivityScore) * (activity > mouthActivityScore ? 0.68 : 0.24);
        previousMouthOpenScore = openScore;
        previousMouthWideScore = wideScore;
    }

    private void resetMotion() {
        previousMouthOpenScore = 0.0;
        previousMouthWideScore = 0.0;
        mouthActivityScore = 0.0;
        mouthOpenHistory.clear();
        mouthWideHistory.clear();
    }

    private static double tongueScore(Mat frame, LandmarkSet landmarks, Rect face) {
        return TongueDetector.score(frame, landmarkMouthRegion(landmarks, face, frame.cols(), frame.rows()));
    }

    private static double tongueCue(MouthMetrics mouth) {
        double shapeSupport = Math.max(mouth.openScore(), mouth.wideScore() * 0.80);
        return mouth.tongueScore() * clamp((shapeSupport - 0.10) / 0.30, 0.0, 1.0);
    }

    private static Rect landmarkMouthRegion(LandmarkSet landmarks, Rect face, int frameWidth, int frameHeight) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int index = 48; index <= 67; index++) {
            LandmarkPoint point = landmarks.point(index);
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
        }

        if (!Double.isFinite(minX) || !Double.isFinite(minY) || maxX <= minX || maxY <= minY) {
            return new Rect(
                    face.x() + face.width() / 5,
                    face.y() + face.height() * 45 / 100,
                    Math.max(1, face.width() * 3 / 5),
                    Math.max(1, face.height() * 45 / 100)
            );
        }

        int mouthWidth = Math.max(1, (int) Math.round(maxX - minX));
        int mouthHeight = Math.max(1, (int) Math.round(maxY - minY));
        int padX = Math.max(4, Math.max(mouthWidth / 3, face.width() / 18));
        int padTop = Math.max(3, mouthHeight / 4);
        int padBottom = Math.max(8, Math.max(mouthHeight, face.height() / 9));
        int x = (int) Math.round(minX) - padX;
        int y = (int) Math.round(minY) - padTop;
        int right = (int) Math.round(maxX) + padX;
        int bottom = (int) Math.round(maxY) + padBottom;
        return new Rect(
                clampInt(x, 0, Math.max(0, frameWidth - 1)),
                clampInt(y, 0, Math.max(0, frameHeight - 1)),
                Math.max(1, clampInt(right, 0, frameWidth) - clampInt(x, 0, Math.max(0, frameWidth - 1))),
                Math.max(1, clampInt(bottom, 0, frameHeight) - clampInt(y, 0, Math.max(0, frameHeight - 1)))
        );
    }

    private void annotate(Mat frame, ExpressionEstimate estimate, Rect face, LandmarkSet landmarks) {
        rectangle(frame, face, FACE_COLOR, 2, LINE_8, 0);
        for (int index : new int[] {36, 39, 42, 45, 48, 51, 54, 57, 62, 66}) {
            LandmarkPoint point = landmarks.point(index);
            circle(frame, new Point((int) Math.round(point.x()), (int) Math.round(point.y())), 2, LANDMARK_COLOR, -1, LINE_AA, 0);
        }

        String text = estimate.expression() + " " + String.format(Locale.US, "%.0f%%", estimate.confidence() * 100.0);
        Rect backdrop = new Rect(face.x(), Math.max(0, face.y() - 34), Math.max(150, text.length() * 12), 30);
        rectangle(frame, backdrop, TEXT_BACKDROP, -1, LINE_8, 0);
        putText(frame, text, new Point(Math.max(6, face.x() + 8), Math.max(22, face.y() - 11)),
                FONT_HERSHEY_SIMPLEX, 0.7, FACE_COLOR, 2, LINE_AA, false);
    }

    private BufferedImage toImage(Mat frame) {
        return imageConverter.convert(matConverter.convert(frame));
    }

    private static Rect paddedRect(Rect face, int frameWidth, int frameHeight) {
        int padX = Math.max(8, Math.round(face.width() * 0.12F));
        int padY = Math.max(8, Math.round(face.height() * 0.14F));
        int x = Math.max(0, face.x() - padX);
        int y = Math.max(0, face.y() - padY);
        int right = Math.min(frameWidth, face.x() + face.width() + padX);
        int bottom = Math.min(frameHeight, face.y() + face.height() + padY);
        return new Rect(x, y, Math.max(1, right - x), Math.max(1, bottom - y));
    }

    private static CascadeClassifier loadCascade() {
        CascadeClassifier classifier = new CascadeClassifier(ResourceExtractor.extractCascade("haarcascade_frontalface_alt2.xml").toString());
        if (classifier.empty()) {
            throw new IllegalStateException("OpenCV could not load face cascade");
        }
        return classifier;
    }

    private static void flatten(Object value, List<Float> values) {
        if (value == null) {
            return;
        }
        if (value instanceof Number number) {
            values.add(number.floatValue());
            return;
        }
        Class<?> type = value.getClass();
        if (!type.isArray()) {
            return;
        }

        int length = Array.getLength(value);
        for (int index = 0; index < length; index++) {
            flatten(Array.get(value, index), values);
        }
    }

    private static double distance(LandmarkPoint first, LandmarkPoint second) {
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double averageY(LandmarkSet points, int startInclusive, int endInclusive) {
        double sum = 0.0;
        int count = 0;
        for (int index = startInclusive; index <= endInclusive; index++) {
            sum += points.point(index).y();
            count++;
        }
        return sum / Math.max(1, count);
    }

    private static double eyeAspectRatio(LandmarkSet points, int left, int upperLeft, int upperRight,
                                         int right, int lowerRight, int lowerLeft) {
        double horizontal = distance(points.point(left), points.point(right));
        if (horizontal <= 0.0) {
            return 0.0;
        }

        double vertical = distance(points.point(upperLeft), points.point(lowerLeft))
                + distance(points.point(upperRight), points.point(lowerRight));
        return vertical / (2.0 * horizontal);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ModelInput(int width, int height, boolean nchw) {
        private static ModelInput from(NodeInfo nodeInfo) {
            if (!(nodeInfo.getInfo() instanceof TensorInfo tensorInfo)) {
                throw new IllegalArgumentException("ONNX input is not a tensor");
            }

            long[] shape = tensorInfo.getShape();
            if (shape.length != 4) {
                throw new IllegalArgumentException("Expected a 4D image tensor input");
            }

            int fallbackSize = Math.max(16, Integer.getInteger("facetrack.onnx.inputSize", DEFAULT_INPUT_SIZE));
            boolean nchw = shape[1] == 3 || shape[3] != 3;
            int height = dimension(shape[nchw ? 2 : 1], fallbackSize);
            int width = dimension(shape[nchw ? 3 : 2], fallbackSize);
            return new ModelInput(width, height, nchw);
        }

        private static int dimension(long value, int fallback) {
            return value > 0 && value <= 4096 ? (int) value : fallback;
        }
    }

    private record LandmarkSet(List<LandmarkPoint> points) {
        private static LandmarkSet from(List<Float> values, Rect crop, int inputWidth, int inputHeight) {
            int count = Math.min(68, values.size() / 2);
            if (count < 68) {
                throw new IllegalArgumentException("Need at least 68 landmarks");
            }

            CoordinateMode mode = CoordinateMode.from(values, count, inputWidth, inputHeight);
            List<LandmarkPoint> points = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                double rawX = values.get(index * 2);
                double rawY = values.get(index * 2 + 1);
                points.add(mode.toFramePoint(rawX, rawY, crop, inputWidth, inputHeight));
            }
            return new LandmarkSet(List.copyOf(points));
        }

        private LandmarkPoint point(int index) {
            return points.get(index);
        }
    }

    private enum CoordinateMode {
        ZERO_TO_ONE {
            @Override
            LandmarkPoint toFramePoint(double x, double y, Rect crop, int inputWidth, int inputHeight) {
                return new LandmarkPoint(crop.x() + x * crop.width(), crop.y() + y * crop.height());
            }
        },
        MINUS_ONE_TO_ONE {
            @Override
            LandmarkPoint toFramePoint(double x, double y, Rect crop, int inputWidth, int inputHeight) {
                return new LandmarkPoint(crop.x() + ((x + 1.0) * 0.5) * crop.width(), crop.y() + ((y + 1.0) * 0.5) * crop.height());
            }
        },
        INPUT_PIXELS {
            @Override
            LandmarkPoint toFramePoint(double x, double y, Rect crop, int inputWidth, int inputHeight) {
                return new LandmarkPoint(crop.x() + (x / inputWidth) * crop.width(), crop.y() + (y / inputHeight) * crop.height());
            }
        };

        abstract LandmarkPoint toFramePoint(double x, double y, Rect crop, int inputWidth, int inputHeight);

        private static CoordinateMode from(List<Float> values, int count, int inputWidth, int inputHeight) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < count * 2; index++) {
                double value = values.get(index);
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            if (min >= -1.25 && max <= 1.25 && min < 0.0) {
                return MINUS_ONE_TO_ONE;
            }
            if (min >= -0.10 && max <= 2.50) {
                return ZERO_TO_ONE;
            }
            if (min >= -8.0 && max <= Math.max(inputWidth, inputHeight) * 1.75) {
                return INPUT_PIXELS;
            }
            throw new IllegalArgumentException("Unsupported landmark coordinate scale");
        }
    }

    private record LandmarkPoint(double x, double y) {
    }

    private record LandmarkFeatures(
            MouthMetrics mouth,
            EyeMetrics eyes,
            BrowMetrics brows,
            double headYawScore
    ) {
        private static LandmarkFeatures from(LandmarkSet points, Rect face) {
            double faceWidth = Math.max(1.0, face.width());
            double faceHeight = Math.max(1.0, face.height());
            return new LandmarkFeatures(
                    mouth(points, faceWidth, faceHeight),
                    eyes(points),
                    brows(points, faceWidth, faceHeight),
                    headYaw(points, faceWidth)
            );
        }

        private static MouthMetrics mouth(LandmarkSet points, double faceWidth, double faceHeight) {
            LandmarkPoint leftCorner = points.point(48);
            LandmarkPoint rightCorner = points.point(54);
            LandmarkPoint upperInner = points.point(62);
            LandmarkPoint lowerInner = points.point(66);
            LandmarkPoint upperOuter = points.point(51);
            LandmarkPoint lowerOuter = points.point(57);

            double mouthWidth = distance(leftCorner, rightCorner) / faceWidth;
            double innerHeight = ((distance(points.point(61), points.point(67))
                    + distance(upperInner, lowerInner)
                    + distance(points.point(63), points.point(65))) / 3.0) / faceHeight;
            double outerHeight = distance(upperOuter, lowerOuter) / faceHeight;
            double opennessRatio = innerHeight / Math.max(0.08, mouthWidth);
            double cornerY = (leftCorner.y() + rightCorner.y()) / 2.0;
            double centerY = (upperInner.y() + lowerInner.y() + upperOuter.y() + lowerOuter.y()) / 4.0;
            double upturn = (centerY - cornerY) / faceHeight;
            double downturn = (cornerY - centerY) / faceHeight;

            double openScore = clamp(((innerHeight - 0.010) * 10.8)
                    + ((outerHeight - 0.046) * 3.9)
                    + ((opennessRatio - 0.052) * 1.7), 0.0, 1.0);
            double wideScore = clamp(((mouthWidth - 0.305) * 3.5)
                    - (openScore * 0.20)
                    + (Math.max(0.0, upturn) * 0.85), 0.0, 1.0);
            double smileScore = clamp(((upturn - 0.0015) * 20.0)
                    + ((mouthWidth - 0.335) * 0.95)
                    - (openScore * 0.12), 0.0, 1.0);
            double sadScore = clamp(((downturn - 0.0015) * 21.5)
                    + ((0.305 - mouthWidth) * 0.28)
                    - (openScore * 0.28), 0.0, 1.0);
            return new MouthMetrics(openScore, wideScore, sadScore, smileScore, 0.0);
        }

        private static EyeMetrics eyes(LandmarkSet points) {
            double left = eyeAspectRatio(points, 36, 37, 38, 39, 40, 41);
            double right = eyeAspectRatio(points, 42, 43, 44, 45, 46, 47);
            double leftOpen = clamp((left - 0.14) / 0.11, 0.0, 1.0);
            double rightOpen = clamp((right - 0.14) / 0.11, 0.0, 1.0);
            double leftClosed = clamp((0.19 - left) / 0.07, 0.0, 1.0);
            double rightClosed = clamp((0.19 - right) / 0.07, 0.0, 1.0);
            int count = 0;
            if (left >= 0.18 || leftOpen >= 0.35) {
                count++;
            }
            if (right >= 0.18 || rightOpen >= 0.35) {
                count++;
            }
            return new EyeMetrics(count, Math.min(leftClosed, rightClosed), Math.max(leftClosed * rightOpen, rightClosed * leftOpen),
                    leftClosed, rightClosed);
        }

        private static BrowMetrics brows(LandmarkSet points, double faceWidth, double faceHeight) {
            double leftBrowY = averageY(points, 17, 21);
            double rightBrowY = averageY(points, 22, 26);
            double leftEyeY = averageY(points, 36, 41);
            double rightEyeY = averageY(points, 42, 47);
            double leftBrowGap = (leftEyeY - leftBrowY) / faceHeight;
            double rightBrowGap = (rightEyeY - rightBrowY) / faceHeight;
            double innerBrowDistance = distance(points.point(21), points.point(22)) / faceWidth;
            double browPinchScore = clamp((0.125 - innerBrowDistance) * 8.5, 0.0, 1.0);

            double leftRaise = clamp((leftBrowGap - 0.105) * 8.0, 0.0, 1.0);
            double rightRaise = clamp((rightBrowGap - 0.105) * 8.0, 0.0, 1.0);
            double leftFurrow = browPinchScore;
            double rightFurrow = browPinchScore;
            double leftSad = clamp((0.092 - leftBrowGap) * 12.0, 0.0, 1.0);
            double rightSad = clamp((0.092 - rightBrowGap) * 12.0, 0.0, 1.0);
            return new BrowMetrics(Math.max(leftRaise, rightRaise), leftRaise, rightRaise, Math.max(leftFurrow, rightFurrow),
                    leftFurrow, rightFurrow, Math.max(leftSad, rightSad), leftSad, rightSad);
        }

        private static double headYaw(LandmarkSet points, double faceWidth) {
            LandmarkPoint noseTip = points.point(30);
            double jawCenterX = (points.point(0).x() + points.point(16).x()) / 2.0;
            double noseOffset = Math.abs(noseTip.x() - jawCenterX) / faceWidth;
            double leftEyeWidth = distance(points.point(36), points.point(39));
            double rightEyeWidth = distance(points.point(42), points.point(45));
            double eyeAsymmetry = Math.abs(leftEyeWidth - rightEyeWidth) / Math.max(1.0, leftEyeWidth + rightEyeWidth);
            return clamp(Math.max(noseOffset * 3.2, eyeAsymmetry * 2.2), 0.0, 1.0);
        }

        private LandmarkFeatures adjusted(NeutralSnapshot neutral) {
            return new LandmarkFeatures(mouth.adjusted(neutral), eyes, brows.adjusted(neutral), headYawScore);
        }

        private LandmarkFeatures withTongue(double tongueScore) {
            return new LandmarkFeatures(mouth.withTongue(tongueScore), eyes, brows, headYawScore);
        }
    }

    private record MouthMetrics(double openScore, double wideScore, double sadScore, double smileScore, double tongueScore) {
        private MouthMetrics adjusted(NeutralSnapshot neutral) {
            return new MouthMetrics(
                    adjustPositive(openScore, neutral.mouthOpenScore(), 0.025, 2.60),
                    adjustPositive(wideScore, neutral.mouthWideScore(), 0.025, 2.40),
                    adjustPositive(sadScore, neutral.mouthSadScore(), 0.035, 2.40),
                    adjustPositive(smileScore, neutral.mouthSmileScore(), 0.025, 2.80),
                    tongueScore
            );
        }

        private MouthMetrics withTongue(double tongueScore) {
            return new MouthMetrics(openScore, wideScore, sadScore, smileScore, clamp(tongueScore, 0.0, 1.0));
        }
    }

    private record EyeMetrics(int eyeCount, double blinkScore, double winkScore, double leftClosedScore, double rightClosedScore) {
    }

    private record BrowMetrics(
            double raiseScore,
            double leftRaiseScore,
            double rightRaiseScore,
            double furrowScore,
            double leftFurrowScore,
            double rightFurrowScore,
            double sadScore,
            double leftSadScore,
            double rightSadScore
    ) {
        private BrowMetrics adjusted(NeutralSnapshot neutral) {
            return new BrowMetrics(
                    adjustPositive(raiseScore, neutral.browRaiseScore(), 0.035, 2.35),
                    adjustPositive(leftRaiseScore, neutral.leftBrowRaiseScore(), 0.035, 2.35),
                    adjustPositive(rightRaiseScore, neutral.rightBrowRaiseScore(), 0.035, 2.35),
                    adjustPositive(furrowScore, neutral.browFurrowScore(), 0.035, 2.35),
                    adjustPositive(leftFurrowScore, neutral.leftBrowFurrowScore(), 0.035, 2.35),
                    adjustPositive(rightFurrowScore, neutral.rightBrowFurrowScore(), 0.035, 2.35),
                    adjustPositive(sadScore, neutral.browSadScore(), 0.035, 2.35),
                    adjustPositive(leftSadScore, neutral.leftBrowSadScore(), 0.035, 2.35),
                    adjustPositive(rightSadScore, neutral.rightBrowSadScore(), 0.035, 2.35)
            );
        }
    }

    private static double adjustPositive(double value, double baseline, double margin, double scale) {
        return clamp((value - baseline - margin) * scale, 0.0, 1.0);
    }

    private record NeutralSnapshot(
            double mouthOpenScore,
            double mouthWideScore,
            double mouthSadScore,
            double mouthSmileScore,
            double browRaiseScore,
            double leftBrowRaiseScore,
            double rightBrowRaiseScore,
            double browFurrowScore,
            double leftBrowFurrowScore,
            double rightBrowFurrowScore,
            double browSadScore,
            double leftBrowSadScore,
            double rightBrowSadScore
    ) {
        private static final NeutralSnapshot ZERO = new NeutralSnapshot(
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0.0, 0.0, 0.0
        );
    }

    private static final class OnnxNeutralBaseline {
        private final int targetFrames;
        private int samples;
        private long revision;
        private NeutralSnapshot snapshot = NeutralSnapshot.ZERO;

        private OnnxNeutralBaseline(int targetFrames) {
            this.targetFrames = Math.max(1, targetFrames);
        }

        synchronized void reset() {
            samples = 0;
            revision++;
            snapshot = NeutralSnapshot.ZERO;
        }

        synchronized void add(LandmarkFeatures features) {
            samples++;
            double weight = 1.0 / samples;
            snapshot = blend(snapshot, snapshotOf(features), weight);
            if (samples == targetFrames) {
                revision++;
            }
        }

        synchronized boolean ready() {
            return samples >= targetFrames;
        }

        synchronized double completion() {
            return clamp((double) samples / targetFrames, 0.0, 1.0);
        }

        synchronized long revision() {
            return revision;
        }

        synchronized NeutralSnapshot snapshot() {
            return snapshot;
        }

        synchronized String status() {
            if (!ready()) {
                return "Learning ONNX neutral baseline " + Math.round(completion() * 100.0) + "%";
            }
            return "Modern ONNX neutral baseline ready";
        }

        synchronized CalibrationProfile profile() {
            return new CalibrationProfile(
                    new CalibrationProfile.Baseline(
                            targetFrames,
                            samples,
                            snapshot.mouthSmileScore(),
                            snapshot.mouthOpenScore(),
                            snapshot.mouthWideScore(),
                            snapshot.mouthSadScore()
                    ),
                    Map.of()
            );
        }

        synchronized void apply(CalibrationProfile profile) {
            if (profile == null || profile.baseline() == null || !profile.baseline().ready()) {
                return;
            }

            CalibrationProfile.Baseline baseline = profile.baseline();
            snapshot = new NeutralSnapshot(
                    baseline.openScore(),
                    baseline.wideScore(),
                    baseline.sadScore(),
                    baseline.smileScore(),
                    0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0
            );
            samples = Math.max(targetFrames, baseline.samples());
            revision++;
        }

        private static NeutralSnapshot snapshotOf(LandmarkFeatures features) {
            MouthMetrics mouth = features.mouth();
            BrowMetrics brows = features.brows();
            return new NeutralSnapshot(
                    mouth.openScore(),
                    mouth.wideScore(),
                    mouth.sadScore(),
                    mouth.smileScore(),
                    brows.raiseScore(),
                    brows.leftRaiseScore(),
                    brows.rightRaiseScore(),
                    brows.furrowScore(),
                    brows.leftFurrowScore(),
                    brows.rightFurrowScore(),
                    brows.sadScore(),
                    brows.leftSadScore(),
                    brows.rightSadScore()
            );
        }

        private static NeutralSnapshot blend(NeutralSnapshot previous, NeutralSnapshot current, double weight) {
            double currentWeight = clamp(weight, 0.0, 1.0);
            double previousWeight = 1.0 - currentWeight;
            return new NeutralSnapshot(
                    previous.mouthOpenScore() * previousWeight + current.mouthOpenScore() * currentWeight,
                    previous.mouthWideScore() * previousWeight + current.mouthWideScore() * currentWeight,
                    previous.mouthSadScore() * previousWeight + current.mouthSadScore() * currentWeight,
                    previous.mouthSmileScore() * previousWeight + current.mouthSmileScore() * currentWeight,
                    previous.browRaiseScore() * previousWeight + current.browRaiseScore() * currentWeight,
                    previous.leftBrowRaiseScore() * previousWeight + current.leftBrowRaiseScore() * currentWeight,
                    previous.rightBrowRaiseScore() * previousWeight + current.rightBrowRaiseScore() * currentWeight,
                    previous.browFurrowScore() * previousWeight + current.browFurrowScore() * currentWeight,
                    previous.leftBrowFurrowScore() * previousWeight + current.leftBrowFurrowScore() * currentWeight,
                    previous.rightBrowFurrowScore() * previousWeight + current.rightBrowFurrowScore() * currentWeight,
                    previous.browSadScore() * previousWeight + current.browSadScore() * currentWeight,
                    previous.leftBrowSadScore() * previousWeight + current.leftBrowSadScore() * currentWeight,
                    previous.rightBrowSadScore() * previousWeight + current.rightBrowSadScore() * currentWeight
            );
        }
    }

    private static final class RollingMetric {
        private final double[] values;
        private int nextIndex;
        private int count;
        private double sum;
        private double sumSquares;

        private RollingMetric(int size) {
            values = new double[Math.max(1, size)];
        }

        void add(double value) {
            if (count < values.length) {
                values[nextIndex] = value;
                count++;
                sum += value;
                sumSquares += value * value;
                nextIndex = (nextIndex + 1) % values.length;
                return;
            }

            double removed = values[nextIndex];
            sum -= removed;
            sumSquares -= removed * removed;
            values[nextIndex] = value;
            sum += value;
            sumSquares += value * value;
            nextIndex = (nextIndex + 1) % values.length;
        }

        void clear() {
            for (int index = 0; index < values.length; index++) {
                values[index] = 0.0;
            }
            nextIndex = 0;
            count = 0;
            sum = 0.0;
            sumSquares = 0.0;
        }

        boolean hasSamples() {
            return count > 0;
        }

        double standardDeviation() {
            if (count < 3) {
                return 0.0;
            }

            double mean = sum / count;
            double variance = Math.max(0.0, (sumSquares / count) - (mean * mean));
            return Math.sqrt(variance);
        }
    }
}
