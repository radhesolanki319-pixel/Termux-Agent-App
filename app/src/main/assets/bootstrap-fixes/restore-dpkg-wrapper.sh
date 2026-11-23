#!/data/data/com.tagent/files/usr/bin/bash
# dpkg wrapper restore — Termux Agent App
#
# Jab dpkg package khud upgrade hota hai to stock ELF bin/dpkg par wapas
# aa jata hai aur wrapper gayab ho jata hai. Yeh script (profile.d guard
# aur dpkg.cfg post-invoke hook dono se chalti hai) check karti hai:
# agar bin/dpkg ek script nahi hai (pehle 2 bytes '#!' nahi — Step 17 se
# od par bharosa nahi, seedha yahi jaanch hai jo asal me chahiye),
# to use bin/dpkg-real bana kar pristine wrapper wapas laga deti hai.
# Hamesha chup-chaap exit 0 — dpkg/shell ko kabhi fail nahi karna.

PREFIX=/data/data/com.tagent/files/usr
WRAPPER="$PREFIX/share/termux-agent/dpkg-wrapper.sh"

[ -f "$WRAPPER" ] || exit 0
[ -d "$PREFIX/bin" ] || exit 0

if [ -f "$PREFIX/bin/dpkg" ]; then
    first2=$(head -c 2 "$PREFIX/bin/dpkg" 2>/dev/null)
    if [ "$first2" != '#!' ]; then
        mv -f "$PREFIX/bin/dpkg" "$PREFIX/bin/dpkg-real" 2>/dev/null
        cp "$WRAPPER" "$PREFIX/bin/dpkg" 2>/dev/null
        chmod 755 "$PREFIX/bin/dpkg" "$PREFIX/bin/dpkg-real" 2>/dev/null
    fi
else
    cp "$WRAPPER" "$PREFIX/bin/dpkg" 2>/dev/null
    chmod 755 "$PREFIX/bin/dpkg" 2>/dev/null
fi

exit 0
