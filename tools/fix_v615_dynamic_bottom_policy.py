from pathlib import Path

policy = Path("tools/verify-ui-policy.sh")
text = policy.read_text()
old = 'grep -q \'android:paddingBottom="24dp"\' "$layout" || fail "continuous reader bottom guard must be one character"'
new = (
    'grep -q \'android:paddingBottom="0dp"\' "$layout" || fail "reader XML bottom padding must defer to runtime navigation-bar insets"\n'
    'grep -q \'navigationBarInsetPx + oneCharacterPx\' "$reader" || fail "reader lower limit must leave one character above navigation bar"'
)
if old in text:
    text = text.replace(old, new, 1)
elif 'android:paddingBottom="0dp"' not in text:
    raise SystemExit("old bottom policy rule not found")
policy.write_text(text)
