# Spec — Google-first sign-in with phone + OTP (replaces email/password)

Approved 2026-10-05. The v1 product spec is summarised in `IMPLEMENTATION_PLAN.md`; this spec changes only
authentication and account identity.

## Summary
- **Sign in with Google is the primary (and only) way to create an account.** A first-time Google user goes through
  onboarding: username, display name and a phone number verified with an OTP. The account is created only after the
  phone is verified.
- **Phone + OTP is a sign-in method for existing accounts only.** It works for a number already verified on an
  account. An unknown number returns **"No linked account"**; it never creates an account.
- **Email/password is removed everywhere**: register and password login (app + server), password hashing, the
  password column, and the Login/Register screens. Email remains only as Google's email on the account.
- Every account therefore has: a Google identity (`google_sub`), a unique verified phone (E.164) and a username.

## Flows
1. **Google, existing account:** Google → signed in.
2. **Google, new user:** Google → **Complete your profile** (username, display name prefilled from Google, country
   picker + phone) → OTP sent to that phone → code entered → account created → signed in.
   - The phone must not be in use by another account ("This number is already linked to another account").
3. **Phone sign-in:** Welcome → "Sign in with phone" → country picker + number →
   - number linked to an account → OTP → code → signed in;
   - no account → "No linked account. Sign in with Google to create one."
4. **Change phone** (Edit profile): new number → OTP to the **new** number → replaces the old one.
5. **Delete account** (Settings): "Send code" → OTP to the account's phone → enter code → deleted. Google
   re-confirmation stays as an alternative.

## OTP rules (server)
- 6 digits, `SecureRandom`; stored only as an HMAC-SHA256 hash; expires after 5 minutes; single use;
  at most 5 wrong attempts per code, then the code is dead.
- Resend: 30 s cooldown per number, at most 5 codes per number per hour, plus a per-IP rate limit on the endpoints.
- Every code is bound to a **purpose** (`login`, `onboarding`, `change_phone`, `delete_account`) and, where it
  applies, to the user or onboarding session; a code for one purpose can't be used for another.
- Delivery through a pluggable `SmsSender`. Default (local): the code is written to the server log. A real
  provider (e.g. Twilio) can be added behind config later.
- **Dev echo:** with `OTP_DEV_ECHO=true` (off by default; set in `.env.example` for local use only) the request
  response also contains the code, so emulator journeys and demos don't need the logs.

## Phone numbers
- Entry: country picker (flag, name, dial code; searchable; default = device region) + national number.
- Validation and formatting with libphonenumber on both sides; stored and sent as E.164 (`+919876543210`).
- Phone numbers are private: returned only in the signed-in user's own account data (`/me`, auth responses),
  never in public profiles, search, followers, comments or chat payloads.

## Data
- Old local accounts are removed by the migration (they have no phone/Google identity). Demo seed accounts get a
  synthetic Google identity (`seed:<username>`) and fictional numbers `+1 555-0101`…`0106`; they sign in by phone.

## ASSUMPTIONS
1. Usernames keep today's rules (3–30 chars, lowercase letters, digits, `.` and `_`); a suggestion is prefilled from
   the Google email/name.
2. Our own access/refresh tokens are unchanged; only how identity is proven changes.
3. No SMS Retriever / auto-fill of codes until a real SMS provider exists.
4. Google sign-up on a device needs the OAuth client IDs (Web + Android); without them only seeded accounts can sign
   in (by phone).

## OPEN RISKS
- **Account enumeration:** "No linked account" tells a caller whether a number is registered (explicitly requested).
  Mitigated by per-IP and per-number rate limits.
- **OTP abuse and SMS cost** once a real provider is used: attempt limits, cooldowns and hourly caps above.
- **Phone recycling:** a number reassigned by a carrier could let its new owner sign in. Mitigation later: keep
  Google as the primary method; optional re-verification after long inactivity (out of scope).
- **Irreversible local data wipe** on migration (local dev data only).
- **Google OAuth setup is now mandatory for sign-up** on a device.
