package com.facetrack.client2;

import static org.bytedeco.opencv.global.opencv_core.countNonZero;
import static org.bytedeco.opencv.global.opencv_face.createFacemarkLBF;
import static org.bytedeco.opencv.global.opencv_imgproc.CHAIN_APPROX_SIMPLE;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.FONT_HERSHEY_SIMPLEX;
import static org.bytedeco.opencv.global.opencv_imgproc.GaussianBlur;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_AA;
import static org.bytedeco.opencv.global.opencv_imgproc.RETR_EXTERNAL;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY_INV;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_OTSU;
import static org.bytedeco.opencv.global.opencv_imgproc.boundingRect;
import static org.bytedeco.opencv.global.opencv_imgproc.contourArea;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.equalizeHist;
import static org.bytedeco.opencv.global.opencv_imgproc.findContours;
import static org.bytedeco.opencv.global.opencv_imgproc.putText;
import static org.bytedeco.opencv.global.opencv_imgproc.rectangle;
import static org.bytedeco.opencv.global.opencv_imgproc.threshold;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Point2f;
import org.bytedeco.opencv.opencv_core.Point2fVector;
import org.bytedeco.opencv.opencv_core.Point2fVectorVector;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_face.Facemark;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;

final class AiExpressionTracker {
    private static final Size FACE_MIN_SIZE = new Size(90, 90);
    private static final Size EYE_MIN_SIZE = new Size(16, 12);
    private static final Size SMILE_MIN_SIZE = new Size(24, 12);
    private static final Scalar FACE_COLOR = new Scalar(64, 208, 255, 0);
    private static final Scalar TEXT_COLOR = new Scalar(235, 238, 242, 0);
    private static final Scalar ALERT_COLOR = new Scalar(70, 120, 255, 0);
    private static final Scalar GOOD_COLOR = new Scalar(80, 220, 120, 0);
    private static final Scalar PANEL_COLOR = new Scalar(28, 31, 36, 0);

    private final NeuralExpressionModel model;
    private final CascadeClassifier faceCascade = loadCascade("haarcascade_frontalface_alt2.xml");
    private final CascadeClassifier eyeCascade = loadCascade("haarcascade_eye_tree_eyeglasses.xml");
    private final CascadeClassifier smileCascade = loadCascade("haarcascade_smile.xml");
    private final LandmarkRefiner landmarkRefiner = LandmarkRefiner.create();
    private final OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
    private final Java2DFrameConverter imageConverter = new Java2DFrameConverter();

    private ExpressionLabel stableLabel = ExpressionLabel.NO_FACE;
    private ExpressionLabel pendingLabel = ExpressionLabel.NO_FACE;
    private int pendingFrames;
    private double stableConfidence;
    private double previousMouthOpen;
    private double previousMouthWide;
    private double mouthActivity;
    private boolean mouthInitialized;
    private int missingEyeFrames;

    AiExpressionTracker(NeuralExpressionModel model) {
        this.model = model;
    }

    TrackingFrame track(Mat cameraFrame, double fps) {
        Mat frame = cameraFrame.clone();
        Mat gray = new Mat();
        RectVector faces = new RectVector();

        try {
            if (frame.channels() > 1) {
                cvtColor(frame, gray, COLOR_BGR2GRAY);
            } else {
                frame.copyTo(gray);
            }
            equalizeHist(gray, gray);

            faceCascade.detectMultiScale(gray, faces, 1.08, 4, 0, FACE_MIN_SIZE, new Size());
            ExpressionEstimate estimate;
            if (faces.size() == 0) {
                resetTransientState();
                estimate = ExpressionEstimate.noFace(fps);
            } else {
                estimate = estimateFace(gray, frame, faces, fps);
            }

            annotate(frame, estimate);
            BufferedImage image = imageConverter.convert(matConverter.convert(frame));
            return new TrackingFrame(image, estimate);
        } finally {
            faces.close();
            gray.close();
            frame.close();
        }
    }

    private ExpressionEstimate estimateFace(Mat gray, Mat frame, RectVector faces, double fps) {
        Rect face = largestFace(faces);
        Rect eyeArea = scaledRect(face, 0.05, 0.12, 0.90, 0.36);
        Rect smileArea = scaledRect(face, 0.10, 0.45, 0.80, 0.42);
        Rect mouthArea = scaledRect(face, 0.18, 0.52, 0.64, 0.35);

        Mat eyesRoi = new Mat(gray, eyeArea);
        Mat smileRoi = new Mat(gray, smileArea);
        Mat mouthRoi = new Mat(gray, mouthArea);
        RectVector eyes = new RectVector();
        RectVector smiles = new RectVector();

        try {
            eyeCascade.detectMultiScale(eyesRoi, eyes, 1.07, 3, 0, EYE_MIN_SIZE, new Size());
            smileCascade.detectMultiScale(smileRoi, smiles, 1.16, 12, 0, SMILE_MIN_SIZE, new Size());

            EyeSignals eyeSignals = analyzeEyes(eyes, face, eyeArea);
            MouthMetrics imageMouth = analyzeMouth(mouthRoi);
            LandmarkEstimate landmark = landmarkRefiner.estimate(gray, face);
            LandmarkSignals landmarkSignals = LandmarkSignals.NONE;
            MouthMetrics mouth = imageMouth;
            boolean landmarkFound = landmark != null;
            if (landmarkFound) {
                landmarkSignals = landmark.signals();
                eyeSignals = landmark.eyeSignals().withCascadeFallback(eyeSignals);
                mouth = landmark.mouth().blend(imageMouth, 0.12);
            }

            double cascadeSmile = scoreSmiles(smiles, smileArea);
            double smileScore = landmarkFound
                    ? Math.max(mouth.smile(), cascadeSmile * 0.18)
                    : Math.max(cascadeSmile, mouth.wide() > 0.54 ? mouth.wide() * 0.55 : 0.0);
            double mouthOpen = mouth.open();
            double mouthWide = mouth.wide();
            double activity = updateMouthActivity(mouthOpen, mouthWide);
            double sadScore = Math.max(mouth.sad(), scoreSad(smileScore, mouthOpen, mouthWide));
            double faceSize = clamp01((face.width() * (double) face.height()) / Math.max(1.0, frame.cols() * (double) frame.rows() * 0.28));
            double faceAspect = clamp01(face.width() / (double) Math.max(1, face.height()));
            double centerOffset = centerOffset(face, frame.cols(), frame.rows());
            double frontalQuality = frontalQuality(eyeSignals.eyePresence(), faceAspect, centerOffset, landmarkSignals.headYaw());

            ExpressionFeatures features = new ExpressionFeatures(
                    smileScore,
                    mouthOpen,
                    mouthWide,
                    sadScore,
                    activity,
                    eyeSignals.eyePresence(),
                    eyeSignals.blink(),
                    eyeSignals.wink(),
                    eyeSignals.leftClosed(),
                    eyeSignals.rightClosed(),
                    faceSize,
                    faceAspect,
                    centerOffset,
                    frontalQuality
            );

            NeuralPrediction prediction = smooth(applyFastGestures(model.classify(features), features));
            FaceParts parts = estimateParts(prediction.label(), features, landmarkSignals);
            return new ExpressionEstimate(
                    Instant.now(),
                    prediction.label(),
                    prediction.confidence(),
                    Math.toIntExact(faces.size()),
                    copyRect(face),
                    smileScore,
                    mouthOpen,
                    mouthWide,
                    sadScore,
                    eyeSignals.blink(),
                    eyeSignals.wink(),
                    parts,
                    fps,
                    frontalQuality,
                    features
            );
        } finally {
            eyes.close();
            smiles.close();
            eyesRoi.close();
            smileRoi.close();
            mouthRoi.close();
        }
    }

    private EyeSignals analyzeEyes(RectVector eyes, Rect face, Rect eyeArea) {
        boolean leftSeen = false;
        boolean rightSeen = false;

        for (long i = 0; i < eyes.size(); i++) {
            Rect eye = eyes.get(i);
            double centerX = eyeArea.x() - face.x() + eye.x() + eye.width() / 2.0;
            if (centerX < face.width() / 2.0) {
                leftSeen = true;
            } else {
                rightSeen = true;
            }
        }

        if (eyes.size() >= 2 && (!leftSeen || !rightSeen)) {
            leftSeen = true;
            rightSeen = true;
        }

        int eyeCount = (leftSeen ? 1 : 0) + (rightSeen ? 1 : 0);
        if (eyeCount > 0) {
            missingEyeFrames = 0;
        } else {
            missingEyeFrames++;
        }

        boolean stableNoEyes = missingEyeFrames >= 3;
        double leftClosed = leftSeen ? 0.04 : rightSeen ? 0.72 : stableNoEyes ? 0.86 : 0.22;
        double rightClosed = rightSeen ? 0.04 : leftSeen ? 0.72 : stableNoEyes ? 0.86 : 0.22;
        double blink = stableNoEyes && eyeCount == 0 ? 0.90 : Math.min(leftClosed, rightClosed) > 0.65 ? 0.78 : 0.05;
        double wink = eyeCount == 1 ? Math.max(leftClosed, rightClosed) : 0.03;
        double eyePresence = clamp01(eyeCount / 2.0);

        return new EyeSignals(eyePresence, blink, wink, leftClosed, rightClosed);
    }

    private MouthMetrics analyzeMouth(Mat mouthRoi) {
        Mat blurred = new Mat();
        Mat binary = new Mat();
        Mat contoursSource = new Mat();
        MatVector contours = new MatVector();
        try {
            GaussianBlur(mouthRoi, blurred, new Size(5, 5), 0.0);
            threshold(blurred, binary, 0.0, 255.0, THRESH_BINARY_INV | THRESH_OTSU);
            binary.copyTo(contoursSource);
            findContours(contoursSource, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

            double darkRatio = countNonZero(binary) / Math.max(1.0, binary.rows() * (double) binary.cols());
            double widest = 0.0;
            double tallest = 0.0;
            double largestArea = 0.0;
            for (long i = 0; i < contours.size(); i++) {
                Mat contour = contours.get(i);
                double area = contourArea(contour);
                if (area > largestArea) {
                    Rect box = boundingRect(contour);
                    largestArea = area;
                    widest = box.width() / (double) Math.max(1, mouthRoi.cols());
                    tallest = box.height() / (double) Math.max(1, mouthRoi.rows());
                    box.close();
                }
                contour.close();
            }

            double open = clamp01((darkRatio - 0.13) / 0.28 + Math.max(0.0, tallest - 0.34) * 0.45);
            double wide = clamp01((widest - 0.36) / 0.44 + open * 0.08);
            double smile = clamp01(wide * 0.46 - open * 0.10);
            double sad = clamp01(Math.max(0.0, 0.34 - wide) * 1.05 + Math.max(0.0, 0.18 - open) * 0.25 - 0.16);
            return new MouthMetrics(open, wide, smile, sad);
        } finally {
            contours.close();
            contoursSource.close();
            binary.close();
            blurred.close();
        }
    }

    private double scoreSmiles(RectVector smiles, Rect smileArea) {
        double largest = 0.0;
        for (long i = 0; i < smiles.size(); i++) {
            Rect smile = smiles.get(i);
            largest = Math.max(largest, smile.width() * (double) smile.height());
        }
        double areaScore = largest / Math.max(1.0, smileArea.width() * (double) smileArea.height());
        double countScore = Math.min(2.0, smiles.size()) * 0.16;
        return clamp01((areaScore - 0.045) / 0.22 + countScore);
    }

    private double updateMouthActivity(double mouthOpen, double mouthWide) {
        double raw = 0.0;
        if (mouthInitialized) {
            raw = clamp01(Math.abs(mouthOpen - previousMouthOpen) * 3.4 + Math.abs(mouthWide - previousMouthWide) * 2.3);
        }
        mouthInitialized = true;
        previousMouthOpen = mouthOpen;
        previousMouthWide = mouthWide;
        mouthActivity = smoothMetric(mouthActivity, raw, raw > mouthActivity ? 0.55 : 0.20);
        return mouthActivity;
    }

    private NeuralPrediction applyFastGestures(NeuralPrediction prediction, ExpressionFeatures features) {
        ExpressionLabel label = prediction.label();
        double confidence = prediction.confidence();

        if (features.blink() > 0.78) {
            label = ExpressionLabel.BLINKING;
            confidence = Math.max(confidence, 0.76);
        } else if (features.wink() > 0.68) {
            label = ExpressionLabel.WINKING;
            confidence = Math.max(confidence, 0.74);
        } else if (features.mouthActivity() > 0.56 && features.mouthOpen() > 0.28) {
            label = ExpressionLabel.TALKING;
            confidence = Math.max(confidence, 0.68);
        }

        return new NeuralPrediction(label, confidence);
    }

    private NeuralPrediction smooth(NeuralPrediction prediction) {
        ExpressionLabel label = prediction.label();
        double confidence = prediction.confidence();
        boolean quickGesture = label == ExpressionLabel.BLINKING || label == ExpressionLabel.WINKING || label == ExpressionLabel.TALKING;

        if (stableLabel == ExpressionLabel.NO_FACE) {
            stableLabel = label;
            stableConfidence = confidence;
            pendingLabel = label;
            pendingFrames = 0;
            return new NeuralPrediction(stableLabel, stableConfidence);
        }

        if (label == stableLabel) {
            pendingFrames = 0;
            stableConfidence = smoothMetric(stableConfidence, confidence, confidence > stableConfidence ? 0.45 : 0.25);
            return new NeuralPrediction(stableLabel, stableConfidence);
        }

        if (label == pendingLabel) {
            pendingFrames++;
        } else {
            pendingLabel = label;
            pendingFrames = 1;
        }

        int requiredFrames = quickGesture ? 1 : 3;
        if (confidence >= 0.72 || pendingFrames >= requiredFrames) {
            stableLabel = label;
            stableConfidence = confidence;
            pendingFrames = 0;
        } else {
            stableConfidence = smoothMetric(stableConfidence, Math.min(stableConfidence, confidence), 0.15);
        }

        return new NeuralPrediction(stableLabel, stableConfidence);
    }

    private FaceParts estimateParts(ExpressionLabel label, ExpressionFeatures features, LandmarkSignals landmarkSignals) {
        FaceParts.Mouth mouth = FaceParts.Mouth.NEUTRAL;
        if (features.mouthOpen() > 0.72 || label == ExpressionLabel.SURPRISED) {
            mouth = FaceParts.Mouth.SURPRISED;
        } else if (features.mouthActivity() > 0.35 || label == ExpressionLabel.TALKING) {
            mouth = FaceParts.Mouth.TALKING;
        } else if (label == ExpressionLabel.FUNNY || (features.smile() > 0.45 && features.mouthWide() > 0.68)) {
            mouth = FaceParts.Mouth.FUNNY;
        } else if (features.smile() > 0.52 || label == ExpressionLabel.HAPPY) {
            mouth = FaceParts.Mouth.HAPPY;
        } else if (features.sad() > 0.58 || label == ExpressionLabel.SAD) {
            mouth = FaceParts.Mouth.SAD;
        }

        FaceParts.Eye leftEye = features.leftClosed() > 0.62 ? FaceParts.Eye.CLOSED : FaceParts.Eye.OPEN;
        FaceParts.Eye rightEye = features.rightClosed() > 0.62 ? FaceParts.Eye.CLOSED : FaceParts.Eye.OPEN;
        if (label == ExpressionLabel.FOCUSED && leftEye == FaceParts.Eye.OPEN && rightEye == FaceParts.Eye.OPEN) {
            leftEye = FaceParts.Eye.FOCUSED;
            rightEye = FaceParts.Eye.FOCUSED;
        }

        FaceParts.Eyebrow leftBrow = FaceParts.Eyebrow.NEUTRAL;
        FaceParts.Eyebrow rightBrow = FaceParts.Eyebrow.NEUTRAL;
        if (label == ExpressionLabel.SURPRISED) {
            leftBrow = FaceParts.Eyebrow.RAISED;
            rightBrow = FaceParts.Eyebrow.RAISED;
        } else if (label == ExpressionLabel.SAD || features.sad() > 0.60) {
            leftBrow = FaceParts.Eyebrow.SAD;
            rightBrow = FaceParts.Eyebrow.SAD;
        } else if (label == ExpressionLabel.FOCUSED) {
            leftBrow = FaceParts.Eyebrow.FOCUSED;
            rightBrow = FaceParts.Eyebrow.FOCUSED;
        }

        if (landmarkSignals.leftBrowRaise() > 0.55) {
            leftBrow = FaceParts.Eyebrow.RAISED;
        } else if (landmarkSignals.leftBrowSad() > 0.48) {
            leftBrow = FaceParts.Eyebrow.SAD;
        } else if (landmarkSignals.leftBrowFurrow() > 0.58) {
            leftBrow = FaceParts.Eyebrow.FOCUSED;
        }

        if (landmarkSignals.rightBrowRaise() > 0.55) {
            rightBrow = FaceParts.Eyebrow.RAISED;
        } else if (landmarkSignals.rightBrowSad() > 0.48) {
            rightBrow = FaceParts.Eyebrow.SAD;
        } else if (landmarkSignals.rightBrowFurrow() > 0.58) {
            rightBrow = FaceParts.Eyebrow.FOCUSED;
        }

        return new FaceParts(mouth, leftEye, rightEye, leftBrow, rightBrow);
    }

    private void annotate(Mat frame, ExpressionEstimate estimate) {
        String title = estimate.expression().displayName() + " " + Math.round(estimate.confidence() * 100.0) + "%";
        rectangle(frame, new Rect(12, 12, Math.min(420, Math.max(260, title.length() * 18)), 62), PANEL_COLOR, -1, LINE_AA, 0);
        putText(frame, title, new Point(24, 40), FONT_HERSHEY_SIMPLEX, 0.80, estimate.faceCount() > 0 ? GOOD_COLOR : ALERT_COLOR, 2, LINE_AA, false);
        putText(frame, String.format(Locale.US, "%.1f fps", estimate.fps()), new Point(24, 66), FONT_HERSHEY_SIMPLEX, 0.55, TEXT_COLOR, 1, LINE_AA, false);

        if (estimate.faceCount() > 0) {
            Rect face = estimate.face();
            rectangle(frame, face, FACE_COLOR, 2, LINE_AA, 0);
            ExpressionFeatures features = estimate.features();
            if (features != null) {
                String metrics = String.format(Locale.US, "smile %.2f open %.2f blink %.2f q %.2f",
                        features.smile(), features.mouthOpen(), features.blink(), features.frontalQuality());
                putText(frame, metrics, new Point(face.x(), Math.max(18, face.y() - 10)), FONT_HERSHEY_SIMPLEX, 0.48, TEXT_COLOR, 1, LINE_AA, false);
            }
        }
    }

    private void resetTransientState() {
        stableLabel = ExpressionLabel.NO_FACE;
        pendingLabel = ExpressionLabel.NO_FACE;
        pendingFrames = 0;
        stableConfidence = 0.0;
        previousMouthOpen = 0.0;
        previousMouthWide = 0.0;
        mouthActivity = 0.0;
        mouthInitialized = false;
        missingEyeFrames = 0;
    }

    private static CascadeClassifier loadCascade(String resourceName) {
        CascadeClassifier classifier = new CascadeClassifier(ResourceExtractor.extractCascade(resourceName).toString());
        if (classifier.empty()) {
            throw new IllegalStateException("Unable to load OpenCV cascade: " + resourceName);
        }
        return classifier;
    }

    private static Rect largestFace(RectVector faces) {
        Rect largest = faces.get(0);
        for (long i = 1; i < faces.size(); i++) {
            Rect face = faces.get(i);
            if (face.width() * face.height() > largest.width() * largest.height()) {
                largest = face;
            }
        }
        return copyRect(largest);
    }

    private static Rect scaledRect(Rect parent, double x, double y, double width, double height) {
        int rectX = parent.x() + (int) Math.round(parent.width() * x);
        int rectY = parent.y() + (int) Math.round(parent.height() * y);
        int rectWidth = Math.max(1, (int) Math.round(parent.width() * width));
        int rectHeight = Math.max(1, (int) Math.round(parent.height() * height));
        return new Rect(rectX, rectY, rectWidth, rectHeight);
    }

    private static Rect copyRect(Rect rect) {
        return new Rect(rect.x(), rect.y(), rect.width(), rect.height());
    }

    private static double scoreSad(double smileScore, double mouthOpen, double mouthWide) {
        return clamp01((1.0 - smileScore) * 0.48 + Math.max(0.0, 0.34 - mouthWide) * 1.15 + Math.max(0.0, 0.24 - mouthOpen) * 0.45 - 0.20);
    }

    private static double frontalQuality(double eyePresence, double faceAspect, double centerOffset, double headYaw) {
        double aspectQuality = 1.0 - Math.min(1.0, Math.abs(faceAspect - 0.76) / 0.34);
        double yawQuality = 1.0 - clamp01(headYaw);
        return clamp01(0.14 + eyePresence * 0.40 + (1.0 - centerOffset) * 0.22 + aspectQuality * 0.10 + yawQuality * 0.14);
    }

    private static double centerOffset(Rect face, int frameWidth, int frameHeight) {
        double faceCenterX = face.x() + face.width() / 2.0;
        double faceCenterY = face.y() + face.height() / 2.0;
        double dx = Math.abs(faceCenterX - frameWidth / 2.0) / Math.max(1.0, frameWidth / 2.0);
        double dy = Math.abs(faceCenterY - frameHeight / 2.0) / Math.max(1.0, frameHeight / 2.0);
        return clamp01(Math.hypot(dx, dy) / 1.35);
    }

    private static double smoothMetric(double oldValue, double newValue, double alpha) {
        return oldValue + (newValue - oldValue) * clamp01(alpha);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static final class LandmarkRefiner {
        private final Facemark facemark;

        private LandmarkRefiner(Facemark facemark) {
            this.facemark = facemark;
        }

        static LandmarkRefiner create() {
            String configuredPath = firstNonBlank(
                    System.getProperty("facetrack.client2.landmark.model", ""),
                    System.getProperty("facetrack.landmark.model", "")
            );
            Path modelPath = configuredPath.isBlank() ? defaultLandmarkPath() : Path.of(configuredPath);
            if (modelPath == null || !Files.isRegularFile(modelPath.toAbsolutePath().normalize())) {
                return new LandmarkRefiner(null);
            }

            try {
                Facemark loadedFacemark = createFacemarkLBF();
                loadedFacemark.loadModel(modelPath.toAbsolutePath().normalize().toString());
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

                EyeSignals eyes = landmarkEyes(points);
                return new LandmarkEstimate(landmarkMouth(points, face), eyes, landmarkSignals(points, face));
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

            double open = clamp01(((innerHeight - 0.010) * 10.5)
                    + ((outerHeight - 0.046) * 3.8)
                    + ((opennessRatio - 0.052) * 1.6));
            double wide = clamp01(((mouthWidth - 0.305) * 3.4)
                    - (open * 0.20)
                    + (Math.max(0.0, upturn) * 0.85));
            double smile = clamp01(((upturn - 0.0015) * 20.0)
                    + ((mouthWidth - 0.335) * 0.95)
                    - (open * 0.12));
            double sad = clamp01(((downturn - 0.0015) * 21.5)
                    + ((0.305 - mouthWidth) * 0.28)
                    - (open * 0.28));
            return new MouthMetrics(open, wide, smile, sad);
        }

        private static EyeSignals landmarkEyes(Point2fVector points) {
            double left = eyeAspectRatio(points, 36, 37, 38, 39, 40, 41);
            double right = eyeAspectRatio(points, 42, 43, 44, 45, 46, 47);
            double leftOpen = clamp01((left - 0.14) / 0.11);
            double rightOpen = clamp01((right - 0.14) / 0.11);
            double leftClosed = clamp01((0.19 - left) / 0.07);
            double rightClosed = clamp01((0.19 - right) / 0.07);
            double blink = Math.min(leftClosed, rightClosed);
            double wink = Math.max(leftClosed * rightOpen, rightClosed * leftOpen);
            double eyePresence = clamp01(((leftOpen >= 0.35 ? 1.0 : 0.0) + (rightOpen >= 0.35 ? 1.0 : 0.0)) / 2.0);
            return new EyeSignals(eyePresence, blink, wink, leftClosed, rightClosed);
        }

        private static LandmarkSignals landmarkSignals(Point2fVector points, Rect face) {
            double faceWidth = Math.max(1.0, face.width());
            double faceHeight = Math.max(1.0, face.height());
            double leftBrowY = averageY(points, 17, 21);
            double rightBrowY = averageY(points, 22, 26);
            double leftEyeY = averageY(points, 36, 41);
            double rightEyeY = averageY(points, 42, 47);
            double leftBrowGap = (leftEyeY - leftBrowY) / faceHeight;
            double rightBrowGap = (rightEyeY - rightBrowY) / faceHeight;
            double innerBrowDistance = distance(points.get(21), points.get(22)) / faceWidth;

            double leftBrowRaise = clamp01((leftBrowGap - 0.105) * 8.0);
            double rightBrowRaise = clamp01((rightBrowGap - 0.105) * 8.0);
            double browPinch = clamp01((0.125 - innerBrowDistance) * 8.5);
            double leftBrowSad = clamp01((0.092 - leftBrowGap) * 12.0);
            double rightBrowSad = clamp01((0.092 - rightBrowGap) * 12.0);

            Point2f noseTip = points.get(30);
            double jawCenterX = (points.get(0).x() + points.get(16).x()) / 2.0;
            double noseOffset = Math.abs(noseTip.x() - jawCenterX) / faceWidth;
            double leftEyeWidth = distance(points.get(36), points.get(39));
            double rightEyeWidth = distance(points.get(42), points.get(45));
            double eyeAsymmetry = Math.abs(leftEyeWidth - rightEyeWidth) / Math.max(1.0, leftEyeWidth + rightEyeWidth);
            double headYaw = clamp01(Math.max(noseOffset * 3.2, eyeAsymmetry * 2.2));

            return new LandmarkSignals(leftBrowRaise, rightBrowRaise, browPinch, browPinch, leftBrowSad, rightBrowSad, headYaw);
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

        private static String firstNonBlank(String first, String second) {
            if (first != null && !first.isBlank()) {
                return first.trim();
            }
            return second == null ? "" : second.trim();
        }

        private static Path defaultLandmarkPath() {
            Path local = Path.of("models", "lbfmodel.yaml");
            if (Files.isRegularFile(local)) {
                return local;
            }

            Path originalClient = Path.of("..", "client", "models", "lbfmodel.yaml");
            if (Files.isRegularFile(originalClient)) {
                return originalClient;
            }
            return null;
        }
    }

    private record LandmarkEstimate(MouthMetrics mouth, EyeSignals eyeSignals, LandmarkSignals signals) {
    }

    private record LandmarkSignals(
            double leftBrowRaise,
            double rightBrowRaise,
            double leftBrowFurrow,
            double rightBrowFurrow,
            double leftBrowSad,
            double rightBrowSad,
            double headYaw
    ) {
        static final LandmarkSignals NONE = new LandmarkSignals(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    private record EyeSignals(double eyePresence, double blink, double wink, double leftClosed, double rightClosed) {
        private EyeSignals withCascadeFallback(EyeSignals fallback) {
            double fallbackLeftOpen = 1.0 - fallback.leftClosed();
            double fallbackRightOpen = 1.0 - fallback.rightClosed();
            double leftClosedScore = Math.max(leftClosed, fallback.leftClosed() > 0.65 && rightClosed < 0.45 ? fallback.leftClosed() : 0.0);
            double rightClosedScore = Math.max(rightClosed, fallback.rightClosed() > 0.65 && leftClosed < 0.45 ? fallback.rightClosed() : 0.0);
            double blinkScore = Math.max(blink, Math.min(leftClosedScore, rightClosedScore));
            double winkScore = Math.max(wink, Math.max(leftClosedScore * fallbackRightOpen, rightClosedScore * fallbackLeftOpen));
            return new EyeSignals(Math.max(eyePresence, fallback.eyePresence()), blinkScore, winkScore, leftClosedScore, rightClosedScore);
        }
    }

    private record MouthMetrics(double open, double wide, double smile, double sad) {
        private MouthMetrics blend(MouthMetrics other, double otherWeight) {
            double weight = clamp01(otherWeight);
            double ownWeight = 1.0 - weight;
            return new MouthMetrics(
                    clamp01(open * ownWeight + other.open() * weight + Math.max(0.0, other.open() - open) * 0.12),
                    clamp01(wide * ownWeight + other.wide() * weight + Math.max(0.0, other.wide() - wide) * 0.08),
                    clamp01(smile * ownWeight + other.smile() * weight),
                    clamp01(sad * ownWeight + other.sad() * weight)
            );
        }
    }
}
