#!/data/data/com.tagent/files/usr/bin/bash
# dpkg wrapper — Termux Agent App (package com.tagent)
#
# Upstream Termux .deb packages ke andar payload ka path
# /data/data/com.termux/files/usr/... hota hai. Stock dpkg woh path
# literally extract karta hai -> woh directory asal Termux app ki hai,
# is app ko wahan Permission denied milta hai aur pura pkg install fail
# ho jata hai (DPKG_FIX_REPORT.md).
#
# Yeh wrapper bin/dpkg ki jagah baithta hai (asal binary bin/dpkg-real hai):
# har .deb ko pehle com.tagent path me rewrite karke dpkg-real ko deta hai.
# Bina .deb wale saare calls (--configure, --remove, queries waghera)
# seedha pass-through hote hain.
#
# Rewrite kahin fail ho to wajah $HOME/dpkg_wrapper.log me likhi jati hai
# (chup-chaap original .deb pass karne se asal error dikhta hi nahi tha).

PREFIX=/data/data/com.tagent/files/usr
DPKG_REAL="$PREFIX/bin/dpkg-real"
DPKG_DEB="$PREFIX/bin/dpkg-deb"
LOG="${HOME:-/data/data/com.tagent/files/home}/dpkg_wrapper.log"

log() {
    printf '%s\n' "$*" >> "$LOG" 2>/dev/null || true
    mkdir -p "/storage/emulated/0/Download/tagent_logs" 2>/dev/null || true
    printf '%s\n' "$*" >> "/storage/emulated/0/Download/tagent_logs/dpkg_wrapper.log" 2>/dev/null || true
}

# Koi .deb argument hai hi nahi -> pass-through
has_deb=0
for a in "$@"; do
    case "$a" in
        *.deb) if [ -f "$a" ]; then has_deb=1; break; fi ;;
    esac
done
if [ "$has_deb" -eq 0 ]; then
    exec "$DPKG_REAL" "$@"
fi

mkdir -p "$PREFIX/tmp" 2>/dev/null
WORK=$(mktemp -d "$PREFIX/tmp/dpkg-rewrite.XXXXXX") || {
    log "FAIL mktemp: tmp dir nahi bana, saare .deb bina rewrite ke gaye: $*"
    exec "$DPKG_REAL" "$@"
}
trap 'rm -rf "$WORK"' EXIT

new_args=()
i=0
for a in "$@"; do
    case "$a" in
        *.deb)
            rewritten=""
            if [ -f "$a" ]; then
                # Sirf tab rewrite karo jab package ke andar purana path ho.
                # Detection fail ho jaye to bhi rewrite karo (safe default).
                need=1
                if listing=$("$DPKG_DEB" -c "$a" 2>/dev/null); then
                    case "$listing" in
                        *data/data/com.termux/*) need=1 ;;
                        *) need=0 ;;
                    esac
                else
                    log "WARN detection fail (dpkg-deb -c), rewrite try hoga: $a"
                fi
                if [ "$need" -eq 1 ]; then
                    i=$((i + 1))
                    root="$WORK/root$i"
                    out="$WORK/rewritten$i.deb"
                    mkdir -p "$root"
                    if ! "$DPKG_DEB" -R "$a" "$root" >/dev/null 2>&1; then
                        log "FAIL extract (dpkg-deb -R): $a"
                    elif [ ! -d "$root/data/data/com.termux" ]; then
                        log "FAIL extract ke baad data/data/com.termux dir nahi mila: $a"
                    else
                        mv "$root/data/data/com.termux" "$root/data/data/com.tagent"
                        # Termux .deb ke maintainer scripts control.tar me 0644
                        # mode ke saath aate hain; dpkg-deb -R unhe waisa hi
                        # nikalta hai aur dpkg-deb --build bina exec permission
                        # wale scripts ko reject kar deta hai (build fail ->
                        # original .deb pass -> stat Permission denied).
                        # Asal dpkg install ke samay khud chmod karta hai;
                        # yahan build se pehle wahi fix-up karo.
                        for s in preinst postinst prerm postrm config; do
                            if [ -f "$root/DEBIAN/$s" ]; then
                                chmod 755 "$root/DEBIAN/$s" 2>/dev/null
                            fi
                        done
                        # CRITICAL FIX (Bug #1): Android umask 0077 causes $root and $root/DEBIAN
                        # to be created with 0700 mode. dpkg-deb --build strictly rejects DEBIAN with:
                        # "control directory has bad permissions 700 (must be >=0755 and <=0775)".
                        # Set 0755 on $root, $root/DEBIAN, and all directory trees:
                        chmod 755 "$root" "$root/DEBIAN" 2>/dev/null
                        find "$root" -type d -exec chmod 755 {} + 2>/dev/null
                        grep -rl 'com\.termux' "$root" 2>/dev/null | while IFS= read -r f; do
                            sed -i 's|com\.termux|com.tagent|g' "$f"
                        done
                        find "$root" -type l | while IFS= read -r l; do
                            t=$(readlink "$l")
                            case "$t" in
                                *com.termux*)
                                    ln -snf "$(printf %s "$t" | sed 's|com\.termux|com.tagent|g')" "$l" ;;
                            esac
                        done
                        if [ -f "$root/DEBIAN/md5sums" ]; then
                            ( cd "$root" && find data -type f -print0 | LC_ALL=C sort -z \
                                | xargs -0 md5sum > DEBIAN/md5sums.tmp \
                                && mv DEBIAN/md5sums.tmp DEBIAN/md5sums )
                        fi
                        if "$DPKG_DEB" --build "$root" "$out" >/dev/null 2>&1; then
                            rewritten="$out"
                        else
                            log "FAIL rebuild (dpkg-deb --build): $a"
                        fi
                    fi
                fi
            fi
            if [ -n "$rewritten" ]; then
                new_args+=("$rewritten")
            else
                new_args+=("$a")
            fi
            ;;
        *)
            new_args+=("$a")
            ;;
    esac
done

"$DPKG_REAL" "${new_args[@]}"
rc=$?
exit $rc
