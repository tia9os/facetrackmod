package com.facetrack.client;

import static org.bytedeco.opencv.global.opencv_core.getNumThreads;
import static org.bytedeco.opencv.global.opencv_core.haveOpenCL;
import static org.bytedeco.opencv.global.opencv_core.setNumThreads;
import static org.bytedeco.opencv.global.opencv_core.setUseOpenCL;
import static org.bytedeco.opencv.global.opencv_core.setUseOptimized;
import static org.bytedeco.opencv.global.opencv_core.useOpenCL;

enum HardwareAccelerationMode {
    CPU("CPU"),
    OPENCL("OpenCL GPU"),
    AUTO("Auto");

    private final String label;

    HardwareAccelerationMode(String label) {
        this.label = label;
    }

    OpenCvRuntime apply(int requestedThreads) {
        int threads = Math.max(1, requestedThreads);
        setUseOptimized(true);
        setNumThreads(threads);

        return switch (this) {
            case CPU -> {
                setUseOpenCL(false);
                yield new OpenCvRuntime("CPU, OpenCV threads " + getNumThreads());
            }
            case OPENCL -> {
                boolean available = haveOpenCL();
                setUseOpenCL(available);
                yield new OpenCvRuntime(available
                        ? "OpenCL GPU enabled, OpenCV threads " + getNumThreads()
                        : "OpenCL unavailable, CPU threads " + getNumThreads());
            }
            case AUTO -> {
                boolean available = haveOpenCL();
                setUseOpenCL(available);
                yield new OpenCvRuntime(useOpenCL()
                        ? "Auto: OpenCL GPU, OpenCV threads " + getNumThreads()
                        : "Auto: CPU, OpenCV threads " + getNumThreads());
            }
        };
    }

    @Override
    public String toString() {
        return label;
    }
}

record OpenCvRuntime(String status) {
}
