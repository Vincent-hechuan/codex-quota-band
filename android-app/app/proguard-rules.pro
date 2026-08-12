# ML Kit discovers these registrars by class name from manifest metadata. Keep their public
# constructors in optimized release builds so the in-app scanner can initialise on real devices.
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar {
    public <init>();
}
-keep class com.google.mlkit.vision.common.internal.VisionCommonRegistrar {
    public <init>();
}
-keep class com.google.mlkit.vision.barcode.internal.BarcodeRegistrar {
    public <init>();
}
