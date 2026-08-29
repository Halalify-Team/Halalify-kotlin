# Remove verbose ML and overlay telemetry from release bytecode as a
# defense-in-depth measure. The source also guards these calls with
# BuildConfig.DEBUG so debug builds retain the detailed diagnostics.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}