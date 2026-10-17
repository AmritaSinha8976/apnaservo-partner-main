# Apna Servo Partner Android

Open this folder in Android Studio:

`E:\New project\.vscode\ApnaServo\partner-app`

Do not open `MainActivity.java` directly and do not choose the parent `New project` folder. If Android Studio keeps opening multiple apps, close that window and double-click `open-project-folder-only.bat`.

This is a separate native Android partner app for Apna Servo. It shows incoming service bookings, lets the partner accept a request, and keeps accepted jobs in the ongoing jobs section.

Production builds must point to an HTTPS ApnaServo backend. Cleartext `http://` local fallback URLs are intentionally not used in the app.
