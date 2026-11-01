# Consumer keep rules of :platform, applied when the app is minified.
# argon2kt calls into native code and creates its result objects from JNI by
# class and member names; it ships no rules of its own.
-keep class com.lambdapioneer.argon2kt.** { *; }
