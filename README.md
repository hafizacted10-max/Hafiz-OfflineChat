# Offline Chat (text only, free, no internet)

## Build (free)
1. Install Android Studio (free). File > Open > this folder.
2. Let Gradle sync (needs internet ONCE to download libraries).
3. Connect an Android phone (USB debugging on) > Run.
   Build > Build APK(s) gives app/build/outputs/apk/.
4. Install on at least 2 real phones (emulators won't work). Turn on Bluetooth + Wi-Fi + Location. Allow all permissions.
5. Type a message on one phone; it appears on the other. No SIM or internet needed.

## Works
- Auto-discovers nearby phones, relays messages through phones in between (max 10 hops)
- Saves messages; delivers later when phones meet
- "Delete all messages" button, no account, no phone number, no server

## Next steps
- Nickname screen, Rohingya/Bengali/Burmese UI text
- Private chats with QR code + libsodium encryption
- "Share app" (send APK over Nearby)
- Room database instead of file
