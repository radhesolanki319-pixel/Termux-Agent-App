# dpkg wrapper guard — Termux Agent App
# Har login shell par: agar dpkg self-upgrade ne bin/dpkg wala wrapper
# uda diya ho to restore-dpkg-wrapper.sh use wapas laga deta hai.
if [ -x /data/data/com.tagent/files/usr/share/termux-agent/restore-dpkg-wrapper.sh ]; then
    /data/data/com.tagent/files/usr/share/termux-agent/restore-dpkg-wrapper.sh >/dev/null 2>&1 || true
fi
