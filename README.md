# Selectru (CarSoundAI)

**Selectru means “choice and truth” — 선택과 진실.** The Android prototype focuses on transparent, measurement-informed car-audio tuning: users choose a sound direction, inspect measured values, and verify changes rather than trusting unexplained recommendations. It does not copy proprietary internals of Mstick Sound Studio.

## Target workflow

1. **Prepare the cabin:** park safely, close windows, turn off HVAC and disable existing vehicle EQ/enhancement effects where possible.
2. **Set a repeatable measurement level:** guide the user to choose a suitable car volume before measuring. Record volume-stage labels so measurements can be compared consistently.
3. **Measure the speaker/cabin response:** use known test signals routed through the car audio path and capture the result with a microphone. Check signal level, clipping, noise, and repeatability.
4. **Analyze detailed frequency bands:** estimate response across multiple bands rather than relying only on broad bass/mid/treble averages. Account for the limitations of phone microphones and cabin reflections.
5. **AI decision layer:** infer likely issues and confidence from measurements, target sound, vehicle/listener preferences, and measurement quality. Do not treat the spectrum of arbitrary music alone as proof of a speaker defect.
6. **Recommend conservative EQ changes:** generate a proposed profile or manual vehicle-EQ instructions, avoid excessive boosts, then repeat the measurement.
7. **Closed-loop verification:** compare before/after measurements and retain a change only when the measurement is repeatable and the result is preferred by the listener.

## Current prototype

- Selectru brand name and dark navy/blue interface styling. The supplied original brand image has not yet been retrieved from its MYBOX short link, so the original icon must not be considered applied yet.

- Five-step beginner tuning guide and simple presets with dynamic step-by-step guidance.
- Expandable, in-app Korean manual covering setup, measurement, EQ presets, A/B comparison, reading results, limitations and safety.
- Expandable sound-style preset list: balanced, bass boost, vocal, concert hall-style tone, live, club, car, studio, cinema, acoustic, podcast and night bass. These presets adjust only the three-band EQ; they do not synthesize reverb or venue acoustics.
- Bass, midrange and treble controls for the app's own test-signal playback; current EQ values persist across launches.
- Saved A/B profiles persist across launches and can be explicitly cleared.
- Live 20-second measurement countdown and cancellation control.
- A/B storage for comparing settings.
- Built-in low-level pink-noise stimulus that can be routed through the available media output to the car audio path.
- 20-second microphone capture and FFT-based eight-band estimate (20–60, 60–125, 125–250, 250–500, 500–1k, 1–2k, 2–4k, and 4–8k Hz), plus broad bass/mid/treble summaries.
- Input-level and clipping checks, logarithmic-band-width normalization for a fairer pink-noise comparison, plus saved band measurements per selected volume stage.
- Visual eight-band spectrum bars and transparent rule-based diagnosis of the relatively highest/lowest measured bands with conservative retuning guidance; this is not a trained AI model.
- Initial bass/treble adjustment suggestions based on those relative measurements.
- Volume-stage labels (1–10) with separate measurement records saved across app restarts.
- Guidance for manually applying suggested changes in the vehicle's own EQ.
- Shareable Selectru JSON profile export/import for current EQ, A/B presets, saved volume-stage measurements, and vehicle/audio/speaker metadata (speaker configuration, brand, model, installation location, amplifier/DSP/crossover notes); no raw audio recordings are exported.
- In-app links to public reference data: AutoEq headphone frequency-response profiles, ANIR vehicle impulse-response/noise corpus, and a catalog of room impulse-response datasets.

## Important limitations

This is an early signal-analysis prototype, not yet a trained AI model or a calibrated room-measurement system. Public datasets linked in the app are reference material, not automatically trained model weights. AutoEq is headphone-focused and should not be treated as a vehicle correction profile. Check the individual dataset license, attribution requirements, permitted use, and download size before redistributing data. Imported user profiles are unverified and should be reviewed before application. The current measurement is an early pink-noise/ambient capture workflow and is only a rough starting point; it does not yet calculate a calibrated transfer function or average multiple microphone positions. A phone microphone hears music plus cabin reflections, background noise, microphone response, and automatic gain control. The spectrum of a song also reflects its arrangement and mastering, so it cannot by itself prove that the vehicle has a response problem.

The volume-stage feature records which car-volume setting was measured; it does not control the vehicle volume itself. The app can generate pink noise and estimate relative band levels, but it does not yet perform a calibrated sweep/transfer-function measurement or automatically apply a generated EQ profile to the vehicle's audio DSP.

Android apps generally cannot directly change the EQ of arbitrary music apps or a vehicle's Bluetooth audio stream. In the current design, users apply recommendations in the car stereo's own EQ.

## Roadmap

1. Upgrade the current pink-noise capture to repeatable multi-frame measurement with background-noise estimation, stability checks, and spatial averaging.
2. Add calibration support where device/microphone calibration data is available and estimate a reference-relative transfer response.
3. Build the diagnosis/recommendation layer with confidence values and listener goals (neutral, bass-forward, vocal clarity). A real trained model will require representative measurement data and validation; until then, label rule-based decisions honestly.
4. Add safe, bounded EQ recommendations and a guided before/after remeasurement loop.
5. Validate across Android devices, car cabins, head units, volume levels, and music genres before calling the results production-ready.
6. Collect consented, anonymized measurements with device/cabin metadata and quality checks; publish a versioned, license-cleared dataset before training a real model. Do not upload personal recordings or location-linked data by default.

## Reference workflow

The public Mstick documentation describes its Auto Tune workflow as setting up the cabin and phone position, adjusting the volume, measuring the car speakers, and generating a customized sound profile. It recommends a quiet parked car, windows closed, HVAC off, and holding the phone with its microphone unobstructed near face height. Selectru uses these as general measurement-practice references; it does not claim to reproduce Mstick's proprietary implementation.

Reference: https://www.manual.m-gears.com/soundautotune

## Build

GitHub Actions builds a debug APK for each push to main. See the [Actions runs](https://github.com/t53244769-glitch/CarSoundAI/actions).
