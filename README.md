# ChickenCounter (Android Kotlin)
This is a minimal Android Studio project skeleton for an offline chicken counting app using a TFLite YOLO model.

## How to use
1. Export your trained YOLOv11 model to **TFLite** from Ultralytics (Kaggle):
   ```python
   from ultralytics import YOLO
   model = YOLO('runs/detect/train/weights/best.pt')
   model.export(format='tflite')  # produces best.tflite
   ```
2. Copy `best.tflite` into `app/src/main/assets/` of this project.
3. Open the project in Android Studio, let it sync Gradle.
4. Run on device or emulator (prefer real device for camera/storage).

## Important notes
- This code assumes the TFLite model input size is **640x640** and the output shape is `[1, maxDetections, 6]` (x,y,w,h,conf,class).
  Depending on how Ultralytics exported your model, you may need to adapt **YoloTFLiteDetector.kt** preprocess/postprocess accordingly.
- For production use, run inference on a background thread and use CameraX for live camera inference.
- If your model uses INT8/UINT8 quantization, update preprocessing (byte order / normalization).
