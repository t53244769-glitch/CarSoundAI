# CarSoundAI

CarSoundAI is an Android prototype for helping drivers tune their car audio by analyzing sound captured from the car speakers.

## Current prototype

- Five-step beginner tuning guide and simple presets.
- Bass, midrange and treble controls for the app's own test-tone playback.
- A/B storage for comparing settings.
- Five-second microphone capture while music is playing through the car speakers.
- FFT-based estimate of average spectral energy in bass (<250 Hz), midrange (250 Hz–4 kHz), and treble (>4 kHz).
- Initial bass/treble adjustment suggestions based on those relative measurements.
- Guidance for manually applying suggested changes in the vehicle's own EQ.

## Important limitations

This is an early signal-analysis prototype, not a trained AI model or a calibrated room-measurement system. A phone microphone hears the music plus cabin reflections, road/ventilation noise, microphone response, and automatic gain control. The spectrum of a single song also reflects its arrangement and mastering, so it cannot by itself prove that the vehicle has a response problem. Treat suggestions as starting points, not objectively optimal settings.

Android apps generally cannot directly change the EQ of arbitrary music apps or a vehicle's Bluetooth audio stream. In the current design, users apply recommendations in the car stereo's own EQ.

## Roadmap toward the intended product

1. **Measurement reliability:** use overlapping analysis frames, stable level handling, input clipping/silence checks, and repeatable measurement guidance.
2. **Calibration:** add a known test signal and microphone/device calibration support; distinguish music content from cabin response as far as practical.
3. **More useful diagnosis:** analyze narrower frequency bands and identify likely boominess, harshness, vocal masking, and spectral imbalance with confidence/uncertainty indicators.
4. **Closed-loop tuning:** guide users through small EQ changes, remeasure, and compare before/after results instead of claiming a single-pass optimum.
5. **Preference-aware recommendations:** capture listener goals (neutral, bass-forward, vocal clarity) and combine them with repeated measurements.
6. **Validation:** test on different Android devices, car cabins, head units, and music genres; document known limitations before calling it production-ready.

## Build

GitHub Actions builds a debug APK for each push to main. See the [Actions runs](https://github.com/t53244769-glitch/CarSoundAI/actions).
