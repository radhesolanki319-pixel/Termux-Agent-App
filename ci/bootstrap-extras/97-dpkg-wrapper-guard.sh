# dpkg wrapper guard — Termux Agent App
# Har login shell par: agar dpkg self-upgrade ne bin/dpkg wala wrapper
# uda diya ho to restore-dpkg-wrapper.sh use wapas laga deta hai.
# bash se seedha chalao — executable bit par bharosa nahi (Step 17).
if [ -f /data/data/com.tagent/files/usr/share/termux-agent/restore-dpkg-wrapper.sh ]; then
    bash /data/data/com.tagent/files/usr/share/termux-agent/restore-dpkg-wrapper.sh >/dev/null 2>&1 || true
fi
