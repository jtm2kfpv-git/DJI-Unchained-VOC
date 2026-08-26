# N3 Local View 0.1 archive verification

The preserved hardware-confirmed debug APK reports:

```text
applicationId: local.n3view.debug
versionCode: 1
versionName: 0.1.0-probe-debug
```

The source snapshot builds with Android API 36 and Java 17. It retains the original eight protocol and H.264 unit tests. The two control packets remain byte-exact against the pinned upstream reference, with lengths 55 and 37 bytes.

The debug and unsigned release APKs declare no Android permissions. The debug APK is v2-signed with the original Android debug certificate.

## Preserved APK hashes

```text
1D1F0F12A5D9E316DE6747AF4A8BE3DAD3B313CC64083AC947A7415C5CBDBC8E  N3-Local-View-0.1.0-hardware-confirmed-debug.apk
A902CB21AE19E82263C1C9EAE35862C6272F9F4A2809204B58169CCD54898A51  N3-Local-View-0.1.0-hardware-confirmed-release-unsigned.apk
```
