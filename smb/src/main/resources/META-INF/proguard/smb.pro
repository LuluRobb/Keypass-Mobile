# Consumer keep rules of :smb, picked up by R8 from this jar.

# smbj passes internal events (connection closed, session logged off, tree
# disconnected) over the mbassador event bus. mbassador finds the receiving methods
# by their @Handler annotation and calls them by reflection. smbj never calls these
# private methods directly, so R8 would remove them, and SMBClient, Connection and
# Session would keep closed objects in their caches (for example connectShare("IPC$")
# would return the closed pipe share of the previous share listing).
# includedescriptorclasses: mbassador picks the handler by the runtime class of the
# event, so the event classes in the handler signatures must not be merged by R8.
-keepclassmembers,includedescriptorclasses class com.hierynomus.** {
    @net.engio.mbassy.listener.Handler <methods>;
}
# mbassador reads the values of @Handler at runtime, including the defaults
# (condition, delivery, invocation, ...), which live on the annotation methods.
-keep @interface net.engio.mbassy.listener.Handler { *; }
# The invocation class named in @Handler(invocation = ...) is created with
# getConstructor(SubscriptionContext).
-keep class * extends net.engio.mbassy.dispatch.HandlerInvocation {
    public <init>(net.engio.mbassy.subscription.SubscriptionContext);
}

# mbassador only creates its EL filter (javax.el) for handlers with a condition
# expression. The smbj handlers have none, and Android has no javax.el.
-dontwarn javax.el.**
# Kerberos via JGSS (SpnegoAuthenticator) is only loaded by smbj's default
# authenticator list on a non-Android JVM. SmbConnector sets NTLM as the only
# authenticator, and Android has no org.ietf.jgss.
-dontwarn org.ietf.jgss.**
