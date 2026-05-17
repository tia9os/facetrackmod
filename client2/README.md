# Face Expression Client 2

Experimental rewrite of the face-expression client using JavaCV/OpenCV for camera and feature extraction plus a DeepLearning4J classifier for expression decisions.

## Run

```bash
./gradlew -p client2 run
```

Start Minecraft with the mod first. The client sends the same local UDP packet format as `./client`:

```text
127.0.0.1:34321
```

## How It Works

- OpenCV captures the webcam and detects the largest face.
- OpenCV extracts compact mouth, smile, eye, motion, and quality features.
- DeepLearning4J runs a small neural network over those features.
- The UI can collect calibration samples for the selected expression and fine-tune the model.
- The result is mapped to `facetrack_parts` packets for the Minecraft mod.

The built-in DL4J model is synthetic-pretrained so it works immediately, but it is intended to be improved with calibration samples in your camera setup.

## Options

Gradle forwards `facetrack.*` system properties to the app:

```bash
./gradlew -p client2 run \
  -Dfacetrack.camera=0 \
  -Dfacetrack.camera.width=960 \
  -Dfacetrack.camera.height=540 \
  -Dfacetrack.bridge.port=34321 \
  -Dfacetrack.client2.landmark.model=../client/models/lbfmodel.yaml \
  -Dfacetrack.client2.model=models/expression-classifier.zip
```

If no landmark path is set, `client2` tries `models/lbfmodel.yaml` and then `../client/models/lbfmodel.yaml`.

## Calibration

1. Start the camera.
2. Pick an expression in the dropdown.
3. Hold that expression and press **Calibrate sample** repeatedly, or use **Auto 20** to capture 20 samples.
4. The model is saved to `client2/models/expression-classifier.zip`.

Use **Reset AI model** if calibration becomes biased.
