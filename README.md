# RemotePhone Direct

Experimental Android-to-Android remote screen/control MVP.

- Install the same APK on both Android phones.
- Phone B runs **Host** and grants Android screen-capture + Accessibility control.
- Phone A runs **Viewer** and connects using Phone B's reachable IP:port plus an access code.
- No clipboard sync or file transfer in this prototype.
- GitHub Actions builds a debug APK automatically on pushes to `main`.
- CI source-package reconstruction repaired for the next APK build.

> Important: this is a prototype, not production security software. Android still requires screen-capture consent on the host, and some secure/banking screens may appear blank or reject remote control.
