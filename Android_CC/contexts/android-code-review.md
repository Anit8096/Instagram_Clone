# Context: review

Mode: find real defects; don't rewrite.
- Use the `compose-reviewer` checklist (state, insets/IME, navigation, accessibility, security).
- Every finding needs file:line, a concrete failure scenario and a fix. Drop speculative ones.
- Check tests exist for new ViewModel/repository logic and that none were weakened.
