# Consumer keep rules of :platform, applied when the app is minified.

# argon2kt (native Argon2 via JNI) ships no rules of its own. Its native library
# looks up the class ByteBufferTarget by name and stores the hash output in the
# private field byteBuffer. Kotlin code never writes that field, so without this
# rule R8 may remove it or treat it as always null. The JNI methods of Argon2Jni
# are kept by the default rule for native methods.
-keep class com.lambdapioneer.argon2kt.ByteBufferTarget {
    private java.nio.ByteBuffer byteBuffer;
}

# SyncWorker needs no rule here: WorkManager creates workers by class name and
# ships the keep rule for the (Context, WorkerParameters) constructor itself.
