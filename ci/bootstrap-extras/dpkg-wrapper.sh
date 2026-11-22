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

PREFIX=/data/data/com.tagent/files/usr
DPKG_REAL="$PREFIX/bin/dpkg-real"
DPKG_DEB="$PREFIX/bin/dpkg-deb"

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

WORK=$(mktemp -d "$PREFIX/tmp/dpkg-rewrite.XXXXXX") || exec "$DPKG_REAL" "$@"
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
                fi
                if [ "$need" -eq 1 ]; then
                    i=$((i + 1))
                    root="$WORK/root$i"
                    out="$WORK/rewritten$i.deb"
                    mkdir -p "$root"
                    if "$DPKG_DEB" -R "$a" "$root" >/dev/null 2>&1 \
                        && [ -d "$root/data/data/com.termux" ]; then
                        mv "$root/data/data/com.termux" "$root/data/data/com.tagent"
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
