package com.facetrack.client;

import static org.bytedeco.opencv.global.opencv_core.BORDER_DEFAULT;
import static org.bytedeco.opencv.global.opencv_core.countNonZero;
import static org.bytedeco.opencv.global.opencv_core.flip;
import static org.bytedeco.opencv.global.opencv_face.createFacemarkLBF;
import static org.bytedeco.opencv.global.opencv_imgproc.ADAPTIVE_THRESH_GAUSSIAN_C;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.FONT_HERSHEY_SIMPLEX;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_AA;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_8;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY_INV;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_OTSU;
import static org.bytedeco.opencv.global.opencv_imgproc.GaussianBlur;
import static org.bytedeco.opencv.global.opencv_imgproc.adaptiveThreshold;
import static org.bytedeco.opencv.global.opencv_imgproc.contourArea;
import static org.bytedeco.opencv.global.opencv_imgproc.moments;
import static org.bytedeco.opencv.global.opencv_imgproc.createCLAHE;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;
import static org.bytedeco.opencv.global.opencv_imgproc.findContours;
import static org.bytedeco.opencv.global.opencv_imgproc.putText;
import static org.bytedeco.opencv.global.opencv_imgproc.rectangle;
import static org.bytedeco.opencv.global.opencv_imgproc.threshold;
import static org.bytedeco.opencv.global.opencv_imgproc.boundingRect;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Moments;
import org.bytedeco.opencv.opencv_core.Point2f;
import org.bytedeco.opencv.opencv_core.Point2fVector;
import org.bytedeco.opencv.opencv_core.Point2fVectorVector;
import org.bytedeco.opencv.opencv_face.Facemark;
import org.bytedeco.opencv.opencv_imgproc.CLAHE;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

final class FaceExpressionTracker implements ExpressionTrackerBackend {
    private static final String EXPRESSION_NEUTRAL = "Neutral";
    private static final String EXPRESSION_HAPPY = "Happy";
    private static final String EXPRESSION_FUNNY = "Funny";
    private static final String EXPRESSION_SAD = "Sad";
    private static final String EXPRESSION_SURPRISED = "Surprised";
    private static final String EXPRESSION_TALKING = "Talking";
    private static final String EXPRESSION_BLINKING = "Blinking";
    private static final String EXPRESSION_WINKING = "Winking";
    private static final String EXPRESSION_FOCUSED = "Focused";
    private static final String EXPRESSION_NO_FACE = "No face";
    private static final String[] CALIBRATABLE_EXPRESSIONS = {
            EXPRESSION_NEUTRAL,
            EXPRESSION_HAPPY,
            EXPRESSION_FUNNY,
            EXPRESSION_SAD,
            EXPRESSION_SURPRISED,
            EXPRESSION_TALKING,
            EXPRESSION_BLINKING,
            EXPRESSION_WINKING,
            EXPRESSION_FOCUSED
    };

    private static final Scalar FACE_COLOR = new Scalar(56, 201, 255, 0);
    private static final Scalar HAPPY_COLOR = new Scalar(72, 206, 94, 0);
    private static final Scalar FUNNY_COLOR = new Scalar(86, 235, 255, 0);
    private static final Scalar SAD_COLOR = new Scalar(210, 126, 255, 0);
    private static final Scalar SURPRISED_COLOR = new Scalar(67, 140, 255, 0);
    private static final Scalar TALKING_COLOR = new Scalar(255, 188, 66, 0);
    private static final Scalar EYE_GESTURE_COLOR = new Scalar(227, 92, 255, 0);
    private static final Scalar ALERT_COLOR = new Scalar(68, 126, 255, 0);
    private static final Scalar TEXT_BACKDROP = new Scalar(24, 26, 30, 0);
    private static final Size FACE_MIN_SIZE = new Size(90, 90);
    private static final Size EYE_MIN_SIZE = new Size(16, 12);
    private static final Size SMILE_MIN_SIZE = new Size(24, 12);
    private static final int AUTO_CALIBRATION_FRAMES = 45;
    private static final int MANUAL_CALIBRATION_FRAMES = 60;

    private final RecognitionQuality quality;
    private final OffAxisStabilityOptions offAxisOptions;
    private final CascadeClassifier faceCascade;
    private final CascadeClassifier profileFaceCascade;
    private final CascadeClassifier eyeCascade;
    private final CascadeClassifier smileCascade;
    private final CLAHE clahe;
    private final LandmarkRefiner landmarkRefiner;
    private final OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
    private final Java2DFrameConverter imageConverter = new Java2DFrameConverter();
    private final AtomicInteger calibrationFrames = new AtomicInteger(AUTO_CALIBRATION_FRAMES);
    private final CalibrationState neutralBaseline = new CalibrationState();
    private final ExpressionCalibrationBank expressionCalibrations = new ExpressionCalibrationBank();
    private final ExpressionTemporalFilter temporalFilter;
    private volatile String calibrationExpression = EXPRESSION_NEUTRAL;
    private String smoothedExpression = EXPRESSION_NO_FACE;
    private double smoothedConfidence;
    private String pendingExpression = EXPRESSION_NO_FACE;
    private int pendingFrames;
    private String lastStableExpression = EXPRESSION_NEUTRAL;
    private double lastStableConfidence;
    private long lastStableAtMillis;
    private int framesSinceEyesSeen = 99;
    private int framesSinceTwoEyesSeen = 99;
    private int consecutiveTwoEyeFrames;
    private int consecutiveOneEyeFrames;
    private int consecutiveNoEyeFrames;
    private int lastStableTwoEyeFrames;
    private double filteredSmileScore;
    private double filteredMouthOpenScore;
    private double filteredMouthWideScore;
    private double filteredSadScore;
    private double previousRawMouthOpenScore;
    private double previousRawMouthWideScore;
    private double mouthActivityScore;
    private final RollingMetric mouthOpenHistory;
    private final RollingMetric mouthWideHistory;
    private volatile String calibrationStatus = "Learning neutral baseline";
    private volatile long calibrationRevision;

    FaceExpressionTracker() {
        this(RecognitionQuality.BALANCED);
    }

    FaceExpressionTracker(RecognitionQuality quality) {
        this(quality, OffAxisStabilityOptions.defaults());
    }

    FaceExpressionTracker(RecognitionQuality quality, OffAxisStabilityOptions offAxisOptions) {
        this.quality = quality;
        this.offAxisOptions = offAxisOptions == null ? OffAxisStabilityOptions.defaults() : offAxisOptions;
        faceCascade = loadCascade("haarcascade_frontalface_alt2.xml");
        profileFaceCascade = loadCascadeOptional("haarcascade_profileface.xml");
        eyeCascade = loadCascade("haarcascade_eye_tree_eyeglasses.xml");
        smileCascade = loadCascade("haarcascade_smile.xml");
        clahe = quality.claheEnabled() ? createCLAHE(2.0, new Size(8, 8)) : null;
        landmarkRefiner = LandmarkRefiner.create(quality);
        mouthOpenHistory = new RollingMetric(quality == RecognitionQuality.ACCURATE ? 12 : 8);
        mouthWideHistory = new RollingMetric(quality == RecognitionQuality.ACCURATE ? 12 : 8);
        temporalFilter = new ExpressionTemporalFilter(quality == RecognitionQuality.ACCURATE ? 9 : quality == RecognitionQuality.BALANCED ? 7 : 5);
    }

    static String[] calibratableExpressions() {
        return CALIBRATABLE_EXPRESSIONS.clone();
    }

    void requestNeutralCalibration() {
        requestCalibration(EXPRESSION_NEUTRAL);
    }

    @Override
    public boolean requestCalibration(String expression) {
        String targetExpression = normalizeExpression(expression);
        if (targetExpression == null) {
            throw new IllegalArgumentException("Unknown expression: " + expression);
        }
        if (!EXPRESSION_NEUTRAL.equals(targetExpression) && !neutralBaseline.ready()) {
            calibrationStatus = "Calibrate neutral first";
            return false;
        }

        calibrationExpression = targetExpression;
        calibrationFrames.set(MANUAL_CALIBRATION_FRAMES);
        if (EXPRESSION_NEUTRAL.equals(targetExpression)) {
            neutralBaseline.reset(MANUAL_CALIBRATION_FRAMES);
        }
        expressionCalibrations.reset(targetExpression, MANUAL_CALIBRATION_FRAMES);
        resetDynamicExpressionState(targetExpression);
        calibrationStatus = "Hold " + targetExpression.toLowerCase(Locale.ROOT) + " expression";
        return true;
    }

    private void resetDynamicExpressionState(String expression) {
        filteredSmileScore = 0.0;
        filteredMouthOpenScore = 0.0;
        filteredMouthWideScore = 0.0;
        filteredSadScore = 0.0;
        previousRawMouthOpenScore = 0.0;
        previousRawMouthWideScore = 0.0;
        mouthActivityScore = 0.0;
        mouthOpenHistory.clear();
        mouthWideHistory.clear();
        smoothedExpression = expression;
        smoothedConfidence = 0.35;
        pendingExpression = expression;
        pendingFrames = 0;
        temporalFilter.reset();
        if (isHoldableExpression(expression)) {
            lastStableExpression = expression;
            lastStableConfidence = smoothedConfidence;
            lastStableAtMillis = System.currentTimeMillis();
        }
    }

    @Override
    public String calibrationStatus() {
        return calibrationStatus;
    }

    @Override
    public long calibrationRevision() {
        return calibrationRevision;
    }

    @Override
    public CalibrationProfile calibrationProfile() {
        return new CalibrationProfile(neutralBaseline.profile(), expressionCalibrations.profile());
    }

    @Override
    public void applyCalibrationProfile(CalibrationProfile profile) {
        if (profile == null) {
            return;
        }

        neutralBaseline.apply(profile.baseline());
        expressionCalibrations.apply(profile.expressions());
        calibrationExpression = EXPRESSION_NEUTRAL;
        calibrationFrames.set(0);
        resetDynamicExpressionState(EXPRESSION_NEUTRAL);
        calibrationStatus = readyCalibrationStatus();
    }

    TrackingFrame track(Mat cameraFrame, double fps) {
        return track(cameraFrame, fps, MicrophoneSpeechDetector.SpeechSample.disabled());
    }

    @Override
    public TrackingFrame track(Mat cameraFrame, double fps, MicrophoneSpeechDetector.SpeechSample speech) {
        Mat frame = cameraFrame.clone();
        Mat gray = new Mat();

        try {
            cvtColor(frame, gray, COLOR_BGR2GRAY);
            enhanceContrast(gray);

            RectVector faces = new RectVector();
            try {
                faceCascade.detectMultiScale(gray, faces, quality.faceScaleFactor(), quality.faceMinNeighbors(),
                        0, FACE_MIN_SIZE, new Size());

                ExpressionEstimate estimate;
                if (faces.size() == 0) {
                    ProfileFaceDetection profileFace = detectProfileFace(gray);
                    if (profileFace.found()) {
                        estimate = estimateOffAxisFace(profileFace, fps);
                    } else {
                        resetNoFaceState();
                        estimate = ExpressionEstimate.noFace(Instant.now(), fps);
                    }
                } else {
                    estimate = estimateExpression(gray, frame, faces, fps);
                }
                estimate = estimate.withMicrophoneSpeech(speech);

                annotate(frame, estimate);
                BufferedImage image = imageConverter.convert(matConverter.convert(frame));
                return new TrackingFrame(image, estimate);
            } finally {
                faces.close();
            }
        } finally {
            gray.close();
            frame.close();
        }
    }

    @Override
    public String runtimeStatus() {
        return "OpenCV heuristics";
    }

    private void enhanceContrast(Mat gray) {
        if (clahe == null) {
            equalizeHist(gray, gray);
            return;
        }

        clahe.apply(gray, gray);
    }

    private void resetNoFaceState() {
        framesSinceEyesSeen = 99;
        framesSinceTwoEyesSeen = 99;
        consecutiveTwoEyeFrames = 0;
        consecutiveOneEyeFrames = 0;
        consecutiveNoEyeFrames = 0;
        lastStableTwoEyeFrames = 0;
        pendingFrames = 0;
        pendingExpression = EXPRESSION_NO_FACE;
        temporalFilter.reset();
        smoothedExpression = EXPRESSION_NO_FACE;
        smoothedConfidence = 0.0;
        filteredSmileScore = smoothMetric(filteredSmileScore, 0.0, 0.35, 0.45);
        filteredMouthOpenScore = smoothMetric(filteredMouthOpenScore, 0.0, 0.35, 0.45);
        filteredMouthWideScore = smoothMetric(filteredMouthWideScore, 0.0, 0.35, 0.45);
        filteredSadScore = smoothMetric(filteredSadScore, 0.0, 0.35, 0.45);
        previousRawMouthOpenScore = 0.0;
        previousRawMouthWideScore = 0.0;
        mouthActivityScore = smoothMetric(mouthActivityScore, 0.0, 0.25, 0.45);
        mouthOpenHistory.clear();
        mouthWideHistory.clear();
    }

    private ExpressionEstimate estimateExpression(Mat gray, Mat frame, RectVector faces, double fps) {
        Rect face = largestFace(faces);

        Mat faceRoi = new Mat(gray, face);
        Mat colorFaceRoi = new Mat(frame, face);
        Rect eyeArea = new Rect(
                0,
                Math.max(0, face.height() / 8),
                face.width(),
                Math.max(1, face.height() / 2)
        );
        Rect smileArea = new Rect(
                Math.max(0, face.width() / 8),
                Math.max(0, face.height() / 2),
                Math.max(1, face.width() * 3 / 4),
                Math.max(1, face.height() / 2)
        );
        Rect mouthArea = new Rect(
                Math.max(0, face.width() / 6),
                Math.max(0, face.height() * 45 / 100),
                Math.max(1, face.width() * 2 / 3),
                Math.max(1, face.height() * 50 / 100)
        );

        Mat eyesRoi = new Mat(faceRoi, eyeArea);
        Mat smileRoi = new Mat(faceRoi, smileArea);
        Mat mouthRoi = new Mat(faceRoi, mouthArea);
        Mat colorMouthRoi = new Mat(colorFaceRoi, mouthArea);

        try {
            RectVector eyes = new RectVector();
            RectVector smiles = new RectVector();
            try {
                eyeCascade.detectMultiScale(eyesRoi, eyes, quality.eyeScaleFactor(), quality.eyeMinNeighbors(),
                        0, EYE_MIN_SIZE, new Size());
                smileCascade.detectMultiScale(smileRoi, smiles, quality.smileScaleFactor(), quality.smileMinNeighbors(),
                        0, SMILE_MIN_SIZE, new Size());

                EyeDetections cascadeEyes = detectIndividualEyes(eyes, face, eyeArea);
                int eyeCount = cascadeEyes.eyeCount();
                double tongueScore = TongueDetector.score(colorMouthRoi);
                MouthMetrics imageMouth = analyzeMouth(mouthRoi, face, quality).withTongue(tongueScore);
                MouthMetrics rawMouth = imageMouth;
                LandmarkSignals landmarkSignals = cascadeEyeSignals(cascadeEyes);
                LandmarkEstimate landmark = landmarkRefiner.estimate(gray, face);
                boolean landmarkMouthFound = false;
                if (landmark != null) {
                    eyeCount = landmark.eyeCount();
                    landmarkSignals = landmark.signals().withCascadeEyeFallback(cascadeEyes);
                    rawMouth = landmarkPrimaryMouth(landmark.mouth(), imageMouth, quality);
                    landmarkMouthFound = true;
                }
                updateEyeHistory(eyeCount);
                TrackingQuality trackingQuality = estimateTrackingQuality(gray, face, eyeCount, landmark != null, landmarkSignals);

                double cascadeSmileScore = scoreSmiles(smiles, face, smileArea);
                double rawSmileScore = !landmarkMouthFound
                        ? Math.max(cascadeSmileScore, rawMouth.smileCurveScore())
                        : Math.max(rawMouth.smileCurveScore(), cascadeSmileScore * 0.18);
                updateNeutralBaseline(rawSmileScore, rawMouth, trackingQuality.frontalQuality());
                CalibrationSnapshot baseline = neutralBaseline.snapshot();
                double smileScore = smoothSmile(normalizeActivation(rawSmileScore, baseline.smileScore(), 0.025, 1.45));
                MouthMetrics mouth = normalizeMouth(rawMouth, baseline);
                ExpressionFeatures features = ExpressionFeatures.from(smileScore, mouth, mouthActivityScore, eyeCount, landmarkSignals);
                ExpressionScores baseScores = scoreExpressions(features);
                boolean calibrating = updateExpressionCalibration(features, baseScores, trackingQuality.frontalQuality());
                ExpressionScores scores = expressionCalibrations.apply(baseScores, features);
                scores = scores.withOffAxisGating(trackingQuality.frontalQuality(), offAxisOptions.minFrontalQuality());
                ExpressionCandidate candidate = classify(scores, mouth.openScore(), features.mouthActivityScore());
                if (calibrating) {
                    if (trackingQuality.frontal()) {
                        String expression = calibrationExpression;
                        double confidence = Math.max(0.35, expressionCalibrations.completion(expression) * 0.75);
                        candidate = new ExpressionCandidate(expression, confidence, scores.blinkingScore(), scores.winkingScore());
                    } else {
                        candidate = offAxisFallbackCandidate(System.currentTimeMillis(), trackingQuality.frontalQuality());
                    }
                } else {
                    candidate = temporalFilter.vote(candidate, scores);
                    candidate = applyOffAxisPolicy(candidate, trackingQuality.frontalQuality(), System.currentTimeMillis());
                    if ((EXPRESSION_NEUTRAL.equals(candidate.expression()) || EXPRESSION_FOCUSED.equals(candidate.expression()))
                            && candidate.confidence() >= 0.55
                            && mouthActivityScore < 0.10
                            && trackingQuality.frontal()) {
                        neutralBaseline.adapt(rawSmileScore, rawMouth);
                    }
                    rememberStableExpression(candidate, trackingQuality.frontalQuality());
                }

                smooth(candidate.expression(), candidate.confidence());
                FaceParts parts = estimateParts(smoothedExpression, features, landmarkSignals, landmarkMouthFound);
                return new ExpressionEstimate(
                        Instant.now(),
                        smoothedExpression,
                        smoothedConfidence,
                        Math.toIntExact(faces.size()),
                        copyRect(face),
                        eyeCount,
                        Math.toIntExact(smiles.size()),
                        smileScore,
                        mouth.openScore(),
                        mouth.wideScore(),
                        mouth.sadScore(),
                        candidate.blinkScore(),
                        candidate.winkScore(),
                        parts,
                        fps,
                        trackingQuality.frontalQuality()
                );
            } finally {
                smiles.close();
                eyes.close();
            }
        } finally {
            colorMouthRoi.close();
            mouthRoi.close();
            eyesRoi.close();
            smileRoi.close();
            colorFaceRoi.close();
            faceRoi.close();
        }
    }

    private static Rect largestFace(RectVector faces) {
        Rect best = faces.get(0);
        long bestArea = (long) best.width() * best.height();

        for (long index = 1; index < faces.size(); index++) {
            Rect candidate = faces.get(index);
            long area = (long) candidate.width() * candidate.height();
            if (area > bestArea) {
                best = candidate;
                bestArea = area;
            }
        }

        return best;
    }

    private ProfileFaceDetection detectProfileFace(Mat gray) {
        if (profileFaceCascade == null) {
            return ProfileFaceDetection.NONE;
        }

        RectVector directFaces = new RectVector();
        RectVector mirroredFaces = new RectVector();
        Mat mirrored = new Mat();
        try {
            profileFaceCascade.detectMultiScale(gray, directFaces, quality.faceScaleFactor(),
                    Math.max(3, quality.faceMinNeighbors() - 1), 0, FACE_MIN_SIZE, new Size());

            flip(gray, mirrored, 1);
            profileFaceCascade.detectMultiScale(mirrored, mirroredFaces, quality.faceScaleFactor(),
                    Math.max(3, quality.faceMinNeighbors() - 1), 0, FACE_MIN_SIZE, new Size());

            Rect best = null;
            long bestArea = 0L;
            long count = directFaces.size() + mirroredFaces.size();
            for (long index = 0; index < directFaces.size(); index++) {
                Rect candidate = directFaces.get(index);
                long area = (long) candidate.width() * candidate.height();
                if (area > bestArea) {
                    best = copyRect(candidate);
                    bestArea = area;
                }
            }
            for (long index = 0; index < mirroredFaces.size(); index++) {
                Rect mirroredFace = mirroredFaces.get(index);
                Rect candidate = new Rect(
                        Math.max(0, gray.cols() - mirroredFace.x() - mirroredFace.width()),
                        mirroredFace.y(),
                        mirroredFace.width(),
                        mirroredFace.height()
                );
                long area = (long) candidate.width() * candidate.height();
                if (area > bestArea) {
                    best = candidate;
                    bestArea = area;
                }
            }

            return best == null ? ProfileFaceDetection.NONE : new ProfileFaceDetection(best, Math.toIntExact(count));
        } finally {
            mirrored.close();
            mirroredFaces.close();
            directFaces.close();
        }
    }

    private ExpressionEstimate estimateOffAxisFace(ProfileFaceDetection profileFace, double fps) {
        double frontalQuality = 0.18;
        ExpressionCandidate candidate = offAxisFallbackCandidate(System.currentTimeMillis(), frontalQuality);
        smooth(candidate.expression(), candidate.confidence());
        FaceParts parts = fallbackParts(smoothedExpression);
        return new ExpressionEstimate(
                Instant.now(),
                smoothedExpression,
                smoothedConfidence,
                profileFace.faceCount(),
                copyRect(profileFace.face()),
                0,
                0,
                0.0,
                0.0,
                0.0,
                0.0,
                candidate.blinkScore(),
                candidate.winkScore(),
                parts,
                fps,
                frontalQuality
        );
    }

    private FaceParts estimateParts(String expression, ExpressionFeatures features, LandmarkSignals landmarkSignals, boolean landmarkMouthFound) {
        if (EXPRESSION_FUNNY.equals(expression)) {
            return new FaceParts(FaceParts.Mouth.FUNNY, FaceParts.Eye.CLOSED, FaceParts.Eye.OPEN,
                    FaceParts.Eyebrow.RAISED, FaceParts.Eyebrow.RAISED);
        }

        FaceParts.Mouth mouth = estimateMouthPart(features, landmarkMouthFound);
        FaceParts.Eye leftEye;
        FaceParts.Eye rightEye;

        if (EXPRESSION_BLINKING.equals(expression)) {
            leftEye = FaceParts.Eye.CLOSED;
            rightEye = FaceParts.Eye.CLOSED;
        } else if (EXPRESSION_WINKING.equals(expression)) {
            double leftClosed = landmarkSignals.leftEyeClosedScore();
            double rightClosed = landmarkSignals.rightEyeClosedScore();
            if (Math.max(leftClosed, rightClosed) >= 0.35) {
                leftEye = leftClosed >= rightClosed ? FaceParts.Eye.CLOSED : estimateOpenEyePart(expression);
                rightEye = rightClosed > leftClosed ? FaceParts.Eye.CLOSED : estimateOpenEyePart(expression);
            } else {
                leftEye = FaceParts.Eye.CLOSED;
                rightEye = estimateOpenEyePart(expression);
            }
        } else {
            leftEye = estimateEyePart(expression, landmarkSignals.leftEyeClosedScore());
            rightEye = estimateEyePart(expression, landmarkSignals.rightEyeClosedScore());
        }

        FaceParts.Eyebrow leftEyebrow = estimateEyebrowPart(
                expression,
                landmarkSignals.leftBrowRaiseScore(),
                landmarkSignals.leftBrowFurrowScore(),
                landmarkSignals.leftBrowSadScore()
        );
        FaceParts.Eyebrow rightEyebrow = estimateEyebrowPart(
                expression,
                landmarkSignals.rightBrowRaiseScore(),
                landmarkSignals.rightBrowFurrowScore(),
                landmarkSignals.rightBrowSadScore()
        );
        return new FaceParts(mouth, leftEye, rightEye, leftEyebrow, rightEyebrow);
    }

    private static MouthMetrics landmarkPrimaryMouth(MouthMetrics landmarkMouth, MouthMetrics imageMouth, RecognitionQuality quality) {
        double fallbackWeight = quality == RecognitionQuality.ACCURATE ? 0.10 : 0.14;
        MouthMetrics blended = landmarkMouth.blend(imageMouth, fallbackWeight);
        double openFallback = Math.max(0.0, imageMouth.openScore() - landmarkMouth.openScore()) * 0.16;
        double wideFallback = Math.max(0.0, imageMouth.wideScore() - landmarkMouth.wideScore()) * 0.12;
        double smileFallback = Math.max(0.0, imageMouth.smileCurveScore() - landmarkMouth.smileCurveScore()) * 0.10;
        double sadFallback = Math.max(0.0, imageMouth.sadScore() - landmarkMouth.sadScore()) * 0.08;
        return new MouthMetrics(
                clamp(blended.openScore() + openFallback, 0.0, 1.0),
                clamp(blended.wideScore() + wideFallback, 0.0, 1.0),
                clamp(blended.sadScore() + sadFallback, 0.0, 1.0),
                clamp(blended.smileCurveScore() + smileFallback, 0.0, 1.0),
                imageMouth.tongueScore()
        );
    }

    private static FaceParts fallbackParts(String expression) {
        return switch (expression) {
            case EXPRESSION_HAPPY -> new FaceParts(FaceParts.Mouth.HAPPY, FaceParts.Eye.OPEN, FaceParts.Eye.OPEN,
                    FaceParts.Eyebrow.NEUTRAL, FaceParts.Eyebrow.NEUTRAL);
            case EXPRESSION_SAD -> new FaceParts(FaceParts.Mouth.SAD, FaceParts.Eye.OPEN, FaceParts.Eye.OPEN,
                    FaceParts.Eyebrow.SAD, FaceParts.Eyebrow.SAD);
            case EXPRESSION_SURPRISED -> new FaceParts(FaceParts.Mouth.SURPRISED, FaceParts.Eye.OPEN, FaceParts.Eye.OPEN,
                    FaceParts.Eyebrow.RAISED, FaceParts.Eyebrow.RAISED);
            case EXPRESSION_TALKING -> new FaceParts(FaceParts.Mouth.TALKING, FaceParts.Eye.OPEN, FaceParts.Eye.OPEN,
                    FaceParts.Eyebrow.NEUTRAL, FaceParts.Eyebrow.NEUTRAL);
            case EXPRESSION_BLINKING -> new FaceParts(FaceParts.Mouth.NEUTRAL, FaceParts.Eye.CLOSED, FaceParts.Eye.CLOSED,
                    FaceParts.Eyebrow.NEUTRAL, FaceParts.Eyebrow.NEUTRAL);
            case EXPRESSION_WINKING -> new FaceParts(FaceParts.Mouth.HAPPY, FaceParts.Eye.CLOSED, FaceParts.Eye.OPEN,
                    FaceParts.Eyebrow.NEUTRAL, FaceParts.Eyebrow.NEUTRAL);
            case EXPRESSION_FOCUSED -> new FaceParts(FaceParts.Mouth.NEUTRAL, FaceParts.Eye.FOCUSED, FaceParts.Eye.FOCUSED,
                    FaceParts.Eyebrow.FOCUSED, FaceParts.Eyebrow.FOCUSED);
            case EXPRESSION_FUNNY -> new FaceParts(FaceParts.Mouth.FUNNY, FaceParts.Eye.CLOSED, FaceParts.Eye.OPEN,
                    FaceParts.Eyebrow.RAISED, FaceParts.Eyebrow.RAISED);
            default -> FaceParts.DEFAULT;
        };
    }

    private static FaceParts.Mouth estimateMouthPart(ExpressionFeatures features, boolean landmarkMouthFound) {
        double landmarkBoost = landmarkMouthFound ? 0.08 : 0.0;
        double tongueCue = tongueCue(features);
        double happyScore = clamp((features.smileScore() * 1.22)
                + (features.wideScore() * 0.20)
                - (features.openScore() * 0.24)
                - (features.sadScore() * 0.58)
                - (features.mouthActivityScore() * 0.10), 0.0, 1.0);
        double sadScore = clamp((features.sadScore() * 1.30)
                - (features.smileScore() * 0.56)
                - (features.openScore() * 0.28)
                - (features.mouthActivityScore() * 0.16), 0.0, 1.0);
        double surprisedScore = clamp((features.openScore() * 1.18)
                - (features.smileScore() * 0.36)
                - (features.sadScore() * 0.26)
                - (features.mouthActivityScore() * 0.72)
                + landmarkBoost, 0.0, 1.0);
        double talkingScore = clamp((features.mouthActivityScore() * 1.18)
                + (features.openScore() * 0.18)
                + (features.wideScore() * 0.12)
                - (features.smileScore() * 0.18)
                - (features.sadScore() * 0.18)
                - (tongueCue * 0.16), 0.0, 1.0);
        double funnyMouthScore = clamp((tongueCue * 1.22)
                + (features.openScore() * 0.16)
                + (features.wideScore() * 0.10)
                - (features.sadScore() * 0.20), 0.0, 1.0);
        double neutralScore = clamp(0.70
                - (features.smileScore() * 0.96)
                - (features.openScore() * 0.92)
                - (features.sadScore() * 0.80)
                - (tongueCue * 0.72)
                - (features.mouthActivityScore() * 0.70), 0.0, 1.0);

        double minimumActiveScore = landmarkMouthFound ? 0.30 : 0.36;
        FaceParts.Mouth best = FaceParts.Mouth.NEUTRAL;
        double bestScore = neutralScore;
        if (funnyMouthScore >= 0.34) {
            best = FaceParts.Mouth.FUNNY;
            bestScore = funnyMouthScore;
        }
        if (happyScore >= minimumActiveScore && happyScore > bestScore + 0.04) {
            best = FaceParts.Mouth.HAPPY;
            bestScore = happyScore;
        }
        if (sadScore >= minimumActiveScore && sadScore > bestScore + 0.04) {
            best = FaceParts.Mouth.SAD;
            bestScore = sadScore;
        }
        if (surprisedScore >= minimumActiveScore
                && features.openScore() >= (landmarkMouthFound ? 0.34 : 0.42)
                && surprisedScore > bestScore + 0.03) {
            best = FaceParts.Mouth.SURPRISED;
            bestScore = surprisedScore;
        }
        if (talkingScore >= (landmarkMouthFound ? 0.32 : 0.38)
                && features.openScore() < 0.82
                && talkingScore > bestScore + 0.02) {
            best = FaceParts.Mouth.TALKING;
        }
        return best;
    }

    private static double tongueCue(ExpressionFeatures features) {
        double shapeSupport = Math.max(features.openScore(), features.wideScore() * 0.78);
        return features.tongueScore() * clamp((shapeSupport - 0.12) / 0.30, 0.0, 1.0);
    }

    private static FaceParts.Eye estimateEyePart(String expression, double closedScore) {
        if (closedScore >= 0.55) {
            return FaceParts.Eye.CLOSED;
        }
        return estimateOpenEyePart(expression);
    }

    private static FaceParts.Eye estimateOpenEyePart(String expression) {
        return EXPRESSION_FOCUSED.equals(expression) ? FaceParts.Eye.FOCUSED : FaceParts.Eye.OPEN;
    }

    private static FaceParts.Eyebrow estimateEyebrowPart(String expression, double raiseScore, double furrowScore, double sadScore) {
        if (EXPRESSION_FOCUSED.equals(expression)) {
            return FaceParts.Eyebrow.FOCUSED;
        }
        if (EXPRESSION_SAD.equals(expression)) {
            return FaceParts.Eyebrow.SAD;
        }
        if (EXPRESSION_SURPRISED.equals(expression)) {
            return FaceParts.Eyebrow.RAISED;
        }
        if (furrowScore >= 0.42 && furrowScore >= sadScore + 0.08 && furrowScore >= raiseScore + 0.08) {
            return FaceParts.Eyebrow.FOCUSED;
        }
        if (sadScore >= 0.38 && sadScore >= furrowScore + 0.06 && sadScore >= raiseScore + 0.04) {
            return FaceParts.Eyebrow.SAD;
        }
        if (raiseScore >= 0.45 && raiseScore >= sadScore + 0.05) {
            return FaceParts.Eyebrow.RAISED;
        }
        return FaceParts.Eyebrow.NEUTRAL;
    }

    private TrackingQuality estimateTrackingQuality(Mat gray, Rect face, long eyeCount, boolean landmarkFound, LandmarkSignals landmarkSignals) {
        double faceWidthRatio = (double) face.width() / Math.max(1, gray.cols());
        double faceHeightRatio = (double) face.height() / Math.max(1, gray.rows());
        double faceSizeScore = clamp((Math.min(faceWidthRatio, faceHeightRatio) - 0.075) / 0.16, 0.0, 1.0);
        double aspectRatio = (double) face.width() / Math.max(1, face.height());
        double aspectScore = clamp(1.0 - (Math.abs(aspectRatio - 0.78) / 0.42), 0.0, 1.0);
        double eyeScore = eyeCount >= 2 ? 1.0 : eyeCount == 1 ? 0.45 : 0.08;
        double poseScore = landmarkFound ? 1.0 - (landmarkSignals.headYawScore() * 0.95) : 0.58;
        double centerX = (face.x() + face.width() / 2.0) / Math.max(1, gray.cols());
        double centerScore = clamp(1.0 - Math.abs(centerX - 0.5) * 1.35, 0.25, 1.0);

        double frontalQuality = clamp(
                (eyeScore * 0.34)
                        + (poseScore * 0.28)
                        + (aspectScore * 0.18)
                        + (faceSizeScore * 0.12)
                        + (centerScore * 0.08),
                0.0,
                1.0
        );
        return new TrackingQuality(frontalQuality, frontalQuality >= offAxisOptions.minFrontalQuality());
    }

    private ExpressionCandidate applyOffAxisPolicy(ExpressionCandidate candidate, double frontalQuality, long nowMillis) {
        if (frontalQuality >= offAxisOptions.minFrontalQuality()) {
            return candidate;
        }

        boolean highRisk = EXPRESSION_HAPPY.equals(candidate.expression())
                || EXPRESSION_FUNNY.equals(candidate.expression())
                || EXPRESSION_SAD.equals(candidate.expression())
                || EXPRESSION_SURPRISED.equals(candidate.expression())
                || EXPRESSION_TALKING.equals(candidate.expression())
                || EXPRESSION_BLINKING.equals(candidate.expression())
                || EXPRESSION_WINKING.equals(candidate.expression());
        if (!highRisk) {
            return new ExpressionCandidate(candidate.expression(), candidate.confidence() * offAxisConfidenceScale(frontalQuality),
                    candidate.blinkScore(), candidate.winkScore());
        }

        double qualityDeficit = clamp((offAxisOptions.minFrontalQuality() - frontalQuality)
                / Math.max(0.05, offAxisOptions.minFrontalQuality()), 0.0, 1.0);
        double strongConfidence = 0.82 + qualityDeficit * 0.10;
        if (candidate.confidence() >= strongConfidence && frontalQuality >= offAxisOptions.minFrontalQuality() * 0.65) {
            return new ExpressionCandidate(candidate.expression(), candidate.confidence() * offAxisConfidenceScale(frontalQuality),
                    candidate.blinkScore(), candidate.winkScore());
        }

        return offAxisFallbackCandidate(nowMillis, frontalQuality);
    }

    private double offAxisConfidenceScale(double frontalQuality) {
        return clamp(0.45 + (frontalQuality / Math.max(0.05, offAxisOptions.minFrontalQuality())) * 0.35, 0.35, 0.85);
    }

    private ExpressionCandidate offAxisFallbackCandidate(long nowMillis, double frontalQuality) {
        if (offAxisOptions.mode() == OffAxisMode.HOLD_LAST && holdCandidateAvailable(nowMillis)) {
            long age = Math.max(0L, nowMillis - lastStableAtMillis);
            double ageRatio = offAxisOptions.holdMillis() <= 0 ? 1.0 : (double) age / Math.max(1, offAxisOptions.holdMillis());
            double confidence = clamp(lastStableConfidence * (1.0 - ageRatio * 0.62), 0.16, 0.82);
            return new ExpressionCandidate(lastStableExpression, confidence, 0.0, 0.0);
        }
        if (offAxisOptions.mode() == OffAxisMode.NO_FACE) {
            return new ExpressionCandidate(EXPRESSION_NO_FACE, 0.0, 0.0, 0.0);
        }
        return new ExpressionCandidate(EXPRESSION_NEUTRAL, clamp(0.24 + frontalQuality * 0.36, 0.24, 0.48), 0.0, 0.0);
    }

    private boolean holdCandidateAvailable(long nowMillis) {
        return offAxisOptions.holdMillis() > 0
                && isHoldableExpression(lastStableExpression)
                && lastStableConfidence >= 0.35
                && nowMillis - lastStableAtMillis <= offAxisOptions.holdMillis();
    }

    private void rememberStableExpression(ExpressionCandidate candidate, double frontalQuality) {
        if (frontalQuality < offAxisOptions.minFrontalQuality()
                || candidate.confidence() < 0.45
                || !isHoldableExpression(candidate.expression())) {
            return;
        }

        lastStableExpression = candidate.expression();
        lastStableConfidence = candidate.confidence();
        lastStableAtMillis = System.currentTimeMillis();
    }

    private static boolean isHoldableExpression(String expression) {
        return EXPRESSION_NEUTRAL.equals(expression)
                || EXPRESSION_HAPPY.equals(expression)
                || EXPRESSION_FUNNY.equals(expression)
                || EXPRESSION_SAD.equals(expression)
                || EXPRESSION_SURPRISED.equals(expression)
                || EXPRESSION_TALKING.equals(expression)
                || EXPRESSION_FOCUSED.equals(expression);
    }

    private void updateEyeHistory(long eyeCount) {
        if (eyeCount > 0) {
            framesSinceEyesSeen = 0;
        } else {
            framesSinceEyesSeen++;
        }

        if (eyeCount >= 2) {
            framesSinceTwoEyesSeen = 0;
            consecutiveTwoEyeFrames++;
            consecutiveOneEyeFrames = 0;
            consecutiveNoEyeFrames = 0;
            lastStableTwoEyeFrames = Math.max(lastStableTwoEyeFrames, consecutiveTwoEyeFrames);
        } else if (eyeCount == 1) {
            framesSinceTwoEyesSeen++;
            consecutiveOneEyeFrames++;
            consecutiveTwoEyeFrames = 0;
            consecutiveNoEyeFrames = 0;
        } else {
            framesSinceTwoEyesSeen++;
            consecutiveNoEyeFrames++;
            consecutiveTwoEyeFrames = 0;
            consecutiveOneEyeFrames = 0;
        }
    }

    private EyeDetections detectIndividualEyes(RectVector eyes, Rect face, Rect eyeArea) {
        double leftOpenConfidence = 0.0;
        double rightOpenConfidence = 0.0;

        for (long index = 0; index < eyes.size(); index++) {
            Rect eye = eyes.get(index);
            double centerX = (eye.x() + eye.width() / 2.0) / Math.max(1, face.width());
            double centerY = (eyeArea.y() + eye.y() + eye.height() / 2.0) / Math.max(1, face.height());
            double widthRatio = (double) eye.width() / Math.max(1, face.width());
            double heightRatio = (double) eye.height() / Math.max(1, face.height());

            if (centerX < 0.16 || centerX > 0.84 || centerY < 0.14 || centerY > 0.58) {
                continue;
            }
            if (widthRatio < 0.045 || widthRatio > 0.28 || heightRatio < 0.03 || heightRatio > 0.20) {
                continue;
            }

            double sizeScore = clamp(((widthRatio - 0.045) / 0.12) + ((heightRatio - 0.03) / 0.14), 0.0, 1.0);
            double verticalScore = clamp(1.0 - Math.abs(centerY - 0.32) * 3.0, 0.0, 1.0);
            double centerScore = clamp(1.0 - Math.abs(centerX - (centerX < 0.50 ? 0.34 : 0.66)) * 3.0, 0.0, 1.0);
            double confidence = clamp((sizeScore * 0.46) + (verticalScore * 0.28) + (centerScore * 0.26), 0.0, 1.0);
            if (centerX < 0.50) {
                leftOpenConfidence = Math.max(leftOpenConfidence, confidence);
            } else {
                rightOpenConfidence = Math.max(rightOpenConfidence, confidence);
            }
        }

        return new EyeDetections(leftOpenConfidence, rightOpenConfidence);
    }

    private LandmarkSignals cascadeEyeSignals(EyeDetections eyes) {
        boolean recentlyStableEyes = lastStableTwoEyeFrames >= 2 && framesSinceTwoEyesSeen <= 4;
        double leftClosed = 0.0;
        double rightClosed = 0.0;
        if (!eyes.leftVisible() && eyes.rightVisible()) {
            leftClosed = 0.72;
        }
        if (!eyes.rightVisible() && eyes.leftVisible()) {
            rightClosed = 0.72;
        }
        if (!eyes.leftVisible() && !eyes.rightVisible() && recentlyStableEyes) {
            double blinkConfidence = consecutiveNoEyeFrames <= 2 ? 0.76 : 0.60;
            leftClosed = blinkConfidence;
            rightClosed = blinkConfidence;
        }
        double leftOpen = eyes.leftOpenScore();
        double rightOpen = eyes.rightOpenScore();
        double blinkScore = Math.min(leftClosed, rightClosed);
        double winkScore = Math.max(leftClosed * rightOpen, rightClosed * leftOpen);
        return new LandmarkSignals(
                blinkScore,
                winkScore,
                leftClosed,
                rightClosed,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0
        );
    }

    private void updateNeutralBaseline(double rawSmileScore, MouthMetrics rawMouth, double frontalQuality) {
        if (!EXPRESSION_NEUTRAL.equals(calibrationExpression) || calibrationFrames.get() <= 0) {
            return;
        }
        if (frontalQuality < offAxisOptions.minFrontalQuality()) {
            return;
        }

        neutralBaseline.add(rawSmileScore, rawMouth);
    }

    private boolean updateExpressionCalibration(ExpressionFeatures features, ExpressionScores scores, double frontalQuality) {
        int remaining = calibrationFrames.get();
        if (remaining <= 0) {
            calibrationStatus = readyCalibrationStatus();
            return false;
        }

        String expression = calibrationExpression;
        if (frontalQuality < offAxisOptions.minFrontalQuality()) {
            calibrationStatus = "Face camera to calibrate " + expression.toLowerCase(Locale.ROOT);
            return true;
        }

        expressionCalibrations.add(expression, features, scores);
        int left = calibrationFrames.updateAndGet(value -> Math.max(0, value - 1));
        if (left > 0) {
            int total = expressionCalibrations.targetFrames(expression);
            int completed = Math.max(0, total - left);
            int percent = (int) Math.round((completed * 100.0) / Math.max(1, total));
            calibrationStatus = "Hold " + expression.toLowerCase(Locale.ROOT) + " expression " + percent + "%";
        } else {
            filteredSmileScore = 0.0;
            filteredMouthOpenScore = 0.0;
            filteredMouthWideScore = 0.0;
            filteredSadScore = 0.0;
            previousRawMouthOpenScore = 0.0;
            previousRawMouthWideScore = 0.0;
            mouthActivityScore = 0.0;
            mouthOpenHistory.clear();
            mouthWideHistory.clear();
            calibrationStatus = expression + " calibrated (" + expressionCalibrations.readyCount()
                    + "/" + CALIBRATABLE_EXPRESSIONS.length + ")";
            calibrationRevision++;
        }
        return true;
    }

    private String readyCalibrationStatus() {
        if (!neutralBaseline.ready()) {
            return "Learning neutral baseline";
        }

        int ready = expressionCalibrations.readyCount();
        if (ready >= CALIBRATABLE_EXPRESSIONS.length) {
            return "All expressions calibrated";
        }
        return "Calibrated " + ready + "/" + CALIBRATABLE_EXPRESSIONS.length + " expressions";
    }

    private double smoothSmile(double score) {
        filteredSmileScore = smoothMetric(filteredSmileScore, score, 0.42, 0.26);
        return filteredSmileScore;
    }

    private MouthMetrics normalizeMouth(MouthMetrics rawMouth, CalibrationSnapshot baseline) {
        double openScore = normalizeActivation(rawMouth.openScore(), baseline.openScore(), 0.018, 1.55);
        double wideScore = normalizeActivation(rawMouth.wideScore(), baseline.wideScore(), 0.024, 1.30);
        double sadScore = normalizeActivation(rawMouth.sadScore(), baseline.sadScore(), 0.006, 2.05);
        updateMouthActivity(openScore, wideScore);

        filteredMouthOpenScore = smoothMetric(filteredMouthOpenScore, openScore, 0.48, 0.30);
        filteredMouthWideScore = smoothMetric(filteredMouthWideScore, wideScore, 0.42, 0.28);
        filteredSadScore = smoothMetric(filteredSadScore, sadScore, 0.52, 0.20);

        return new MouthMetrics(filteredMouthOpenScore, filteredMouthWideScore, filteredSadScore,
                rawMouth.smileCurveScore(), rawMouth.tongueScore());
    }

    private void updateMouthActivity(double rawOpenScore, double rawWideScore) {
        boolean hasMouthHistory = mouthOpenHistory.hasSamples();
        double openDelta = hasMouthHistory ? Math.max(0.0, Math.abs(rawOpenScore - previousRawMouthOpenScore) - 0.035) : 0.0;
        double wideDelta = hasMouthHistory ? Math.max(0.0, Math.abs(rawWideScore - previousRawMouthWideScore) - 0.030) : 0.0;
        previousRawMouthOpenScore = rawOpenScore;
        previousRawMouthWideScore = rawWideScore;

        mouthOpenHistory.add(rawOpenScore);
        mouthWideHistory.add(rawWideScore);

        double openVariation = Math.max(0.0, mouthOpenHistory.standardDeviation() - 0.025);
        double wideVariation = Math.max(0.0, mouthWideHistory.standardDeviation() - 0.020);
        double frameActivity = clamp((openDelta * 4.4) + (wideDelta * 2.6), 0.0, 1.0);
        double sustainedActivity = clamp((openVariation * 5.0) + (wideVariation * 3.0), 0.0, 1.0);
        double activity = Math.max(frameActivity, sustainedActivity);
        mouthActivityScore = smoothMetric(mouthActivityScore, activity, 0.70, 0.22);
    }

    private static double normalizeActivation(double rawScore, double baseline, double deadZone, double gain) {
        double denominator = Math.max(0.35, 1.0 - baseline);
        return clamp(((rawScore - baseline - deadZone) / denominator) * gain, 0.0, 1.0);
    }

    private static double smoothMetric(double previous, double current, double riseWeight, double fallWeight) {
        double weight = current > previous ? riseWeight : fallWeight;
        return (previous * (1.0 - weight)) + (current * weight);
    }

    private static double scoreSmiles(RectVector smiles, Rect face, Rect smileArea) {
        double score = 0.0;

        for (long index = 0; index < smiles.size(); index++) {
            Rect smile = smiles.get(index);
            double centerX = (smileArea.x() + smile.x() + smile.width() / 2.0) / Math.max(1, face.width());
            double centerY = (smileArea.y() + smile.y() + smile.height() / 2.0) / Math.max(1, face.height());
            double widthRatio = (double) smile.width() / Math.max(1, face.width());
            double heightRatio = (double) smile.height() / Math.max(1, face.height());
            double centerBias = clamp(1.0 - Math.abs(centerX - 0.5) * 1.6, 0.35, 1.0);

            if (centerY < 0.55 || centerY > 0.93 || centerX < 0.14 || centerX > 0.86) {
                continue;
            }

            score = Math.max(score, (((widthRatio - 0.12) * 1.95) + ((heightRatio - 0.04) * 0.65)) * centerBias);
        }

        return clamp(score, 0.0, 1.0);
    }

    private static MouthMetrics analyzeMouth(Mat lowerFace, Rect face, RecognitionQuality quality) {
        Mat blurred = new Mat();
        Mat thresholded = new Mat();
        Mat adaptive = new Mat();

        try {
            GaussianBlur(lowerFace, blurred, new Size(5, 5), 0.0, 0.0, BORDER_DEFAULT, 0);
            threshold(blurred, thresholded, 0.0, 255.0, THRESH_BINARY_INV | THRESH_OTSU);

            MouthMetrics otsuMetrics = analyzeMouthContours(thresholded, lowerFace, face);
            if (!quality.adaptiveMouthThresholdEnabled()) {
                return otsuMetrics;
            }

            int blockSize = Math.max(11, ((lowerFace.cols() / 8) | 1));
            adaptiveThreshold(blurred, adaptive, 255.0, ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY_INV, blockSize, 5.0);
            MouthMetrics adaptiveMetrics = analyzeMouthContours(adaptive, lowerFace, face);
            return otsuMetrics.max(adaptiveMetrics);
        } finally {
            adaptive.close();
            thresholded.close();
            blurred.close();
        }
    }

    private static MouthMetrics analyzeMouthContours(Mat thresholded, Mat lowerFace, Rect face) {
        Mat contourInput = thresholded.clone();
        MatVector contours = new MatVector();

        try {
            findContours(contourInput, contours, org.bytedeco.opencv.global.opencv_imgproc.RETR_EXTERNAL,
                    org.bytedeco.opencv.global.opencv_imgproc.CHAIN_APPROX_SIMPLE);

            double openScore = 0.0;
            double wideScore = 0.0;
            for (long index = 0; index < contours.size(); index++) {
                Mat contour = contours.get(index);
                double area = contourArea(contour);
                Rect bounds = boundingRect(contour);
                double centerX = (bounds.x() + bounds.width() / 2.0) / Math.max(1, lowerFace.cols());
                double centerY = (bounds.y() + bounds.height() / 2.0) / Math.max(1, lowerFace.rows());
                double verticalRatio = (double) bounds.height() / Math.max(1, face.height());
                double widthRatio = (double) bounds.width() / Math.max(1, face.width());
                double areaRatio = area / Math.max(1.0, (double) face.width() * face.height());
                double widthToHeight = (double) bounds.width() / Math.max(1, bounds.height());
                double fillRatio = area / Math.max(1.0, (double) bounds.width() * bounds.height());
                double centerBias = clamp(1.0 - Math.abs(centerX - 0.5) * 1.45, 0.25, 1.0);

                boolean centeredInMouthBand = centerX > 0.12 && centerX < 0.88 && centerY > 0.20 && centerY < 0.86;
                boolean plausibleMouthShape = areaRatio > 0.0014
                        && areaRatio < 0.045
                        && widthRatio < 0.62
                        && verticalRatio < 0.24
                        && fillRatio > 0.08
                        && fillRatio < 0.92
                        && bounds.x() > 0
                        && bounds.x() + bounds.width() < lowerFace.cols();
                if (centeredInMouthBand && plausibleMouthShape) {
                    double openCandidate = (((verticalRatio - 0.055) * 5.0) + (areaRatio * 7.0)) * centerBias;
                    double wideCandidate = (((widthRatio - 0.16) * 2.5) - (verticalRatio * 0.95) + (areaRatio * 2.2)) * centerBias;

                    if (widthRatio > 0.13 && widthToHeight > 0.9) {
                        openScore = Math.max(openScore, openCandidate);
                    }
                    if (widthToHeight >= 1.8 && widthRatio > 0.22) {
                        wideScore = Math.max(wideScore, wideCandidate);
                    }
                }
            }

            openScore = clamp(openScore, 0.0, 1.0);
            wideScore = clamp(wideScore, 0.0, 1.0);
            CurveMetrics curve = estimateCurveScores(thresholded, face, openScore, wideScore);
            return new MouthMetrics(openScore, wideScore, curve.sadScore(), curve.smileScore(), 0.0);
        } finally {
            contours.close();
            contourInput.close();
        }
    }

    private static CurveMetrics estimateCurveScores(Mat thresholded, Rect face, double openScore, double wideScore) {
        int width = thresholded.cols();
        int height = thresholded.rows();
        if (width < 24 || height < 18) {
            return CurveMetrics.NONE;
        }

        int sampleY = Math.max(0, height * 3 / 10);
        int sampleHeight = Math.max(1, Math.min(height - sampleY, height / 2));
        int thirdWidth = Math.max(1, width / 3);

        Rect leftRect = new Rect(0, sampleY, thirdWidth, sampleHeight);
        Rect centerRect = new Rect(thirdWidth, sampleY, Math.max(1, width - thirdWidth * 2), sampleHeight);
        Rect rightRect = new Rect(width - thirdWidth, sampleY, thirdWidth, sampleHeight);

        Mat left = new Mat(thresholded, leftRect);
        Mat center = new Mat(thresholded, centerRect);
        Mat right = new Mat(thresholded, rightRect);

        try {
            int leftPixels = countNonZero(left);
            int centerPixels = countNonZero(center);
            int rightPixels = countNonZero(right);
            int sidePixels = leftPixels + rightPixels;
            int minimumPixels = Math.max(5, face.width() * face.height() / 2600);

            if (centerPixels < minimumPixels || sidePixels < minimumPixels) {
                return CurveMetrics.NONE;
            }

            double leftY = weightedCenterY(left, sampleY);
            double centerY = weightedCenterY(center, sampleY);
            double rightY = weightedCenterY(right, sampleY);
            double sideY = (leftY + rightY) / 2.0;
            double downturn = (sideY - centerY) / Math.max(1.0, sampleHeight);
            double upturn = (centerY - sideY) / Math.max(1.0, sampleHeight);
            double symmetry = 1.0 - clamp(Math.abs(leftY - rightY) / Math.max(1.0, sampleHeight), 0.0, 1.0);
            double sideBalance = clamp((double) Math.min(leftPixels, rightPixels) / Math.max(1, Math.max(leftPixels, rightPixels)), 0.0, 1.0);
            double mouthClosed = 1.0 - clamp((openScore * 0.70) + (wideScore * 0.30), 0.0, 1.0);
            double smileOpenness = 1.0 - clamp(openScore * 0.50, 0.0, 0.72);

            double sadScore = clamp((downturn - 0.018) * 8.4 * symmetry * sideBalance * mouthClosed, 0.0, 1.0);
            double smileScore = clamp((upturn - 0.022) * 6.6 * symmetry * sideBalance * smileOpenness, 0.0, 1.0);
            return new CurveMetrics(smileScore, sadScore);
        } finally {
            right.close();
            center.close();
            left.close();
        }
    }

    private static double weightedCenterY(Mat binaryRegion, int offsetY) {
        Moments regionMoments = moments(binaryRegion, true);
        if (regionMoments.m00() <= 0.0) {
            return offsetY;
        }
        return offsetY + (regionMoments.m01() / regionMoments.m00());
    }

    private ExpressionScores scoreExpressions(ExpressionFeatures features) {
        double smileScore = features.smileScore();
        double mouthOpenScore = features.openScore();
        double tongueCue = tongueCue(features);
        int eyeCount = features.bothEyesScore() >= 0.5 ? 2 : features.oneEyeScore() >= 0.5 ? 1 : 0;
        double browRaiseScore = features.browRaiseScore();
        double browFurrowScore = features.browFurrowScore();
        double headYawScore = features.headYawScore();

        double eyeStability = eyeCount >= 2 ? 0.16 : eyeCount == 1 ? 0.08 : 0.0;
        boolean recentlyStableEyes = lastStableTwoEyeFrames >= 2 && framesSinceTwoEyesSeen <= 4;
        double historyBlinkScore = eyeCount == 0 && recentlyStableEyes && consecutiveNoEyeFrames <= 3
                ? clamp(0.72 + (3 - consecutiveNoEyeFrames) * 0.06, 0.0, 1.0)
                : 0.0;
        double historyWinkScore = eyeCount == 1 && recentlyStableEyes && consecutiveOneEyeFrames <= 6
                ? clamp(0.64 + (6 - consecutiveOneEyeFrames) * 0.035, 0.0, 1.0)
                : 0.0;
        double blinkScore = Math.max(historyBlinkScore, features.blinkShapeScore());
        double winkScore = Math.max(historyWinkScore, features.winkShapeScore());
        double poseReliability = 1.0 - (headYawScore * 0.18);
        double happyScore = clamp(((smileScore * 1.16) + (features.wideScore() * 0.12)
                - (mouthOpenScore * 0.22) - (features.sadScore() * 0.36)
                - (features.mouthActivityScore() * 0.08) - (browFurrowScore * 0.14)) * poseReliability, 0.0, 1.0);
        double funnyCue = Math.max(winkScore * 0.95,
                Math.max(tongueCue * 1.12,
                        Math.max(browRaiseScore * 0.70, Math.min(features.wideScore(), mouthOpenScore) * 0.70)));
        double funnyScore = clamp(((smileScore * 0.74) + (features.wideScore() * 0.18)
                + (funnyCue * 0.44) + (tongueCue * 0.78) + (mouthOpenScore * 0.06) - (features.sadScore() * 0.34)
                - (features.mouthActivityScore() * 0.08) - (browFurrowScore * 0.12)) * poseReliability, 0.0, 1.0);
        double sadScore = clamp(((features.sadScore() * 1.32) + (browFurrowScore * 0.24)
                + (eyeCount > 0 ? 0.05 : 0.0) - (smileScore * 0.62)
                - (mouthOpenScore * 0.24) - (features.mouthActivityScore() * 0.30)) * poseReliability, 0.0, 1.0);
        double surprisedScore = clamp(((mouthOpenScore * 1.18) + (browRaiseScore * 0.30)
                - (smileScore * 0.42) - (features.mouthActivityScore() * 0.62)
                - (features.sadScore() * 0.16) - (tongueCue * 0.24) + eyeStability) * poseReliability, 0.0, 1.0);
        double speechActivity = clamp((features.mouthActivityScore() - 0.12) / 0.45, 0.0, 1.0);
        double speechShapeSupport = speechActivity * ((mouthOpenScore * 0.22) + (features.wideScore() * 0.14));
        double talkingScore = clamp(((speechActivity * 0.98) + speechShapeSupport - (smileScore * 0.22)
                - (features.sadScore() * 0.24) - (tongueCue * 0.18) - (browRaiseScore * 0.08)) * poseReliability, 0.0, 1.0);
        double neutralScore = clamp(0.82 + eyeStability - (smileScore * 1.20)
                - (mouthOpenScore * 0.98) - (features.sadScore() * 0.64)
                - (tongueCue * 0.68) - (features.mouthActivityScore() * 0.40) - (browRaiseScore * 0.16)
                - (browFurrowScore * 0.12) - (headYawScore * 0.10), 0.0, 1.0);
        double focusedScore = clamp(0.50 + (eyeCount > 0 ? 0.18 : 0.0)
                + (browFurrowScore * 0.26) + (headYawScore * 0.16)
                - (smileScore * 0.64) - (mouthOpenScore * 0.52)
                - (features.sadScore() * 0.32) - (browRaiseScore * 0.12)
                - (features.mouthActivityScore() * 0.08), 0.0, 1.0);

        return new ExpressionScores(
                neutralScore,
                happyScore,
                funnyScore,
                sadScore,
                surprisedScore,
                talkingScore,
                blinkScore,
                winkScore,
                focusedScore
        );
    }

    private ExpressionCandidate classify(ExpressionScores scores, double mouthOpenScore, double mouthActivityScore) {
        double blinkScore = scores.blinkingScore();
        double winkScore = scores.winkingScore();
        double happyScore = scores.happyScore();
        double funnyScore = scores.funnyScore();
        double sadScore = scores.sadScore();
        double surprisedScore = scores.surprisedScore();
        double talkingScore = scores.talkingScore();
        double neutralScore = scores.neutralScore();
        double focusedScore = scores.focusedScore();

        if (blinkScore >= 0.70) {
            return new ExpressionCandidate(EXPRESSION_BLINKING, blinkScore, blinkScore, winkScore);
        }
        if (funnyScore >= 0.52
                && funnyScore >= happyScore + (expressionCalibrations.ready(EXPRESSION_FUNNY) ? -0.03 : 0.02)
                && funnyScore >= sadScore + 0.10
                && funnyScore >= talkingScore + 0.04
                && funnyScore >= surprisedScore - 0.02) {
            return new ExpressionCandidate(EXPRESSION_FUNNY, Math.max(0.46, funnyScore), blinkScore, winkScore);
        }
        if (winkScore >= 0.66) {
            return new ExpressionCandidate(EXPRESSION_WINKING, winkScore, blinkScore, winkScore);
        }
        if (happyScore >= 0.50
                && happyScore >= sadScore + 0.10
                && happyScore >= surprisedScore - 0.04
                && happyScore >= talkingScore + 0.02) {
            return new ExpressionCandidate(EXPRESSION_HAPPY, happyScore, blinkScore, winkScore);
        }
        if (sadScore >= 0.34
                && (expressionCalibrations.ready(EXPRESSION_SAD) || mouthOpenScore < 0.50)
                && sadScore >= happyScore + 0.05
                && sadScore >= talkingScore - 0.04) {
            return new ExpressionCandidate(EXPRESSION_SAD, Math.max(0.42, sadScore), blinkScore, winkScore);
        }
        double surprisedActivityLimit = expressionCalibrations.ready(EXPRESSION_SURPRISED) ? 0.34 : 0.24;
        if (surprisedScore >= 0.50
                && (expressionCalibrations.ready(EXPRESSION_SURPRISED) || mouthOpenScore >= 0.34)
                && mouthActivityScore < surprisedActivityLimit
                && surprisedScore >= talkingScore + 0.08) {
            return new ExpressionCandidate(EXPRESSION_SURPRISED, surprisedScore, blinkScore, winkScore);
        }
        double minimumTalkingActivity = expressionCalibrations.ready(EXPRESSION_TALKING) ? 0.12 : 0.16;
        boolean speechLikeShape = mouthOpenScore < 0.72 || (mouthActivityScore >= 0.30 && mouthOpenScore < 0.88);
        if (talkingScore >= 0.40
                && mouthActivityScore >= minimumTalkingActivity
                && speechLikeShape
                && talkingScore >= neutralScore - 0.02
                && talkingScore >= focusedScore + 0.02) {
            return new ExpressionCandidate(EXPRESSION_TALKING, Math.max(0.42, talkingScore), blinkScore, winkScore);
        }
        if (neutralScore >= focusedScore) {
            return new ExpressionCandidate(EXPRESSION_NEUTRAL, Math.max(0.38, neutralScore), blinkScore, winkScore);
        }
        return new ExpressionCandidate(EXPRESSION_FOCUSED, Math.max(0.36, focusedScore), blinkScore, winkScore);
    }

    private void smooth(String expression, double confidence) {
        if (expression.equals(smoothedExpression)) {
            pendingFrames = 0;
            smoothedConfidence = (smoothedConfidence * 0.55) + (confidence * 0.45);
            return;
        }

        if (expression.equals(pendingExpression)) {
            pendingFrames++;
        } else {
            pendingExpression = expression;
            pendingFrames = 1;
        }

        boolean quickGesture = EXPRESSION_BLINKING.equals(expression) || EXPRESSION_WINKING.equals(expression);
        boolean speechOrSad = EXPRESSION_TALKING.equals(expression) || EXPRESSION_SAD.equals(expression);
        if (quickGesture || confidence >= 0.62 || pendingFrames >= 3 || (speechOrSad && pendingFrames >= 2) || smoothedConfidence < 0.32) {
            smoothedExpression = expression;
            smoothedConfidence = confidence;
            pendingFrames = 0;
        } else {
            smoothedConfidence = Math.max(0.0, smoothedConfidence * 0.88);
        }
    }

    private static void annotate(Mat frame, ExpressionEstimate estimate) {
        if (estimate.faceCount() <= 0) {
            drawLabel(frame, EXPRESSION_NO_FACE, 18, 34, ALERT_COLOR);
            return;
        }

        Rect face = estimate.face();
        Scalar accent = colorFor(estimate.expression());
        rectangle(frame, face, accent, 2, LINE_8, 0);

        String confidence = String.format(Locale.US, "%.0f%%", estimate.confidence() * 100.0);
        drawLabel(frame, estimate.expression() + " " + confidence, face.x(), Math.max(28, face.y() - 10), accent);
    }

    private static void drawLabel(Mat frame, String text, int x, int y, Scalar accent) {
        int safeX = Math.max(0, Math.min(frame.cols() - 1, x));
        int baseline = Math.max(0, y - 24);
        int width = Math.min(frame.cols() - safeX, Math.max(150, text.length() * 12));
        Rect background = new Rect(
                safeX,
                Math.max(0, baseline),
                Math.max(1, width),
                30
        );
        rectangle(frame, background, TEXT_BACKDROP, -1, LINE_8, 0);
        putText(frame, text, new Point(Math.max(6, safeX + 8), Math.max(22, y)), FONT_HERSHEY_SIMPLEX, 0.7, accent, 2, LINE_AA, false);
    }

    private static Scalar colorFor(String expression) {
        return switch (expression) {
            case EXPRESSION_HAPPY -> HAPPY_COLOR;
            case EXPRESSION_FUNNY -> FUNNY_COLOR;
            case EXPRESSION_SAD -> SAD_COLOR;
            case EXPRESSION_SURPRISED -> SURPRISED_COLOR;
            case EXPRESSION_TALKING -> TALKING_COLOR;
            case EXPRESSION_BLINKING, EXPRESSION_WINKING -> EYE_GESTURE_COLOR;
            default -> FACE_COLOR;
        };
    }

    private static CascadeClassifier loadCascade(String resourceName) {
        CascadeClassifier classifier = new CascadeClassifier(ResourceExtractor.extractCascade(resourceName).toString());
        if (classifier.empty()) {
            throw new IllegalStateException("OpenCV could not load cascade: " + resourceName);
        }
        return classifier;
    }

    private static CascadeClassifier loadCascadeOptional(String resourceName) {
        try {
            return loadCascade(resourceName);
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    private static Rect copyRect(Rect rect) {
        return new Rect(rect.x(), rect.y(), rect.width(), rect.height());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeExpression(String expression) {
        if (expression == null) {
            return null;
        }

        String normalized = expression.trim();
        for (String candidate : CALIBRATABLE_EXPRESSIONS) {
            if (candidate.equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
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

    private static final class ExpressionTemporalFilter {
        private final double currentWeight;
        private ExpressionScores rollingScores;
        private int samples;

        private ExpressionTemporalFilter(int windowSize) {
            currentWeight = 2.0 / (Math.max(1, windowSize) + 1.0);
        }

        void reset() {
            rollingScores = null;
            samples = 0;
        }

        ExpressionCandidate vote(ExpressionCandidate candidate, ExpressionScores scores) {
            if (rollingScores == null) {
                rollingScores = scores;
            } else {
                rollingScores = rollingScores.blend(scores, currentWeight);
            }
            samples++;

            boolean quickGesture = EXPRESSION_BLINKING.equals(candidate.expression())
                    || EXPRESSION_WINKING.equals(candidate.expression());
            if (quickGesture || samples < 3) {
                return candidate;
            }

            String votedExpression = rollingScores.bestSustainedExpression();
            double votedScore = rollingScores.score(votedExpression);
            if (votedExpression.equals(candidate.expression())) {
                return new ExpressionCandidate(candidate.expression(), Math.max(candidate.confidence(), votedScore),
                        scores.blinkingScore(), scores.winkingScore());
            }

            double candidateRollingScore = rollingScores.score(candidate.expression());
            if (votedScore >= Math.max(0.36, candidateRollingScore + 0.05)) {
                return new ExpressionCandidate(votedExpression, Math.max(candidate.confidence() * 0.82, votedScore),
                        scores.blinkingScore(), scores.winkingScore());
            }
            return candidate;
        }
    }

    private static final class LandmarkRefiner {
        private final Facemark facemark;

        private LandmarkRefiner(Facemark facemark) {
            this.facemark = facemark;
        }

        static LandmarkRefiner create(RecognitionQuality quality) {
            if (!quality.landmarkRefinementEnabled()) {
                return new LandmarkRefiner(null);
            }

            String configuredPath = System.getProperty("facetrack.landmark.model", "").trim();
            if (configuredPath.isEmpty()) {
                return new LandmarkRefiner(null);
            }

            Path modelPath = Path.of(configuredPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(modelPath)) {
                return new LandmarkRefiner(null);
            }

            try {
                Facemark loadedFacemark = createFacemarkLBF();
                loadedFacemark.loadModel(modelPath.toString());
                return new LandmarkRefiner(loadedFacemark);
            } catch (RuntimeException exception) {
                return new LandmarkRefiner(null);
            }
        }

        LandmarkEstimate estimate(Mat gray, Rect face) {
            if (facemark == null) {
                return null;
            }

            RectVector faceBox = new RectVector(copyRect(face));
            Point2fVectorVector landmarks = new Point2fVectorVector();
            try {
                if (!facemark.fit(gray, faceBox, landmarks) || landmarks.empty() || landmarks.size() == 0) {
                    return null;
                }

                Point2fVector points = landmarks.get(0);
                if (points.size() < 68) {
                    return null;
                }

                EyeLandmarkMetrics eyeMetrics = landmarkEyes(points);
                return new LandmarkEstimate(landmarkMouth(points, face), eyeMetrics.eyeCount(),
                        landmarkSignals(points, face, eyeMetrics));
            } catch (RuntimeException exception) {
                return null;
            } finally {
                landmarks.close();
                faceBox.close();
            }
        }

        private static MouthMetrics landmarkMouth(Point2fVector points, Rect face) {
            double faceWidth = Math.max(1.0, face.width());
            double faceHeight = Math.max(1.0, face.height());

            Point2f leftCorner = points.get(48);
            Point2f rightCorner = points.get(54);
            Point2f upperInner = points.get(62);
            Point2f lowerInner = points.get(66);
            Point2f upperOuter = points.get(51);
            Point2f lowerOuter = points.get(57);

            double mouthWidth = distance(leftCorner, rightCorner) / faceWidth;
            double innerHeight = ((distance(points.get(61), points.get(67))
                    + distance(upperInner, lowerInner)
                    + distance(points.get(63), points.get(65))) / 3.0) / faceHeight;
            double outerHeight = distance(upperOuter, lowerOuter) / faceHeight;
            double opennessRatio = innerHeight / Math.max(0.08, mouthWidth);
            double cornerY = (leftCorner.y() + rightCorner.y()) / 2.0;
            double centerY = (upperInner.y() + lowerInner.y() + upperOuter.y() + lowerOuter.y()) / 4.0;
            double upturn = (centerY - cornerY) / faceHeight;
            double downturn = (cornerY - centerY) / faceHeight;

            double openScore = clamp(((innerHeight - 0.010) * 10.5)
                    + ((outerHeight - 0.046) * 3.8)
                    + ((opennessRatio - 0.052) * 1.6), 0.0, 1.0);
            double wideScore = clamp(((mouthWidth - 0.305) * 3.4)
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

        private static EyeLandmarkMetrics landmarkEyes(Point2fVector points) {
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
            double blinkScore = Math.min(leftClosed, rightClosed);
            double winkScore = Math.max(leftClosed * rightOpen, rightClosed * leftOpen);
            return new EyeLandmarkMetrics(count, blinkScore, winkScore, leftClosed, rightClosed);
        }

        private static LandmarkSignals landmarkSignals(Point2fVector points, Rect face, EyeLandmarkMetrics eyeMetrics) {
            double faceWidth = Math.max(1.0, face.width());
            double faceHeight = Math.max(1.0, face.height());
            double leftBrowY = averageY(points, 17, 21);
            double rightBrowY = averageY(points, 22, 26);
            double leftEyeY = averageY(points, 36, 41);
            double rightEyeY = averageY(points, 42, 47);
            double leftBrowGap = (leftEyeY - leftBrowY) / faceHeight;
            double rightBrowGap = (rightEyeY - rightBrowY) / faceHeight;
            double browGap = (leftBrowGap + rightBrowGap) / 2.0;
            double innerBrowDistance = distance(points.get(21), points.get(22)) / faceWidth;

            double browRaiseScore = clamp((browGap - 0.105) * 8.0, 0.0, 1.0);
            double leftBrowRaiseScore = clamp((leftBrowGap - 0.105) * 8.0, 0.0, 1.0);
            double rightBrowRaiseScore = clamp((rightBrowGap - 0.105) * 8.0, 0.0, 1.0);
            double browPinchScore = clamp((0.125 - innerBrowDistance) * 8.5, 0.0, 1.0);
            double leftBrowFurrowScore = browPinchScore;
            double rightBrowFurrowScore = browPinchScore;
            double browFurrowScore = Math.max(leftBrowFurrowScore, rightBrowFurrowScore);
            double leftBrowSadScore = clamp((0.092 - leftBrowGap) * 12.0, 0.0, 1.0);
            double rightBrowSadScore = clamp((0.092 - rightBrowGap) * 12.0, 0.0, 1.0);

            Point2f noseTip = points.get(30);
            double jawCenterX = (points.get(0).x() + points.get(16).x()) / 2.0;
            double noseOffset = Math.abs(noseTip.x() - jawCenterX) / faceWidth;
            double leftEyeWidth = distance(points.get(36), points.get(39));
            double rightEyeWidth = distance(points.get(42), points.get(45));
            double eyeAsymmetry = Math.abs(leftEyeWidth - rightEyeWidth) / Math.max(1.0, leftEyeWidth + rightEyeWidth);
            double headYawScore = clamp(Math.max(noseOffset * 3.2, eyeAsymmetry * 2.2), 0.0, 1.0);

            return new LandmarkSignals(
                    eyeMetrics.blinkScore(),
                    eyeMetrics.winkScore(),
                    eyeMetrics.leftClosedScore(),
                    eyeMetrics.rightClosedScore(),
                    browRaiseScore,
                    leftBrowRaiseScore,
                    rightBrowRaiseScore,
                    browFurrowScore,
                    leftBrowFurrowScore,
                    rightBrowFurrowScore,
                    leftBrowSadScore,
                    rightBrowSadScore,
                    headYawScore
            );
        }

        private static double eyeAspectRatio(Point2fVector points, int left, int upperLeft, int upperRight,
                                             int right, int lowerRight, int lowerLeft) {
            double horizontal = distance(points.get(left), points.get(right));
            if (horizontal <= 0.0) {
                return 0.0;
            }

            double vertical = distance(points.get(upperLeft), points.get(lowerLeft))
                    + distance(points.get(upperRight), points.get(lowerRight));
            return vertical / (2.0 * horizontal);
        }

        private static double averageY(Point2fVector points, int startInclusive, int endInclusive) {
            double sum = 0.0;
            int count = 0;
            for (int index = startInclusive; index <= endInclusive; index++) {
                sum += points.get(index).y();
                count++;
            }
            return sum / Math.max(1, count);
        }

        private static double distance(Point2f first, Point2f second) {
            double dx = first.x() - second.x();
            double dy = first.y() - second.y();
            return Math.sqrt((dx * dx) + (dy * dy));
        }

        private record EyeLandmarkMetrics(
                int eyeCount,
                double blinkScore,
                double winkScore,
                double leftClosedScore,
                double rightClosedScore
        ) {
        }
    }

    private static final class ExpressionCalibrationBank {
        private final Map<String, ExpressionProfileState> profiles = new LinkedHashMap<>();

        private ExpressionCalibrationBank() {
            for (String expression : CALIBRATABLE_EXPRESSIONS) {
                profiles.put(expression, new ExpressionProfileState(expression));
            }
        }

        void reset(String expression, int targetFrames) {
            profiles.get(expression).reset(targetFrames);
        }

        void add(String expression, ExpressionFeatures features, ExpressionScores scores) {
            profiles.get(expression).add(features, scores);
        }

        ExpressionScores apply(ExpressionScores baseScores, ExpressionFeatures features) {
            Map<String, Double> matches = new LinkedHashMap<>();
            String bestExpression = null;
            double bestMatch = -1.0;
            double secondBestMatch = -1.0;
            int readyProfiles = 0;

            for (Map.Entry<String, ExpressionProfileState> entry : profiles.entrySet()) {
                ExpressionProfileState profile = entry.getValue();
                if (!profile.ready()) {
                    continue;
                }

                readyProfiles++;
                double match = profile.match(features);
                matches.put(entry.getKey(), match);
                if (match > bestMatch) {
                    secondBestMatch = bestMatch;
                    bestMatch = match;
                    bestExpression = entry.getKey();
                } else if (match > secondBestMatch) {
                    secondBestMatch = match;
                }
            }

            return new ExpressionScores(
                    calibratedScore(EXPRESSION_NEUTRAL, baseScores, features, matches, bestExpression, secondBestMatch, readyProfiles),
                    calibratedScore(EXPRESSION_HAPPY, baseScores, features, matches, bestExpression, secondBestMatch, readyProfiles),
                    calibratedScore(EXPRESSION_FUNNY, baseScores, features, matches, bestExpression, secondBestMatch, readyProfiles),
                    calibratedScore(EXPRESSION_SAD, baseScores, features, matches, bestExpression, secondBestMatch, readyProfiles),
                    calibratedScore(EXPRESSION_SURPRISED, baseScores, features, matches, bestExpression, secondBestMatch, readyProfiles),
                    calibratedScore(EXPRESSION_TALKING, baseScores, features, matches, bestExpression, secondBestMatch, readyProfiles),
                    calibratedScore(EXPRESSION_BLINKING, baseScores, features, matches, bestExpression, secondBestMatch, readyProfiles),
                    calibratedScore(EXPRESSION_WINKING, baseScores, features, matches, bestExpression, secondBestMatch, readyProfiles),
                    calibratedScore(EXPRESSION_FOCUSED, baseScores, features, matches, bestExpression, secondBestMatch, readyProfiles)
            );
        }

        private double calibratedScore(
                String expression,
                ExpressionScores baseScores,
                ExpressionFeatures features,
                Map<String, Double> matches,
                String bestExpression,
                double secondBestMatch,
                int readyProfiles
        ) {
            return profiles.get(expression).calibratedScore(baseScores, features,
                    profileWeight(expression, matches, bestExpression, secondBestMatch, readyProfiles));
        }

        private static double profileWeight(
                String expression,
                Map<String, Double> matches,
                String bestExpression,
                double secondBestMatch,
                int readyProfiles
        ) {
            Double match = matches.get(expression);
            if (match == null || match < 0.38) {
                return 0.0;
            }
            if (readyProfiles == 1) {
                return clamp((match - 0.38) / 0.30, 0.0, 0.75);
            }
            if (!expression.equals(bestExpression)) {
                return 0.0;
            }

            double separation = Math.max(0.0, match - Math.max(0.0, secondBestMatch));
            double matchWeight = clamp((match - 0.38) / 0.30, 0.0, 1.0);
            double separationWeight = clamp(separation / 0.20, 0.0, 1.0);
            return clamp((matchWeight * 0.55) + (separationWeight * 0.45), 0.0, 0.90);
        }

        boolean ready(String expression) {
            return profiles.get(expression).ready();
        }

        int readyCount() {
            int ready = 0;
            for (ExpressionProfileState profile : profiles.values()) {
                if (profile.ready()) {
                    ready++;
                }
            }
            return ready;
        }

        double completion(String expression) {
            return profiles.get(expression).completion();
        }

        int targetFrames(String expression) {
            return profiles.get(expression).targetFrames();
        }

        Map<String, CalibrationProfile.Expression> profile() {
            Map<String, CalibrationProfile.Expression> profile = new LinkedHashMap<>();
            for (Map.Entry<String, ExpressionProfileState> entry : profiles.entrySet()) {
                CalibrationProfile.Expression expressionProfile = entry.getValue().profile();
                if (expressionProfile.samples() > 0) {
                    profile.put(entry.getKey(), expressionProfile);
                }
            }
            return profile;
        }

        void apply(Map<String, CalibrationProfile.Expression> savedProfiles) {
            for (Map.Entry<String, CalibrationProfile.Expression> entry : savedProfiles.entrySet()) {
                String expression = normalizeExpression(entry.getKey());
                if (expression == null) {
                    continue;
                }

                profiles.get(expression).apply(entry.getValue());
            }
        }
    }

    private static final class ExpressionProfileState {
        private final String expression;
        private int targetFrames = AUTO_CALIBRATION_FRAMES;
        private int samples;
        private double smileScore;
        private double openScore;
        private double wideScore;
        private double sadScore;
        private double mouthActivityScore;
        private double bothEyesScore;
        private double oneEyeScore;
        private double noEyesScore;
        private double blinkShapeScore;
        private double winkShapeScore;
        private double browRaiseScore;
        private double browFurrowScore;
        private double headYawScore;
        private double expressionScore;

        private ExpressionProfileState(String expression) {
            this.expression = expression;
        }

        synchronized void reset(int targetFrames) {
            this.targetFrames = Math.max(1, targetFrames);
            samples = 0;
            smileScore = 0.0;
            openScore = 0.0;
            wideScore = 0.0;
            sadScore = 0.0;
            mouthActivityScore = 0.0;
            bothEyesScore = 0.0;
            oneEyeScore = 0.0;
            noEyesScore = 0.0;
            blinkShapeScore = 0.0;
            winkShapeScore = 0.0;
            browRaiseScore = 0.0;
            browFurrowScore = 0.0;
            headYawScore = 0.0;
            expressionScore = 0.0;
        }

        synchronized void add(ExpressionFeatures features, ExpressionScores scores) {
            samples++;
            double weight = 1.0 / samples;
            smileScore += (features.smileScore() - smileScore) * weight;
            openScore += (features.openScore() - openScore) * weight;
            wideScore += (features.wideScore() - wideScore) * weight;
            sadScore += (features.sadScore() - sadScore) * weight;
            mouthActivityScore += (features.mouthActivityScore() - mouthActivityScore) * weight;
            bothEyesScore += (features.bothEyesScore() - bothEyesScore) * weight;
            oneEyeScore += (features.oneEyeScore() - oneEyeScore) * weight;
            noEyesScore += (features.noEyesScore() - noEyesScore) * weight;
            blinkShapeScore += (features.blinkShapeScore() - blinkShapeScore) * weight;
            winkShapeScore += (features.winkShapeScore() - winkShapeScore) * weight;
            browRaiseScore += (features.browRaiseScore() - browRaiseScore) * weight;
            browFurrowScore += (features.browFurrowScore() - browFurrowScore) * weight;
            headYawScore += (features.headYawScore() - headYawScore) * weight;
            expressionScore += (scores.score(expression) - expressionScore) * weight;
        }

        synchronized double match(ExpressionFeatures currentFeatures) {
            if (!ready()) {
                return 0.0;
            }

            return profileFeatures().match(currentFeatures, weightsFor(expression));
        }

        synchronized double calibratedScore(ExpressionScores baseScores, ExpressionFeatures currentFeatures, double profileWeight) {
            double baseScore = baseScores.score(expression);
            if (!ready() || profileWeight <= 0.0) {
                return baseScore;
            }

            double featureMatch = profileFeatures().match(currentFeatures, weightsFor(expression));
            double learnedActivation = clamp(baseScore / Math.max(0.18, expressionScore * 0.78), 0.0, 1.0);
            double calibratedScore = (featureMatch * 0.62) + (learnedActivation * 0.38);
            double boost = Math.max(0.0, clamp(calibratedScore, 0.0, 1.0) - baseScore);
            return clamp(baseScore + (boost * profileWeight), 0.0, 1.0);
        }

        private ExpressionFeatures profileFeatures() {
            return new ExpressionFeatures(
                    smileScore,
                    openScore,
                    wideScore,
                    sadScore,
                    mouthActivityScore,
                    0.0,
                    bothEyesScore,
                    oneEyeScore,
                    noEyesScore,
                    blinkShapeScore,
                    winkShapeScore,
                    browRaiseScore,
                    browFurrowScore,
                    headYawScore
            );
        }

        synchronized boolean ready() {
            return samples >= Math.min(12, targetFrames);
        }

        synchronized double completion() {
            return clamp((double) samples / Math.max(1, targetFrames), 0.0, 1.0);
        }

        synchronized int targetFrames() {
            return targetFrames;
        }

        synchronized CalibrationProfile.Expression profile() {
            return new CalibrationProfile.Expression(
                    targetFrames,
                    samples,
                    smileScore,
                    openScore,
                    wideScore,
                    sadScore,
                    mouthActivityScore,
                    bothEyesScore,
                    oneEyeScore,
                    noEyesScore,
                    blinkShapeScore,
                    winkShapeScore,
                    browRaiseScore,
                    browFurrowScore,
                    headYawScore,
                    expressionScore
            );
        }

        synchronized void apply(CalibrationProfile.Expression profile) {
            targetFrames = Math.max(1, profile.targetFrames());
            samples = Math.max(0, profile.samples());
            smileScore = profile.smileScore();
            openScore = profile.openScore();
            wideScore = profile.wideScore();
            sadScore = profile.sadScore();
            mouthActivityScore = profile.mouthActivityScore();
            bothEyesScore = profile.bothEyesScore();
            oneEyeScore = profile.oneEyeScore();
            noEyesScore = profile.noEyesScore();
            blinkShapeScore = profile.blinkShapeScore();
            winkShapeScore = profile.winkShapeScore();
            browRaiseScore = profile.browRaiseScore();
            browFurrowScore = profile.browFurrowScore();
            headYawScore = profile.headYawScore();
            expressionScore = profile.expressionScore();
        }

        private static ExpressionWeights weightsFor(String expression) {
            return switch (expression) {
                case EXPRESSION_HAPPY -> new ExpressionWeights(2.2, 0.6, 0.9, 0.9, 0.5, 0.3, 0.6, 0.8, 0.4, 0.4, 0.5, 0.7, 0.4);
                case EXPRESSION_FUNNY -> new ExpressionWeights(2.2, 0.8, 1.6, 0.7, 0.6, 0.6, 1.3, 0.5, 0.6, 2.0, 1.2, 0.5, 0.4);
                case EXPRESSION_SAD -> new ExpressionWeights(1.0, 0.8, 0.5, 2.4, 0.7, 0.3, 0.5, 0.5, 0.4, 0.4, 0.8, 1.6, 0.5);
                case EXPRESSION_SURPRISED -> new ExpressionWeights(0.8, 2.5, 0.7, 0.6, 0.8, 0.4, 0.5, 0.5, 0.5, 0.5, 1.6, 0.6, 0.5);
                case EXPRESSION_TALKING -> new ExpressionWeights(0.7, 1.2, 1.0, 0.6, 2.2, 0.3, 0.5, 0.5, 0.4, 0.4, 0.5, 0.5, 0.4);
                case EXPRESSION_BLINKING -> new ExpressionWeights(0.3, 0.3, 0.3, 0.3, 0.3, 1.3, 0.8, 2.8, 2.8, 0.9, 0.3, 0.3, 0.3);
                case EXPRESSION_WINKING -> new ExpressionWeights(0.3, 0.3, 0.3, 0.3, 0.3, 1.1, 2.8, 0.9, 0.9, 2.8, 0.3, 0.3, 0.3);
                case EXPRESSION_FOCUSED -> new ExpressionWeights(0.9, 0.7, 0.6, 0.7, 0.8, 1.4, 0.6, 0.6, 0.4, 0.4, 0.5, 1.4, 0.8);
                default -> new ExpressionWeights(1.4, 1.3, 1.1, 1.1, 1.3, 0.8, 0.6, 0.8, 0.4, 0.4, 0.6, 0.6, 0.5);
            };
        }
    }

    private static final class CalibrationState {
        private int targetFrames = AUTO_CALIBRATION_FRAMES;
        private int samples;
        private double smileScore;
        private double openScore;
        private double wideScore;
        private double sadScore;

        synchronized void reset(int targetFrames) {
            this.targetFrames = Math.max(1, targetFrames);
            samples = 0;
            smileScore = 0.0;
            openScore = 0.0;
            wideScore = 0.0;
            sadScore = 0.0;
        }

        synchronized void add(double rawSmileScore, MouthMetrics rawMouth) {
            samples++;
            double weight = 1.0 / samples;
            smileScore += (rawSmileScore - smileScore) * weight;
            openScore += (rawMouth.openScore() - openScore) * weight;
            wideScore += (rawMouth.wideScore() - wideScore) * weight;
            sadScore += (rawMouth.sadScore() - sadScore) * weight;
        }

        synchronized void adapt(double rawSmileScore, MouthMetrics rawMouth) {
            if (!ready()) {
                return;
            }

            double weight = 0.015;
            smileScore += (rawSmileScore - smileScore) * weight;
            openScore += (rawMouth.openScore() - openScore) * weight;
            wideScore += (rawMouth.wideScore() - wideScore) * weight;
            sadScore += (rawMouth.sadScore() - sadScore) * weight;
        }

        synchronized CalibrationSnapshot snapshot() {
            if (!ready()) {
                return new CalibrationSnapshot(0.0, 0.0, 0.0, 0.0);
            }
            return new CalibrationSnapshot(smileScore, openScore, wideScore, sadScore);
        }

        synchronized boolean ready() {
            return samples >= Math.min(12, targetFrames);
        }

        synchronized double completion() {
            return clamp((double) samples / Math.max(1, targetFrames), 0.0, 1.0);
        }

        synchronized int targetFrames() {
            return targetFrames;
        }

        synchronized CalibrationProfile.Baseline profile() {
            return new CalibrationProfile.Baseline(
                    targetFrames,
                    samples,
                    smileScore,
                    openScore,
                    wideScore,
                    sadScore
            );
        }

        synchronized void apply(CalibrationProfile.Baseline baseline) {
            targetFrames = Math.max(1, baseline.targetFrames());
            samples = Math.max(0, baseline.samples());
            smileScore = baseline.smileScore();
            openScore = baseline.openScore();
            wideScore = baseline.wideScore();
            sadScore = baseline.sadScore();
        }
    }

    private record ExpressionScores(
            double neutralScore,
            double happyScore,
            double funnyScore,
            double sadScore,
            double surprisedScore,
            double talkingScore,
            double blinkingScore,
            double winkingScore,
            double focusedScore
    ) {
        private double score(String expression) {
            return switch (expression) {
                case EXPRESSION_HAPPY -> happyScore;
                case EXPRESSION_FUNNY -> funnyScore;
                case EXPRESSION_SAD -> sadScore;
                case EXPRESSION_SURPRISED -> surprisedScore;
                case EXPRESSION_TALKING -> talkingScore;
                case EXPRESSION_BLINKING -> blinkingScore;
                case EXPRESSION_WINKING -> winkingScore;
                case EXPRESSION_FOCUSED -> focusedScore;
                default -> neutralScore;
            };
        }

        private ExpressionScores blend(ExpressionScores current, double currentWeight) {
            double weight = clamp(currentWeight, 0.0, 1.0);
            double previousWeight = 1.0 - weight;
            return new ExpressionScores(
                    (neutralScore * previousWeight) + (current.neutralScore() * weight),
                    (happyScore * previousWeight) + (current.happyScore() * weight),
                    (funnyScore * previousWeight) + (current.funnyScore() * weight),
                    (sadScore * previousWeight) + (current.sadScore() * weight),
                    (surprisedScore * previousWeight) + (current.surprisedScore() * weight),
                    (talkingScore * previousWeight) + (current.talkingScore() * weight),
                    (blinkingScore * previousWeight) + (current.blinkingScore() * weight),
                    (winkingScore * previousWeight) + (current.winkingScore() * weight),
                    (focusedScore * previousWeight) + (current.focusedScore() * weight)
            );
        }

        private ExpressionScores withOffAxisGating(double frontalQuality, double minFrontalQuality) {
            if (frontalQuality >= minFrontalQuality) {
                return this;
            }

            double severity = clamp((minFrontalQuality - frontalQuality) / Math.max(0.05, minFrontalQuality), 0.0, 1.0);
            double expressionFactor = 1.0 - severity * 0.76;
            double eyeGestureFactor = 1.0 - severity * 0.88;
            double focusedFactor = 1.0 - severity * 0.30;
            double neutral = clamp(Math.max(neutralScore, focusedScore * 0.72) + severity * 0.22, 0.0, 1.0);
            return new ExpressionScores(
                    neutral,
                    happyScore * expressionFactor,
                    funnyScore * expressionFactor,
                    sadScore * expressionFactor,
                    surprisedScore * expressionFactor,
                    talkingScore * expressionFactor,
                    blinkingScore * eyeGestureFactor,
                    winkingScore * eyeGestureFactor,
                    clamp(focusedScore * focusedFactor + severity * 0.08, 0.0, 1.0)
            );
        }

        private String bestSustainedExpression() {
            String best = EXPRESSION_NEUTRAL;
            double bestScore = neutralScore;
            if (happyScore > bestScore) {
                best = EXPRESSION_HAPPY;
                bestScore = happyScore;
            }
            if (funnyScore > bestScore) {
                best = EXPRESSION_FUNNY;
                bestScore = funnyScore;
            }
            if (sadScore > bestScore) {
                best = EXPRESSION_SAD;
                bestScore = sadScore;
            }
            if (surprisedScore > bestScore) {
                best = EXPRESSION_SURPRISED;
                bestScore = surprisedScore;
            }
            if (talkingScore > bestScore) {
                best = EXPRESSION_TALKING;
                bestScore = talkingScore;
            }
            if (focusedScore > bestScore) {
                best = EXPRESSION_FOCUSED;
            }
            return best;
        }
    }

    private record ExpressionFeatures(
            double smileScore,
            double openScore,
            double wideScore,
            double sadScore,
            double mouthActivityScore,
            double tongueScore,
            double bothEyesScore,
            double oneEyeScore,
            double noEyesScore,
            double blinkShapeScore,
            double winkShapeScore,
            double browRaiseScore,
            double browFurrowScore,
            double headYawScore
    ) {
        private static ExpressionFeatures from(double smileScore, MouthMetrics mouth, double mouthActivityScore,
                                               long eyeCount, LandmarkSignals landmarkSignals) {
            return new ExpressionFeatures(
                    smileScore,
                    mouth.openScore(),
                    mouth.wideScore(),
                    mouth.sadScore(),
                    mouthActivityScore,
                    mouth.tongueScore(),
                    eyeCount >= 2 ? 1.0 : 0.0,
                    eyeCount == 1 ? 1.0 : 0.0,
                    eyeCount == 0 ? 1.0 : 0.0,
                    landmarkSignals.blinkScore(),
                    landmarkSignals.winkScore(),
                    landmarkSignals.browRaiseScore(),
                    landmarkSignals.browFurrowScore(),
                    landmarkSignals.headYawScore()
            );
        }

        private double match(ExpressionFeatures other, ExpressionWeights weights) {
            double weightedDistance =
                    weights.smileScore() * square(smileScore - other.smileScore())
                            + weights.openScore() * square(openScore - other.openScore())
                            + weights.wideScore() * square(wideScore - other.wideScore())
                            + weights.sadScore() * square(sadScore - other.sadScore())
                            + weights.mouthActivityScore() * square(mouthActivityScore - other.mouthActivityScore())
                            + weights.bothEyesScore() * square(bothEyesScore - other.bothEyesScore())
                            + weights.oneEyeScore() * square(oneEyeScore - other.oneEyeScore())
                            + weights.noEyesScore() * square(noEyesScore - other.noEyesScore())
                            + weights.blinkShapeScore() * square(blinkShapeScore - other.blinkShapeScore())
                            + weights.winkShapeScore() * square(winkShapeScore - other.winkShapeScore())
                            + weights.browRaiseScore() * square(browRaiseScore - other.browRaiseScore())
                            + weights.browFurrowScore() * square(browFurrowScore - other.browFurrowScore())
                            + weights.headYawScore() * square(headYawScore - other.headYawScore());
            double normalizedDistance = Math.sqrt(weightedDistance / Math.max(0.001, weights.total()));
            return clamp(1.0 - (normalizedDistance / 0.42), 0.0, 1.0);
        }

        private static double square(double value) {
            return value * value;
        }
    }

    private record ExpressionWeights(
            double smileScore,
            double openScore,
            double wideScore,
            double sadScore,
            double mouthActivityScore,
            double bothEyesScore,
            double oneEyeScore,
            double noEyesScore,
            double blinkShapeScore,
            double winkShapeScore,
            double browRaiseScore,
            double browFurrowScore,
            double headYawScore
    ) {
        private double total() {
            return smileScore + openScore + wideScore + sadScore + mouthActivityScore
                    + bothEyesScore + oneEyeScore + noEyesScore + blinkShapeScore
                    + winkShapeScore + browRaiseScore + browFurrowScore + headYawScore;
        }
    }

    private record CalibrationSnapshot(double smileScore, double openScore, double wideScore, double sadScore) {
    }

    private record CurveMetrics(double smileScore, double sadScore) {
        private static final CurveMetrics NONE = new CurveMetrics(0.0, 0.0);
    }

    private record LandmarkSignals(
            double blinkScore,
            double winkScore,
            double leftEyeClosedScore,
            double rightEyeClosedScore,
            double browRaiseScore,
            double leftBrowRaiseScore,
            double rightBrowRaiseScore,
            double browFurrowScore,
            double leftBrowFurrowScore,
            double rightBrowFurrowScore,
            double leftBrowSadScore,
            double rightBrowSadScore,
            double headYawScore
    ) {
        private static final LandmarkSignals NONE = new LandmarkSignals(
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        );

        private LandmarkSignals withCascadeEyeFallback(EyeDetections eyes) {
            double leftClosed = Math.max(leftEyeClosedScore, !eyes.leftVisible() && eyes.rightVisible() ? 0.72 : 0.0);
            double rightClosed = Math.max(rightEyeClosedScore, !eyes.rightVisible() && eyes.leftVisible() ? 0.72 : 0.0);
            double leftOpen = Math.max(eyes.leftOpenScore(), 1.0 - leftClosed);
            double rightOpen = Math.max(eyes.rightOpenScore(), 1.0 - rightClosed);
            double blink = Math.max(blinkScore, Math.min(leftClosed, rightClosed));
            double wink = Math.max(winkScore, Math.max(leftClosed * rightOpen, rightClosed * leftOpen));
            return new LandmarkSignals(
                    blink,
                    wink,
                    leftClosed,
                    rightClosed,
                    browRaiseScore,
                    leftBrowRaiseScore,
                    rightBrowRaiseScore,
                    browFurrowScore,
                    leftBrowFurrowScore,
                    rightBrowFurrowScore,
                    leftBrowSadScore,
                    rightBrowSadScore,
                    headYawScore
            );
        }
    }

    private record LandmarkEstimate(MouthMetrics mouth, int eyeCount, LandmarkSignals signals) {
    }

    private record EyeDetections(double leftOpenScore, double rightOpenScore) {
        private boolean leftVisible() {
            return leftOpenScore >= 0.32;
        }

        private boolean rightVisible() {
            return rightOpenScore >= 0.32;
        }

        private int eyeCount() {
            return (leftVisible() ? 1 : 0) + (rightVisible() ? 1 : 0);
        }
    }

    private record TrackingQuality(double frontalQuality, boolean frontal) {
    }

    private record ProfileFaceDetection(Rect face, int faceCount) {
        private static final ProfileFaceDetection NONE = new ProfileFaceDetection(new Rect(0, 0, 0, 0), 0);

        private boolean found() {
            return faceCount > 0;
        }
    }

    private record MouthMetrics(double openScore, double wideScore, double sadScore, double smileCurveScore, double tongueScore) {
        private MouthMetrics blend(MouthMetrics other, double otherWeight) {
            double weight = clamp(otherWeight, 0.0, 1.0);
            double selfWeight = 1.0 - weight;
            return new MouthMetrics(
                    (openScore * selfWeight) + (other.openScore() * weight),
                    (wideScore * selfWeight) + (other.wideScore() * weight),
                    (sadScore * selfWeight) + (other.sadScore() * weight),
                    (smileCurveScore * selfWeight) + (other.smileCurveScore() * weight),
                    (tongueScore * selfWeight) + (other.tongueScore() * weight)
            );
        }

        private MouthMetrics max(MouthMetrics other) {
            return new MouthMetrics(
                    Math.max(openScore, other.openScore()),
                    Math.max(wideScore, other.wideScore()),
                    Math.max(sadScore, other.sadScore()),
                    Math.max(smileCurveScore, other.smileCurveScore()),
                    Math.max(tongueScore, other.tongueScore())
            );
        }

        private MouthMetrics withTongue(double tongueScore) {
            return new MouthMetrics(openScore, wideScore, sadScore, smileCurveScore, clamp(tongueScore, 0.0, 1.0));
        }
    }

    private record ExpressionCandidate(String expression, double confidence, double blinkScore, double winkScore) {
    }
}
