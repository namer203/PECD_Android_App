# PECD_Android_App
Energy optimization of an application
This project presents an energy-efficient Android application for spoken keyword
recognition using a TensorFlow Lite neural network. The baseline system performs continuous
audio recording and inference, resulting in unnecessary energy consumption, especially during
silence. To address this, the application includes a lightweight voice activity detection mechanism based on signal energy to filter out silent or non-speech audio segments and trigger inference only when speech is detected. Additional optimizations include adaptive sensitivity modes
for different acoustic environments, and lifecycle-aware foreground/background service management. The application logs recognized keywords and prediction statistics locally and uploads them to cloud storage in a batched and background-efficient manner. Experimental profiling using both Android studio profiler power measurement tools demonstrates a significant reduction in energy consumption with some impact on recognition accuracy.
