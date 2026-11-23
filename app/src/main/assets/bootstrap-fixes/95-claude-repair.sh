# Termux Agent App: Claude Code self-repair (one-shot).
# Naya Claude Code ek chhote "launcher" cli.js ke saath aata hai jo
# platform-native binary mangta hai — woh binary Android par npm
# download hi nahi karta ("claude native binary not installed").
# Asli JS wala cli.js kai MB ka hota hai; launcher sirf kuch KB ka.
# Size se pehchan kar, launcher ho to JS-wale 2.1.112 par khud wapas.
# Marker lagne ke baad dobara kabhi nahi chalta — user baad me khud
# koi aur sahi version lagaye to usse ladna nahi hai.
_tagent_claude_repair() {
    _marker="$HOME/.termux-agent/claude-js.ok"
    [ -f "$_marker" ] && return 0
    _cli="$PREFIX/lib/node_modules/@anthropic-ai/claude-code/cli.js"
    [ -f "$_cli" ] || return 0
    _size=$(wc -c < "$_cli" 2>/dev/null || echo 0)
    if [ "$_size" -ge 1000000 ] 2>/dev/null; then
        mkdir -p "$HOME/.termux-agent" 2>/dev/null
        touch "$_marker" 2>/dev/null
        return 0
    fi
    command -v npm >/dev/null 2>&1 || return 0
    echo "Claude Code ka sahi (JS) version lagaya ja raha hai — sirf ek baar lagega..."
    if npm install -g --force @anthropic-ai/claude-code@2.1.111 >/dev/null 2>&1; then
        mkdir -p "$HOME/.termux-agent" 2>/dev/null
        touch "$_marker" 2>/dev/null
        echo "Claude Code repair ho gaya."
    fi
}
_tagent_claude_repair
unset -f _tagent_claude_repair 2>/dev/null
