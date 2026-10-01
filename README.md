# USB Audio Router

An Android application that routes stereo audio from a USB audio input device (like the Saramonic SR-C2003) to the system audio output (speakers, Bluetooth, Android Auto).

## Features
- **Real-time Loopback**: Low-latency audio routing at 48kHz Stereo.
- **Android Auto Support**: Uses a MediaSession to appear as a media source in vehicles.
- **Foreground Service**: Ensures audio continues playing even when the app is in the background.
- **Robust Detection**: Automatically detects USB audio devices when plugged in.
- **Orientation Locked**: Fixed to portrait mode to prevent streaming interruptions during rotation.

## Technical Details
- **USB Host API**: Uses Android's USB host features to identify compatible hardware.
- **AudioRecord & AudioTrack**: Manages raw PCM data transfer between input and output.
- **MediaSessionCompat**: Integrates with the system media controller and volume panel.
- **Resilient Routing**: Includes retry logic for transient USB driver glitches.
