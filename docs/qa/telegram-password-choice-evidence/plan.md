# Telegram keyboard choice acceptance

TV emulator emulator-5554, API36 arm64 1080p; isolated .passwordqa build, unavailable local API. Native real password screen with controlled status and callback spies; actual downloaded pass.mp4.

1. Initial choice must decode video and focus TV keyboard, without sending a phone request or submitting a password. Observe a real loop within 15s.
2. Physical Down/Select chooses Phone keyboard and sends one request. Recomposition must not send another request. Back returns to choices and cancels that phone request. Same video continues.
3. Physical Select chooses TV keyboard; masked input and TV IME appear. Enter a dummy password and activate Sign in; expect exact callback value and cleared input. No phone request from TV selection.
4. Return to choices; missing video falls back to branding and choices still work. Connected status invokes automatic completion and releases video.

Each state assertion is bounded to 6s; total native run bound 60s. No automatic retries. Retain failed attempts and diagnose fixture/product issues separately. Screenshots before password typing only. No live Telegram credentials or password submission. Real cross-device encrypted notification delivery remains unverified.
