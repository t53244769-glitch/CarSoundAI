# CarSoundAI

CarSoundAI aims to bring a measurement-driven car-audio auto-tuning workflow to an Android app, with an AI decision layer that interprets acoustic measurements and recommends corrections. The goal is not to copy proprietary internals of Mstick Sound Studio, but to build a transparent, testable workflow with similar high-level stages.

## Target workflow

1. **Prepare the cabin:** park safely, close windows, turn off HVAC and disable existing vehicle EQ/enhancement effects where possible.
2. **Set a repeatable measurement level:** guide the user to choose a suitable car volume before measuring. Record volume-stage labels so measurements can be compared consistently.
3. **Measure the speaker/cabin response:** use known test signals routed through the car audio path and capture the result with a microphone. Check signal level, clipping, noise, and repeatability.
4. **Analyze detailed frequency bands:** estimate response across multiple bands rather than relying only on broad bass/mid/treble averages. Account for the limitations of phone microphones and cabin reflections.
5. **AI decision layer:** infer likely issues and confidence from measurements, target sound, vehicle/listener preferences, and measurement quality. Do not treat the spectrum of arbitrary music alone as proof of a speaker defect.
6. **Recommend conservative EQ changes:** generate a proposed profile or manual vehicle-EQ instructions, avoid excessive boosts, then repeat the measurement.
7. **Closed-loop verification:** compare before/after measurements and retain a change only when the measurement is repeatable and the result is preferred by the listener.

## Current prototype

- Five-step beginner tuning guide and simple presets.
- Bass, midrange and treble controls for the app's own test-tone playback.
- A/B storage for comparing settings.
- Five-second microphone capture while music is playing through the car speakers.
- FFT-based estimate of average spectral energy in bass (<250 Hz), midrange (250 Hz–4 kHz), and treble (>4 kHz).
- Initial bass/treble adjustment suggestions based on those relative measurements.
- Volume-stage labels (1–10) with separate measurement records for comparison.
- Guidance for manually applying suggested changes in the vehicle's own EQ.

## Important limitations

This is an early signal-analysis prototype, not yet a trained AI model or a calibrated room-measurement system. The current music-based analysis is only a rough starting point. A phone microphone hears music plus cabin reflections, background noise, microphone response, and automatic gain control. The spectrum of a song also reflects its arrangement and mastering, so it cannot by itself prove that the vehicle has a response problem.

The volume-stage feature records which car-volume setting was measured; it does not control the vehicle volume itself. The current app does not yet perform a calibrated sweep/pink-noise measurement or automatically apply a generated EQ profile to the vehicle's audio DSP.

Android apps generally cannot directly change the EQ of arbitrary music apps or a vehicle's Bluetooth audio stream. In the current design, users apply recommendations in the car stereo's own EQ.

## Roadmap

1. Replace broad three-band-only analysis with repeatable known-signal measurement and multi-band response estimation.
2. Add calibration support where device/microphone calibration data is available; detect silence, noise, clipping, and unstable measurements.
3. Build the diagnosis/recommendation layer with confidence values and listener goals (neutral, bass-forward, vocal clarity). A real trained model will require representative measurement data and validation; until then, label rule-based decisions honestly.
4. Add safe, bounded EQ recommendations and a guided before/after remeasurement loop.
5. Validate across Android devices, car cabins, head units, volume levels, and music genres before calling the results production-ready.

## Reference workflow

The public Mstick documentation describes its Auto Tune workflow as setting up the cabin and phone position, adjusting the volume, measuring the car speakers, and generating a customized sound profile. It recommends a quiet parked car, windows closed, HVAC off, and holding the phone with its microphone unobstructed near face height. CarSoundAI uses these as general measurement-practice references; it does not claim to reproduce Mstick's proprietary implementation.

Reference: https://www.manual.m-gears.com/soundautotune

## Build

GitHub Actions builds a debug APK for each push to main. See the [Actions runs](https://github.com/t53244769-glitch/CarSoundAI/actions).
