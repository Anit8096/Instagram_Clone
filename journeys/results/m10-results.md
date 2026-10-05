# Journey results — M10 Google-first sign-in with phone OTP (`journeys/m10-phone-auth.xml`)

Device: emulator `medium_phone` (API 36, region US), backend via `adb reverse tcp:8080 tcp:8080`, debug build with
`-Pinsta.apiBaseUrl=http://localhost:8080`, Google OAuth client IDs configured, server from this branch with
`OTP_DEV_ECHO=true`, reseeded after the V4 migration. App data cleared before the run.

| # | Step | Result |
|---|------|--------|
| 1 | Welcome: "Continue with Google" primary, "Sign in with phone" secondary | PASS |
| 2–3 | Unknown number 201-555-0150 → "No account is linked to this number. Sign in with Google to create one." + Continue with Google | PASS (`m10-13-no-linked-account.png`) |
| 4 | Country picker: searchable sheet (flags, names, dial codes); "united st" → United States | PASS |
| 5 | maya's number → "Enter the code we sent to +1 ••••••0101.", resend countdown "0:26", dev code shown | PASS |
| 6 | Wrong code → "That code isn't right. 4 tries left.", field cleared | PASS |
| 7 | Dev code → Home feed with seeded posts | PASS |
| 8 | Edit profile → Phone row "+1 201-555-0101" | PASS |
| 9 | Change → 201-555-0199 → code to "+1 ••••••0199" → Save → row shows "+1 201-555-0199" | PASS |
| 10 | Settings → Log out → Welcome | PASS |
| 11 | kai.garden signs in by phone | PASS |
| 12 | Settings → Delete account → Send code (to "+1 ••••••0106") → code → Delete → Welcome | PASS |
| 13 | 201-555-0106 again → "No account is linked to this number" | PASS |
| 14 | Continue with Google → Google account picker → account chosen | PASS |
| 15 | Onboarding prefilled (username from the Google email, Google name) → phone 201-555-0177 → code → Create account → Home | PASS (`m10-google-onboarded.png`) |
| 16 | Log out → Continue with Google, same account → straight to Home (no onboarding) | PASS |

All 16 steps passed on the first run. Real Google ID tokens were verified by the server (JWKS), so the
Google-first path is checked end to end, not only with the test fake.
