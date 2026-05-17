package com.facetrack.client2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;

final class NeuralExpressionModel {
    private static final int OUTPUT_SIZE = ExpressionLabel.TRAINABLE.length;
    private static final int SYNTHETIC_SAMPLES_PER_LABEL = 64;
    private static final int SYNTHETIC_EPOCHS = 170;
    private static final long SEED = 42L;

    private final Path modelPath;
    private final Random random = new Random(SEED);
    private MultiLayerNetwork network;
    private volatile String status;

    private NeuralExpressionModel(Path modelPath, MultiLayerNetwork network, String status) {
        this.modelPath = modelPath;
        this.network = network;
        this.status = status;
    }

    static NeuralExpressionModel loadOrCreate() {
        Path modelPath = Paths.get(System.getProperty("facetrack.client2.model", "models/expression-classifier.zip"));
        if (Files.isRegularFile(modelPath)) {
            try {
                MultiLayerNetwork restored = ModelSerializer.restoreMultiLayerNetwork(modelPath.toFile());
                return new NeuralExpressionModel(modelPath, restored, "Loaded AI model " + modelPath);
            } catch (IOException | RuntimeException exception) {
                NeuralExpressionModel model = new NeuralExpressionModel(modelPath, createNetwork(), "Rebuilt AI model after load error");
                model.pretrainAndSave();
                return model;
            }
        }

        NeuralExpressionModel model = new NeuralExpressionModel(modelPath, createNetwork(), "Created AI model " + modelPath);
        model.pretrainAndSave();
        return model;
    }

    synchronized NeuralPrediction classify(ExpressionFeatures features) {
        if (features == null) {
            return new NeuralPrediction(ExpressionLabel.NO_FACE, 0.0);
        }

        INDArray input = Nd4j.create(new double[][] {features.toArray()});
        INDArray output = network.output(input, false);
        int index = 0;
        double confidence = output.getDouble(0, 0);
        for (int i = 1; i < OUTPUT_SIZE; i++) {
            double score = output.getDouble(0, i);
            if (score > confidence) {
                confidence = score;
                index = i;
            }
        }
        return new NeuralPrediction(ExpressionLabel.TRAINABLE[index], confidence);
    }

    synchronized void train(ExpressionLabel label, Collection<ExpressionFeatures> samples, int epochs) {
        if (!isTrainable(label) || samples == null || samples.isEmpty()) {
            return;
        }

        DataSet dataSet = buildCalibrationData(label, samples);
        int iterations = Math.max(1, epochs);
        for (int i = 0; i < iterations; i++) {
            network.fit(dataSet);
        }
        save();
        status = String.format(Locale.US, "Trained %s on %d sample(s)", label.displayName(), samples.size());
    }

    synchronized void reset() {
        network = createNetwork();
        pretrainAndSave();
        status = "Reset AI model";
    }

    Path modelPath() {
        return modelPath;
    }

    String status() {
        return status;
    }

    private void pretrainAndSave() {
        DataSet pretrain = buildSyntheticData();
        for (int i = 0; i < SYNTHETIC_EPOCHS; i++) {
            network.fit(pretrain);
        }
        save();
    }

    private void save() {
        try {
            Path parent = modelPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ModelSerializer.writeModel(network, modelPath.toFile(), true);
        } catch (IOException exception) {
            status = "AI model save failed: " + exception.getMessage();
        }
    }

    private DataSet buildSyntheticData() {
        List<double[]> rows = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();
        for (int labelIndex = 0; labelIndex < OUTPUT_SIZE; labelIndex++) {
            double[] archetype = archetype(ExpressionLabel.TRAINABLE[labelIndex]);
            for (int sample = 0; sample < SYNTHETIC_SAMPLES_PER_LABEL; sample++) {
                rows.add(jitter(archetype, 0.08));
                labels.add(labelIndex);
            }
        }
        return toDataSet(rows, labels);
    }

    private DataSet buildCalibrationData(ExpressionLabel target, Collection<ExpressionFeatures> samples) {
        List<double[]> rows = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();

        int targetIndex = labelIndex(target);
        for (ExpressionFeatures sample : samples) {
            if (sample == null) {
                continue;
            }
            double[] vector = sample.toArray();
            for (int i = 0; i < 18; i++) {
                rows.add(jitter(vector, 0.035));
                labels.add(targetIndex);
            }
        }

        for (int labelIndex = 0; labelIndex < OUTPUT_SIZE; labelIndex++) {
            double[] archetype = archetype(ExpressionLabel.TRAINABLE[labelIndex]);
            for (int i = 0; i < 5; i++) {
                rows.add(jitter(archetype, 0.075));
                labels.add(labelIndex);
            }
        }

        return toDataSet(rows, labels);
    }

    private DataSet toDataSet(List<double[]> rows, List<Integer> labels) {
        INDArray features = Nd4j.zeros(rows.size(), ExpressionFeatures.SIZE);
        INDArray outputs = Nd4j.zeros(rows.size(), OUTPUT_SIZE);
        for (int row = 0; row < rows.size(); row++) {
            double[] vector = rows.get(row);
            for (int column = 0; column < ExpressionFeatures.SIZE; column++) {
                features.putScalar(row, column, vector[column]);
            }
            outputs.putScalar(row, labels.get(row), 1.0);
        }
        return new DataSet(features, outputs);
    }

    private double[] jitter(double[] source, double spread) {
        double[] vector = source.clone();
        for (int i = 0; i < vector.length; i++) {
            vector[i] = clamp01(vector[i] + random.nextGaussian() * spread);
        }
        return vector;
    }

    private static MultiLayerNetwork createNetwork() {
        MultiLayerConfiguration configuration = new NeuralNetConfiguration.Builder()
                .seed(SEED)
                .weightInit(WeightInit.XAVIER)
                .updater(new Adam(0.01))
                .list()
                .layer(new DenseLayer.Builder()
                        .nIn(ExpressionFeatures.SIZE)
                        .nOut(32)
                        .activation(Activation.RELU)
                        .build())
                .layer(new DenseLayer.Builder()
                        .nOut(18)
                        .activation(Activation.RELU)
                        .build())
                .layer(new OutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .nOut(OUTPUT_SIZE)
                        .activation(Activation.SOFTMAX)
                        .build())
                .build();
        MultiLayerNetwork created = new MultiLayerNetwork(configuration);
        created.init();
        return created;
    }

    private static boolean isTrainable(ExpressionLabel label) {
        return labelIndex(label) >= 0;
    }

    private static int labelIndex(ExpressionLabel label) {
        for (int i = 0; i < ExpressionLabel.TRAINABLE.length; i++) {
            if (ExpressionLabel.TRAINABLE[i] == label) {
                return i;
            }
        }
        return -1;
    }

    private static double[] archetype(ExpressionLabel label) {
        return switch (label) {
            case HAPPY -> values(0.90, 0.20, 0.58, 0.03, 0.08, 0.92, 0.03, 0.02, 0.04, 0.04, 0.45, 0.76, 0.12, 0.88);
            case FUNNY -> values(0.62, 0.46, 0.86, 0.05, 0.38, 0.75, 0.18, 0.52, 0.12, 0.52, 0.45, 0.76, 0.14, 0.80);
            case SAD -> values(0.04, 0.13, 0.17, 0.86, 0.04, 0.70, 0.10, 0.05, 0.12, 0.12, 0.45, 0.74, 0.16, 0.76);
            case SURPRISED -> values(0.10, 0.94, 0.72, 0.04, 0.18, 0.95, 0.02, 0.02, 0.02, 0.02, 0.46, 0.73, 0.10, 0.90);
            case TALKING -> values(0.22, 0.58, 0.48, 0.08, 0.92, 0.85, 0.06, 0.03, 0.07, 0.07, 0.44, 0.75, 0.14, 0.84);
            case BLINKING -> values(0.07, 0.08, 0.12, 0.06, 0.04, 0.12, 0.96, 0.06, 0.92, 0.92, 0.44, 0.75, 0.13, 0.78);
            case WINKING -> values(0.24, 0.14, 0.26, 0.04, 0.06, 0.50, 0.28, 0.96, 0.12, 0.88, 0.45, 0.76, 0.12, 0.80);
            case FOCUSED -> values(0.04, 0.06, 0.10, 0.18, 0.02, 0.58, 0.08, 0.04, 0.14, 0.14, 0.45, 0.72, 0.10, 0.82);
            case NEUTRAL, NO_FACE -> values(0.08, 0.08, 0.13, 0.06, 0.03, 0.88, 0.04, 0.03, 0.05, 0.05, 0.45, 0.76, 0.12, 0.86);
        };
    }

    private static double[] values(double... values) {
        return values;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
