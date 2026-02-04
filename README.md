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
