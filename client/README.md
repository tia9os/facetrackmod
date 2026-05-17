# Face Expression Client

Standalone Java desktop client for local webcam-based facial expression tracking.

## Run

From the repository root:

```bash
./gradlew -p client run
```

The app opens a Swing UI with profile, camera, tracker backend, microphone talking detection, quality and OpenCV runtime settings, per-expression calibration, live video, expression status, confidence, FPS, recording, snapshot actions, and Minecraft bridge status. It can classify neutral, happy, funny, sad, surprised, talking, blinking, winking, and focused states.

## Accuracy

The tracker learns a neutral baseline when the camera starts. Keep a neutral face visible for the first few seconds, or choose **Neutral** and press **Calibrate expression** at any time until the calibration status reaches 100%. This baseline reduces false positives caused by lighting, camera angle, and each person's natural mouth shape.

After neutral calibration is ready, choose any supported expression and press **Calibrate expression** to capture a runtime profile for it. The classifier blends those profiles with its built-in heuristics so happy, sad, surprised, talking, blinking, winking, and focused can be tuned to the current person and camera setup.

Talking detection uses mouth movement over time, while surprised detection expects a steadier open-mouth shape. Enable **Mic talking** and choose a **Mic** device to use microphone voice activity as an extra signal: speech can promote the expression to talking, and silence suppresses false talking detections from camera jitter. The microphone panel shows sensitivity and amplification sliders plus live level, noise-floor, and speech-gate gauges so you can tune whether the selected input is usable. Higher sensitivity uses a more aggressive gate for quieter microphones, and amplification boosts the measured microphone level before speech detection. The final expression also goes through temporal score voting so one-frame spikes are less likely to flip the result.

The toolbar includes off-axis stability controls for cases where the user turns away from the camera:

- **Off-axis**: `Hold last`, `Neutral`, or `No face`.
- **Hold ms**: how long `Hold last` keeps the most recent stable expression.
- **Min Q**: minimum frontal-quality percentage before normal expression classification is trusted.

When frontal quality is below the threshold, the tracker suppresses high-risk expressions, pauses calibration learning, and either holds the last stable expression, falls back to neutral, or reports no face. A bundled profile-face cascade detects side-facing faces so the client can enter this low-confidence state instead of flickering between unrelated expressions.

When an LBF landmark model is configured in `Accurate` mode, the OpenCV tracker uses 68-point landmarks as the primary mouth-shape source, plus independent left/right eye openness, independent left/right eyebrow raise/furrow/sad tilt, blink/wink scoring, and approximate head yaw. Haar cascades still provide the initial face box and provide per-eye fallback signals when no landmark model is configured.

The **Tracker** dropdown also includes `Modern ONNX`. This backend can use a local facial-landmark ONNX model for OpenFace-style geometry signals. Start it with:

```bash
./gradlew -Dfacetrack.onnx.model=/path/to/landmark-model.onnx -p client run
```

You can also set the model path in `client/settings.properties`:

```properties
facetrack.onnx.model=models/fan2_68_landmark.onnx
```

The `-Dfacetrack.onnx.model=...` command-line value takes precedence over the settings file.

The ONNX backend currently supports common single-output 68-point or 98-point coordinate landmark models with a 4D float image input in NCHW or NHWC layout. Coordinates may be normalized `0..1`, normalized `-1..1`, or input-pixel coordinates. If the model is missing or has an unsupported shape, the client falls back to the OpenCV tracker and shows the fallback reason in **Performance**. Keep a neutral face visible while the ONNX neutral baseline reaches 100%; press **Calibrate expression** with **Neutral** selected to relearn that baseline if the output gets biased.

Completed calibrations are saved automatically to the selected profile name, camera index, and quality mode. The matching profile is loaded when the camera starts, so calibrated expressions persist across app restarts without mixing users or camera setups.

## Performance

The toolbar includes three processing controls:

- **Tracker**: `OpenCV` is the default heuristic tracker. `Modern ONNX` uses a configured landmark ONNX model when available and falls back to OpenCV otherwise.
- **Quality**: `Fast` uses lower camera resolution and cheaper cascade settings, `Balanced` is the default, and `Accurate` asks for a higher camera resolution, tighter cascade scans, CLAHE contrast enhancement, adaptive mouth thresholding, and optional landmark refinement.
- **Backend**: `CPU`, `OpenCL GPU`, or `Auto`. OpenCL is enabled only when the bundled OpenCV runtime reports that it is available; otherwise the client falls back to CPU.
- **Threads**: sets OpenCV's native CPU thread budget. The client uses a latest-frame analysis pipeline, so camera capture and Swing UI updates do not block on expression analysis; stale frames are dropped when analysis is still busy.

For the heavier landmark path in `Accurate` mode, provide an OpenCV LBF facemark model:

```bash
./gradlew -Dfacetrack.landmark.model=/path/to/lbfmodel.yaml -p client run
```

Or set it in `client/settings.properties`:

```properties
facetrack.landmark.model=models/lbfmodel.yaml
```

The `-Dfacetrack.landmark.model=...` command-line value takes precedence over the settings file.

Without that model, `Accurate` still uses the higher-cost cascade, contrast, and mouth-analysis path.

## Minecraft bridge

While the camera is running, the client sends expression samples to the Fabric mod over localhost UDP:

```text
127.0.0.1:34321
```

The Fabric client receives those samples and sends them to the server with Fabric play networking. A modded server then broadcasts each player's expression to other modded clients, where it is rendered as a generated face overlay texture on that player's head.

The client sends `facetrack_parts` packets with the final expression plus separate mouth, left-eye, right-eye, left-eyebrow, and right-eyebrow states. Mouth, eye, and eyebrow part states are classified from their own signals, so they can differ from the final whole-face expression. The mod still accepts the older `facetrack` packet format for compatibility.

Both the Minecraft client and server need this mod installed for multiplayer sync. For local testing, start Minecraft with the mod, then run `./gradlew -p client run` and start the camera.

If you need a different localhost port, start both Java processes with the same `-Dfacetrack.bridge.port=<port>` value.

## Output

Recordings and snapshots are written locally under:

```text
client/sessions/
```

Calibration profiles are written locally under:

```text
client/profiles/<profile>-camera<index>-<quality>.properties
```

For backward compatibility, `client/profiles/default.properties` is still loaded for the default profile on camera 0 in `Balanced` mode if the newer scoped file does not exist.

Client UI settings are saved locally under:

```text
client/settings.properties
```

This includes the selected profile, camera index, tracker backend, quality, OpenCV runtime backend, OpenCV thread count, off-axis options, optional ONNX and LBF model paths, microphone talking toggle, selected microphone device, microphone sensitivity, microphone amplification, selected calibration expression, and window position/size.

Each recording creates a timestamped CSV file with expression estimates, confidence, face bounds, eye/smile counts, smile, mouth-open, mouth-wide, sad, blink, wink, part states, FPS, and frontal-quality scores. Snapshots are saved as annotated PNG files.

## Notes

This client uses OpenCV Haar cascades, optional LBF facemarks, and lightweight local heuristics for expression estimates. It does not upload video or tracking data; only compact expression scores are sent to the local Minecraft client.
