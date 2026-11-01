# Consumer keep rules of :kdbx, picked up by R8 from this jar.
# KotpassInternals reads these kotpass members by reflection.
-keep class app.keemobile.kotpass.cryptography.format.BaseKdfProvider {
    public static final app.keemobile.kotpass.cryptography.format.BaseKdfProvider INSTANCE;
}
-keepclassmembers class app.keemobile.kotpass.models.EntryValue$Encrypted {
    private final app.keemobile.kotpass.cryptography.EncryptedValue value;
}
