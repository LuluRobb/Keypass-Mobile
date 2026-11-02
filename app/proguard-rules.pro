# R8 rules of the app module (release build).
#
# Shrinking and optimization stay on, obfuscation is off: the app is open source, so
# renaming hides nothing, and stack traces keep the real class and method names without
# a mapping file per release. Line numbers in stack traces are instruction offsets
# (R8 always encodes them for retracing with mapping.txt), and inlined or merged code
# shows up under the caller or the merge target.
-dontobfuscate

# Keep rules for libraries and for reflection in our own modules are not collected here.
# They sit next to the code that needs them:
#   :platform  platform/consumer-rules.pro (argon2kt JNI)
#   :kdbx      kdbx/src/main/resources/META-INF/proguard/kdbx.pro (kotpass internals)
#   :smb       smb/src/main/resources/META-INF/proguard/smb.pro (smbj event bus)
