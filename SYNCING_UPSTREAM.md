# Syncing upstream

This a quick guide for how to sync @baillie/react-native-webrtc with @livekit/react-ntive-webrtc.

1. Sync `master` of this fork with upstream.
2. Checkout `bg` and then run `git merge master`.
3. Fix conflicts. Most conflicts are likely caused by the package name in package.json. Replace @livekit with @baillie.
4. Push.
