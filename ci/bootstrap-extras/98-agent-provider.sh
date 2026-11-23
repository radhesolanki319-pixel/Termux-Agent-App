# Termux Agent App: Connect screen par save kiye gaye provider settings
# (provider + API key) har login shell me load karo, taaki andar ke agents
# wahi provider/key use karein jo user ne app me chuna hai.
if [ -f "$HOME/.termux-agent/provider.env" ]; then
	. "$HOME/.termux-agent/provider.env"
else
	export FREELLM_API_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
	export FREELLMAPI_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
	export FREELLMAPI_URL="https://freellmapi-backup.onrender.com/v1"
	export UNIFIED_API_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
	export ANTHROPIC_BASE_URL="https://freellmapi-backup.onrender.com"
	export ANTHROPIC_API_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
	export OPENAI_BASE_URL="https://freellmapi-backup.onrender.com/v1"
	export OPENAI_API_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
fi

# Design: terminal ka card wala rang (gehara grey background) — sirf tab likho
# jab user ne apni colors.properties na banayi ho.
if [ ! -f "$HOME/.termux/colors.properties" ]; then
	mkdir -p "$HOME/.termux"
	printf 'background=#202024\nforeground=#F2F2F5\ncursor=#F2F2F5\n' > "$HOME/.termux/colors.properties"
fi
