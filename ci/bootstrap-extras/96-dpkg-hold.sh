# dpkg hold — Termux Agent App
# dpkg package ko "hold" par rakho taaki `pkg upgrade` ke dauraan dpkg ka
# self-upgrade bin/dpkg par baithe wrapper ko stock binary se na badal de
# (wrapper uda to har package install Permission denied se fail hota hai,
# aur restore guard naye shell se pehle chalta hi nahi).
# Ek baar hold lag jaye to marker ban jata hai, phir yeh kuch nahi karta.
# Hold hatana ho to: apt-mark unhold dpkg
if [ ! -f "$HOME/.termux-agent/dpkg-hold.done" ]; then
    if command -v apt-mark >/dev/null 2>&1; then
        if apt-mark hold dpkg >/dev/null 2>&1; then
            mkdir -p "$HOME/.termux-agent" 2>/dev/null
            touch "$HOME/.termux-agent/dpkg-hold.done" 2>/dev/null
        fi
    fi
fi
