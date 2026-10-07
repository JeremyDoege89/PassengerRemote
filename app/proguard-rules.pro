# NotificationListenerService and the foreground service are instantiated by the
# framework from the manifest, so R8 must not rename or strip them.
-keep class dev.passengerremote.host.media.MediaNotificationListener { *; }
-keep class dev.passengerremote.host.service.RemoteHostService { *; }
